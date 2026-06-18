(ns leiningen.skeptic-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [leiningen.core.classpath]
            [leiningen.core.eval]
            [leiningen.skeptic :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "lein-skeptic-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(defn- no-resolved-jars
  [& _args]
  [])

;; -- Launcher namespace has zero skeptic.* deps --------------------------

(defn- launcher-required-ns-syms
  "Read leiningen/skeptic.clj from the classpath, extract the (ns ...) form,
   and return the set of namespace symbols actually named in its :require
   clause. Skips docstrings and any other text in the file."
  []
  (let [source (slurp (io/resource "leiningen/skeptic.clj"))
        ns-form (read-string source)
        require-clause (->> (filter list? ns-form)
                            (some #(when (= :require (first %)) %)))
        spec->sym (fn [spec]
                    (cond
                      (symbol? spec) spec
                      (vector? spec) (first spec)
                      :else nil))]
    (into #{} (keep spec->sym) (rest require-clause))))

(defn- any-ns-prefixed? [prefix syms]
  (some (fn [s] (str/starts-with? (str s) prefix)) syms))

(deftest launcher-namespace-has-no-skeptic-requires
  (testing "lein-skeptic plugin namespace must not :require any skeptic.* code, schema, malli, nrepl, or transit — the launcher is a zero-transitive shim"
    (let [required (launcher-required-ns-syms)]
      (is (not (any-ns-prefixed? "skeptic." required))
          (str ":require must not name any skeptic.* namespace; got " required))
      (is (not (any-ns-prefixed? "schema." required))
          (str ":require must not name schema.*; got " required))
      (is (not (any-ns-prefixed? "malli." required))
          (str ":require must not name malli.*; got " required))
      (is (not (any-ns-prefixed? "nrepl." required))
          (str ":require must not name nrepl.*; got " required))
      (is (not (any-ns-prefixed? "transit." required))
          (str ":require must not name transit.*; got " required))
      (is (not (any-ns-prefixed? "cognitect.transit" required))
          (str ":require must not name cognitect.transit; got " required)))))

;; -- Reading host-deps / worker-deps from inside a jar ------------------

(deftest read-skeptic-vector-extracts-quoted-literal
  (testing "read-skeptic-vector reads (def NAME 'LITERAL) from a clj file in a jar without requiring the namespace"
    (let [dir (temp-dir!)
          jar (io/file dir "fake-skeptic.jar")]
      (try
        (let [src (str "(ns skeptic.host.deps)\n"
                       "(def host-deps '[[org.clojure/clojure \"1.12.0\"]])\n")
              ;; Build a minimal jar with one entry
              fos (java.io.FileOutputStream. jar)
              jos (java.util.jar.JarOutputStream. fos)]
          (.putNextEntry jos (java.util.jar.JarEntry. "skeptic/host/deps.clj"))
          (.write jos (.getBytes ^String src "UTF-8"))
          (.closeEntry jos)
          (.close jos))
        (let [v (#'sut/read-skeptic-vector (.getPath jar)
                                           "skeptic/host/deps.clj"
                                           'host-deps)]
          (is (= '[[org.clojure/clojure "1.12.0"]] v)))
        (finally
          (delete-recursively! dir))))))

;; -- Aether synthetic-project shape ------------------------------------

(deftest synthetic-project-inherits-repo-connection-keys-only
  (testing "synthetic project inherits :repositories, :mirrors, :certificates, :local-repo from the user project; does NOT inherit :dependencies, :plugins, :managed-dependencies"
    (let [project {:dependencies          [['foo "1.0"]]
                   :plugins               [['bar "2.0"]]
                   :managed-dependencies  [['baz "3.0"]]
                   :repositories          {"clojars" "https://repo.clojars.org"}
                   :mirrors               {"central" "https://mirror.example/"}
                   :certificates          ["cert.pem"]
                   :local-repo            "/tmp/m2"}
          deps    [['org.clojure/clojure "1.12.0"]]
          synth   (#'sut/synthetic-project project deps)]
      (is (= deps (:dependencies synth)))
      (is (= (:repositories project)  (:repositories synth)))
      (is (= (:mirrors project)       (:mirrors synth)))
      (is (= (:certificates project)  (:certificates synth)))
      (is (= (:local-repo project)    (:local-repo synth)))
      (is (not (contains? synth :plugins)))
      (is (not (contains? synth :managed-dependencies))))))

;; -- Worker classpath inlined assembly ----------------------------------

(deftest worker-cp-string-is-project-first
  (testing "worker -cp string puts project entries first, worker jars second, skeptic self-entry last; distinct preserves first-occurrence so shared coords resolve to project's pin"
    (let [combined (#'sut/worker-cp-string
                    ["/m2/clojure-1.12.0.jar"
                     "/m2/tools.reader-1.6.0.jar"]
                    ["/proj/test"
                     "/proj/src"
                     "/m2/clojure-1.10.3.jar"
                     "/m2/tools.reader-1.3.6.jar"]
                    "/m2/skeptic-rc9.jar")
          entries (str/split combined (re-pattern (java.util.regex.Pattern/quote
                                                   java.io.File/pathSeparator)))]
      (is (= ["/proj/test"
              "/proj/src"
              "/m2/clojure-1.10.3.jar"
              "/m2/tools.reader-1.3.6.jar"
              "/m2/clojure-1.12.0.jar"
              "/m2/tools.reader-1.6.0.jar"
              "/m2/skeptic-rc9.jar"]
             entries)
          "project-first ordering with distinct first-occurrence semantics"))))

;; -- Worker launch command augmentation ---------------------------------

(deftest worker-launch-command-replaces-classpath-only
  (testing "augment-classpath-arg replaces -classpath but not -javaagent or arbitrary flags"
    (let [combine (fn [_] "REPLACED")
          cmd     ["java" "-javaagent:/path/foo.jar" "-Dother=true"
                   "-classpath" "/orig/cp" "clojure.main"]
          augmented (mapv #(#'sut/augment-classpath-arg %1 %2 combine)
                          (cons nil cmd) cmd)]
      (is (= "java" (nth augmented 0)))
      (is (= "-javaagent:/path/foo.jar" (nth augmented 1))
          "-javaagent flags pass through unchanged")
      (is (= "-Dother=true" (nth augmented 2)))
      (is (= "-classpath" (nth augmented 3)))
      (is (= "REPLACED" (nth augmented 4))
          "-classpath value is replaced with our combined cp")
      (is (= "clojure.main" (nth augmented 5))))))

(deftest worker-launch-command-produces-project-first-cp
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        project {:root root :source-paths [(str (io/file root "src"))] :test-paths []
                 :dependencies [['org.clojure/clojure "1.12.0"]]}]
    (try
      (.mkdirs (io/file root "src"))
      (with-redefs [leiningen.core.eval/shell-command
                    (fn [_ _form]
                      ["java" "-classpath" "/proj/cp" "clojure.main" "-i" "init.clj"])]
        (let [worker-form (#'sut/project-worker-form project (io/file root "worker.port"))
              cmd (#'sut/worker-launch-command project worker-form
                                               ["worker.jar"] ["plugin.jar"]
                                               "/m2/skeptic.jar")
              entries (str/split (nth cmd 2)
                                 (re-pattern (java.util.regex.Pattern/quote
                                              java.io.File/pathSeparator)))]
          (is (= ["java" "-classpath"] (subvec cmd 0 2)))
          (is (= ["/proj/cp" "plugin.jar" "worker.jar" "/m2/skeptic.jar"]
                 (vec entries))
              "project entries first, then plugin jars, then worker jars, then skeptic self-entry")))
      (finally
        (delete-recursively! dir)))))

;; -- Worker form preserves lein eval-in-project ordering ---------------

(deftest project-worker-form-matches-lein-eval-in-project-ordering
  ;; Expected ordering per lein/core/eval.clj:368-374:
  ;; warn-on-reflection set, global-vars sets, require (skeptic-specific
  ;; init), injections, run-worker!
  (let [project {:root "/tmp"
                 :warn-on-reflection true
                 :global-vars {'*unchecked-math* :warn-on-boxed}
                 :injections ['(register-project-readers!)]}
        form    (#'sut/project-worker-form project (io/file "/tmp/x.port"))
        steps   (rest form)] ; drop 'do
    (is (= 'set! (first (nth steps 0))))
    (is (= '*warn-on-reflection* (second (nth steps 0))))
    (is (= true (last (nth steps 0))))
    (is (= 'set! (first (nth steps 1))))
    (is (= '*unchecked-math* (second (nth steps 1))))
    ;; syntax-quote namespaces `require` to clojure.core; the quoted ns
    ;; survives as a (quote ...) form, not a reader-quote token.
    (is (= '(clojure.core/require (quote skeptic.worker.server))
           (nth steps 2)))
    (is (= '(register-project-readers!) (nth steps 3)))
    (is (= 'skeptic.worker.server/run-worker! (first (nth steps 4))))))

;; -- discover-cljs-paths reads project map only -------------------------

(deftest discover-cljs-paths-reads-project-map-without-skeptic-deps
  (let [project {:root "/tmp/proj"
                 :source-paths ["/abs/src"]
                 :test-paths ["/abs/test"]
                 :cljsbuild {:builds {:dev {:source-paths ["src/cljs"
                                                          "/abs/cljs"]}}}}
        paths   (#'sut/discover-cljs-paths project)]
    (is (some #{"/abs/src"} paths))
    (is (some #{"/abs/test"} paths))
    (is (some #{"/tmp/proj/src/cljs"} paths)
        "relative cljsbuild source-paths are absolutized against :root")
    (is (some #{"/abs/cljs"} paths)
        "absolute cljsbuild source-paths pass through unchanged")))

;; -- Worker process output capture -------------------------------------
;; The worker spawn machinery is mostly unchanged from rc9; these tests
;; cover its remaining surface in the launcher.

(deftest worker-reports-child-exit-with-captured-output
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        project {:root root :source-paths [] :test-paths []}
        thrown
        (with-redefs [leiningen.core.eval/prep (fn [_] nil)
                      leiningen.core.classpath/resolve-managed-dependencies
                      no-resolved-jars
                      leiningen.core.eval/shell-command
                      (fn [_ _form]
                        ["/bin/sh" "-c"
                         "printf 'launch out\\n'; printf 'launch err\\n' >&2; exit 7"])]
          (try
            (#'sut/spawn-project-worker! project [] [] [] "/fake/skeptic.jar" false)
            nil
            (catch clojure.lang.ExceptionInfo e
              e)))]
    (try
      (is (some? thrown))
      (is (= 7 (:exit-code (ex-data thrown))))
      (is (= ["launch out"] (:worker-stdout (ex-data thrown))))
      (is (= ["launch err"] (:worker-stderr (ex-data thrown))))
      (is (.contains (.getMessage thrown) "Worker stdout:\nlaunch out"))
      (is (.contains (.getMessage thrown) "Worker stderr:\nlaunch err"))
      (finally
        (delete-recursively! dir)))))

;; -- --help short-circuits without spawning ----------------------------

(deftest help-requested-short-circuits-spawn
  (testing "--help and -h are recognized launcher-side via exact match"
    (is (#'sut/help-requested? ["--help"]))
    (is (#'sut/help-requested? ["-h"]))
    (is (#'sut/help-requested? ["--verbose" "--help"]))
    (is (not (#'sut/help-requested? ["--help-foo"]))
        "substring matches must NOT fire — exact-match only")
    (is (not (#'sut/help-requested? ["help"]))
        "positional 'help' is not a flag")
    (is (not (#'sut/help-requested? [])))))
