(ns skeptic.core-cljs-test
  "Phase 9 end-to-end smoke gate: `core/check-project` discovers and checks
  .clj / .cljs / .cljc files in a single run, with `:lang` attribution on
  each finding. `:cljs-disable` (set unless `--cljs-enable` was passed)
  filters .cljs files at discovery and collapses .cljc to a `:clj`-only
  pass. Reuses the Phase 7 p7 fixture."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.core :as sut]
            [skeptic.cli.options :as opts]
            [skeptic.test-support.worker-opts :refer [with-worker-cp]]))

(def ^:private fixture-path "dev-resources/skeptic/cljs_fixtures/p7")

(defn- parse-jsonl [s]
  (->> (str/split-lines s)
       (remove str/blank?)
       (mapv #(json/read-str % :key-fn keyword))))

(defn- findings [lines]
  (filter #(= "finding" (:kind %)) lines))

(defn- finding-lang [finding]
  (get-in finding [:location :lang]))

(defn- finding-file [finding]
  (get-in finding [:location :file]))

(deftest cljs-enable-flag-parses
  (let [{:keys [options]} (opts/parse [])]
    (is (true? (:cljs-disable options))
        "cljs intake is off by default: :cljs-disable true when --cljs-enable is absent"))
  (let [{:keys [options]} (opts/parse ["--cljs-enable"])]
    (is (false? (:cljs-disable options))
        "--cljs-enable should appear as :cljs-disable false on the opts map")))

(defn- finding-for-basename [findings basename]
  (some #(when (str/ends-with? (or (finding-file %) "") basename) %) findings))

(deftest check-project-end-to-end-with-cljs-and-cljc
  (testing "default run picks up .clj, .cljs, and .cljc and tags each finding"
    (let [out (with-out-str
                (sut/check-project (with-worker-cp {:porcelain true}) "." fixture-path))
          fs (findings (parse-jsonl out))
          clj-finding (finding-for-basename fs "/foo.clj")
          cljs-finding (finding-for-basename fs "/bar.cljs")
          cljc-finding (finding-for-basename fs "/baz.cljc")]
      (is (some? clj-finding)  "foo.clj produces a finding")
      (is (some? cljs-finding) "bar.cljs produces a finding (.cljs discovered end to end)")
      (is (some? cljc-finding) "baz.cljc produces a finding")
      (is (= "clj"  (finding-lang clj-finding))  ":lang :clj on .clj finding")
      (is (= "cljs" (finding-lang cljs-finding)) ":lang :cljs on .cljs finding")
      (is (= ["clj" "cljs"] (sort (finding-lang cljc-finding)))
          ":lang #{:clj :cljs} (JSONL sorted array) on .cljc finding after dedup"))))

(deftest cljs-disable-skips-cljs-files-and-collapses-cljc
  (testing ":cljs-disable drops .cljs files and runs .cljc as :clj only"
    (let [out (with-out-str
                (sut/check-project (with-worker-cp {:porcelain true :cljs-disable true}) "." fixture-path))
          fs (findings (parse-jsonl out))
          clj-finding (finding-for-basename fs "/foo.clj")
          cljs-finding (finding-for-basename fs "/bar.cljs")
          cljc-finding (finding-for-basename fs "/baz.cljc")]
      (is (some? clj-finding) "foo.clj still produces a finding")
      (is (nil? cljs-finding) "bar.cljs is filtered at discovery under :cljs-disable")
      (is (some? cljc-finding) "baz.cljc still produces a finding (clj branch)")
      (is (= "clj" (finding-lang cljc-finding))
          ".cljc collapses to :clj-only pass under :cljs-disable"))))
