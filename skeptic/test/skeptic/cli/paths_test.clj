(ns skeptic.cli.paths-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.paths :as paths])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-cli-paths-"
                                      (into-array FileAttribute []))))

(defn- write-deps!
  [dir contents]
  (let [f (io/file dir "deps.edn")]
    (spit f (pr-str contents))
    f))

(defn- write-source!
  [dir rel-path contents]
  (let [f (io/file dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f contents)
    f))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(defn- source-files [ctx]
  (mapv #(.getName ^java.io.File %) (:source-files ctx)))

(defn- failures [ctx]
  (vec (:source-discovery-failures ctx)))

(defn- failure-for [ctx path-suffix]
  (some #(when (str/ends-with? (str (:path %)) path-suffix) %)
        (failures ctx)))

;; ---------------------------------------------------------------------------
;; Existing tests preserved

(deftest discover-paths-reads-paths-key
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src" "test"]})
      (let [discovered (paths/discover-paths (.getAbsolutePath dir) [])]
        (is (some #{"src"} discovered))
        (is (some #{"test"} discovered)))
      (finally (delete-recursively! dir)))))

(deftest discover-paths-merges-alias-extra-paths
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]
                        :aliases {:dev {:extra-paths ["dev"]}}})
      (testing "without alias selection, :extra-paths are not merged"
        (is (not (some #{"dev"} (paths/discover-paths (.getAbsolutePath dir) [])))))
      (testing "with alias selection, :extra-paths are merged"
        (let [discovered (paths/discover-paths (.getAbsolutePath dir) [:dev])]
          (is (some #{"src"} discovered))
          (is (some #{"dev"} discovered))))
      (finally (delete-recursively! dir)))))

(deftest discover-paths-throws-when-deps-edn-missing
  (let [dir (temp-dir!)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No deps\.edn"
                            (paths/discover-paths (.getAbsolutePath dir) [])))
      (finally (delete-recursively! dir)))))

;; ---------------------------------------------------------------------------
;; Falsifier tests for the eligibility filter

(deftest project-only-stdlib-deps-are-accepted
  ;; A file requiring only clojure stdlib + nothing else passes eligibility.
  ;; This is the SURVIVAL test for stdlib resolution; falsified if we ever
  ;; re-introduce a prefix-list whitelist that misidentifies what's "stdlib".
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/stdlib.clj"
                     "(ns demo.stdlib (:require [clojure.string :as s] [clojure.set :as set]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (= ["stdlib.clj"] (source-files ctx)))
        (is (empty? (failures ctx))))
      (finally (delete-recursively! dir)))))

(deftest clojure-core-async-is-rejected-when-basis-lacks-it
  ;; FALSIFIER for the previous session's bug. clojure.core.async shares the
  ;; clojure.* prefix with stdlib but is a separate Maven artifact. Without
  ;; an explicit dep in deps.edn, the basis has no core.async jar, so the
  ;; URLClassLoader probe returns nil for clojure/core/async.* and the file
  ;; MUST be rejected. The pre-fix prefix-whitelist bug would have accepted
  ;; this file and crashed the worker with FileNotFoundException.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/async_user.clj"
                     "(ns demo.async-user (:require [clojure.core.async :as a]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])
            f (failure-for ctx "async_user.clj")]
        (is (empty? (source-files ctx))
            "file MUST be rejected; sending it to the worker would crash with FNF")
        (is (some? f) "rejection MUST emit a failure entry")
        (is (= ["clojure.core.async"] (:unresolvable-deps f))))
      (finally (delete-recursively! dir)))))

(deftest clojure-core-async-is-accepted-when-basis-declares-it
  ;; Symmetric to the previous test: with the dep declared, the basis carries
  ;; the core.async jar, the probe finds clojure/core/async.clj inside it,
  ;; and the file passes.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]
                        :deps {'org.clojure/core.async {:mvn/version "1.6.681"}}})
      (write-source! dir "src/demo/async_user.clj"
                     "(ns demo.async-user (:require [clojure.core.async :as a]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (= ["async_user.clj"] (source-files ctx)))
        (is (empty? (filter :unresolvable-deps (failures ctx)))))
      (finally (delete-recursively! dir)))))

(deftest transitive-rejection-closure-blocks-dependent-files
  ;; FALSIFIER for the chained-rejection attack (A13/A15). File F requires
  ;; project G; project G requires unresolvable.dep. F's per-file
  ;; eligibility would PASS (G is in project-namespaces). But sending F to
  ;; the worker would still crash via the require chain. The transitive
  ;; closure must reject F too.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/g.clj"
                     "(ns demo.g (:require [clojure.core.async :as a]))")
      (write-source! dir "src/demo/f.clj"
                     "(ns demo.f (:require [demo.g :as g]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])
            f-fail (failure-for ctx "f.clj")
            g-fail (failure-for ctx "g.clj")]
        (is (empty? (source-files ctx))
            "BOTH F and G must be rejected; sending F crashes the worker via require chain")
        (is (some? g-fail) "G rejection emitted")
        (is (= ["clojure.core.async"] (:unresolvable-deps g-fail)))
        (is (some? f-fail) "F rejection emitted")
        (is (= ["clojure.core.async"] (:unresolvable-deps f-fail))
            "F's :unresolvable-deps carries the ROOT unresolvable dep (not 'demo.g')"))
      (finally (delete-recursively! dir)))))

(deftest as-alias-libspec-does-not-cause-rejection
  ;; Clojure 1.11+ :as-alias declares an alias without loading. The
  ;; extractor must NOT yield the libspec's symbol as a dep. Falsified if
  ;; we treat :as-alias-decorated libspecs the same as regular :require.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/alias_user.clj"
                     "(ns demo.alias-user (:require [no.such.lib :as-alias nsl]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (= ["alias_user.clj"] (source-files ctx))
            "file MUST pass; :as-alias [no.such.lib] is not a runtime dep"))
      (finally (delete-recursively! dir)))))

(deftest in-ns-form-recognized-as-project-namespace
  ;; A file declaring its ns via (in-ns 'sym) (rather than (ns sym ...)) is
  ;; still a project file. Other project files requiring it must see its ns
  ;; in project-namespaces, so they pass eligibility.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/in_ns_decl.clj"
                     "(in-ns 'demo.in-ns-decl)")
      (write-source! dir "src/demo/uses_in_ns.clj"
                     "(ns demo.uses-in-ns (:require [demo.in-ns-decl :as d]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (= 2 (count (source-files ctx)))
            "both files pass; in-ns-declared ns counts as project namespace")
        (is (empty? (filter :unresolvable-deps (failures ctx)))))
      (finally (delete-recursively! dir)))))

(deftest empty-libspec-vector-does-not-emit-nil
  ;; (:require []) is malformed but should not crash the extractor with
  ;; a NullPointerException from URLClassLoader.findResource(nil).
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/malformed.clj"
                     "(ns demo.malformed (:require [] [clojure.set]))")
      ;; The file must EITHER pass (treating [] as no-op) OR be rejected
      ;; cleanly. It MUST NOT throw.
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (some? ctx) "did not throw"))
      (finally (delete-recursively! dir)))))

(deftest reader-eval-form-does-not-execute-during-discovery
  ;; Security: a hostile project's ns-form containing #=(...) MUST NOT
  ;; evaluate during discovery. Otherwise a `clj -T:skeptic check` against
  ;; a downloaded project could execute arbitrary code.
  ;;
  ;; Implementation note: tools.reader already disregards #= forms by
  ;; default (does not call evaluator); this test confirms the behavior
  ;; survives any future reader-options changes. The test asserts that the
  ;; sentinel side-effect file is NOT created during discovery.
  (let [dir (temp-dir!)
        sentinel (io/file dir "BOOM-touched")]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/hostile.clj"
                     (str "(ns demo.hostile (:require "
                          "#=(do (spit \""
                          (.getAbsolutePath sentinel)
                          "\" \"executed\") [clojure.set])))"))
      (paths/project-context (.getAbsolutePath dir) [])
      (is (not (.exists sentinel))
          "ns-form reading MUST NOT execute #= forms during discovery")
      (finally (delete-recursively! dir)))))

(deftest paths-override-flows-through-eligibility
  ;; FALSIFIER for the followup's bug #3: with the gate removed, a user
  ;; supplying explicit source-paths-override (the :paths equivalent) must
  ;; still see eligibility filtering happen, with rejected files appearing
  ;; in :source-discovery-failures.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/ok.clj"
                     "(ns demo.ok (:require [clojure.set]))")
      (write-source! dir "src/demo/broken.clj"
                     "(ns demo.broken (:require [clojure.core.async]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) []
                                       {:source-paths-override ["src"]})]
        (is (= ["ok.clj"] (source-files ctx))
            "broken.clj is rejected even under :paths-override")
        (is (some? (failure-for ctx "broken.clj"))
            "rejection emits a ns-discovery-warning under :paths-override too"))
      (finally (delete-recursively! dir)))))

(deftest aot-only-jar-resource-finds-init-class
  ;; FALSIFIER for A4: a library shipped AOT-only (foo__init.class but no
  ;; foo.clj source) must still be found by the probe. Real-world examples
  ;; in m2 cache include borkdude/edamame's bundled tools.reader copies.
  ;;
  ;; This test uses a synthetic JAR rather than an m2 dep to keep the
  ;; suite hermetic.
  (let [dir (temp-dir!)
        jar-file (io/file dir "aot.jar")]
    (try
      (write-deps! dir {:paths ["src"]
                        :deps {'org.skeptic.test/aot-only {:local/root (.getAbsolutePath jar-file)}}})
      ;; Build a minimal jar containing only foo/bar__init.class
      (with-open [zos (java.util.zip.ZipOutputStream.
                       (io/output-stream jar-file))]
        (.putNextEntry zos (java.util.zip.ZipEntry. "foo/bar__init.class"))
        (.write zos (byte-array 0))
        (.closeEntry zos))
      (write-source! dir "src/demo/aot_user.clj"
                     "(ns demo.aot-user (:require [foo.bar :as fb]))")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        (is (= ["aot_user.clj"] (source-files ctx))
            "AOT-only library must be found via __init.class probe"))
      (finally (delete-recursively! dir)))))

(deftest read-error-file-is-not-rejected
  ;; A file whose ns-form can't be parsed remains in the worker's input set
  ;; with a :read-error entry. The worker is the authority for parse-time
  ;; failures; discovery does not preempt it with a ns-discovery-warning.
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]})
      (write-source! dir "src/demo/broken_syntax.clj"
                     "(ns demo.broken-syntax (:require [#[malformed)")
      (let [ctx (paths/project-context (.getAbsolutePath dir) [])]
        ;; Either the file passes through to the worker OR it doesn't reach the
        ;; eligibility filter at all. The contract is: NO :unresolvable-deps
        ;; failure record for a read-error file.
        (is (empty? (filter :unresolvable-deps (failures ctx)))
            "read-error files do not emit ns-discovery-warning"))
      (finally (delete-recursively! dir)))))
