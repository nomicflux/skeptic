(ns skeptic.cli.main-test
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.main :as main])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-main-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(def ^:private skeptic-deps
  '{prismatic/schema {:mvn/version "1.4.1"}
    prismatic/plumbing {:mvn/version "0.6.0"}
    metosin/malli {:mvn/version "0.20.1"}
    org.clojure/clojure {:mvn/version "1.12.0"}
    org.clojure/clojurescript {:mvn/version "1.11.132"}
    org.clojure/data.json {:mvn/version "2.5.1"}
    org.clojure/tools.analyzer {:mvn/version "1.2.2"}
    org.clojure/tools.analyzer.jvm {:mvn/version "1.4.0-beta1"}
    org.clojure/tools.cli {:mvn/version "1.0.214"}
    org.clojure/tools.deps {:mvn/version "0.29.1598"}
    org.babashka/sci {:mvn/version "0.12.51"}
    commons-io/commons-io {:mvn/version "2.11.0"}
    com.taoensso/nippy {:mvn/version "3.4.2"}
    nrepl/nrepl {:mvn/version "1.3.1"}})

(defn- deps-edn-source
  []
  (pr-str {:paths ["src"
                   (.getPath (io/file (System/getProperty "user.dir") "src"))
                   (.getPath (io/file (System/getProperty "user.dir") "resources"))]
           :deps skeptic-deps}))

(defn- selected-basis-deps-edn-source
  []
  (pr-str {:paths ["src"]
           :deps (select-keys skeptic-deps
                              '[org.clojure/clojure
                                prismatic/schema
                                prismatic/plumbing])
           :aliases {:malli {:extra-deps
                             (select-keys skeptic-deps '[metosin/malli])}}}))

(def ^:private clean-source
  "(ns demo.core (:require [schema.core :as s]))\n
(s/defn add-one :- s/Int [x :- s/Int] (+ x 1))\n")

(def ^:private bad-source
  "A planted output mismatch: the body returns a String where the declared
  return schema is s/Int. The deps path must CHECK this and report a finding."
  "(ns demo.core (:require [schema.core :as s]))\n
(s/defn add-one :- s/Int [x :- s/Int] (str x))\n")

(def ^:private selected-basis-core-source
  "(ns demo.core (:require [schema.core :as s]))\n
(s/defn add-one :- s/Int [x :- s/Int] (+ x 1))\n")

(def ^:private selected-basis-optional-source
  "(ns demo.optional
  (:require [schema.core :as s]
            [malli.core :as m]))\n
(def sample-schema (m/schema [:map [:x :int]]))\n
(s/defn optional-id :- s/Int [x :- s/Int] x)\n")

(defn- check-source!
  "Write `source` as demo.core in a temp deps project, run the deps -T path over
  it with a live worker, and return the exit code (0 clean, 1 findings)."
  [source]
  (let [dir (temp-dir!)]
    (try
      (spit (io/file dir "deps.edn") (deps-edn-source))
      (.mkdirs (io/file dir "src" "demo"))
      (spit (io/file dir "src" "demo" "core.clj") source)
      (main/check-project {:project-dir (.getPath dir) :paths "src"})
      (finally
        (delete-recursively! dir)))))

(defn- json-records
  [output]
  (->> (str/split-lines output)
       (remove str/blank?)
       (map #(json/read-str % :key-fn keyword))
       vec))

(defn- run-summary
  [records]
  (some #(when (= "run-summary" (:kind %)) %) records))

(defn- write-selected-basis-project!
  [dir]
  (spit (io/file dir "deps.edn") (selected-basis-deps-edn-source))
  (.mkdirs (io/file dir "src" "demo"))
  (spit (io/file dir "src" "demo" "core.clj") selected-basis-core-source)
  (spit (io/file dir "src" "demo" "optional.clj") selected-basis-optional-source))

(defn- check-selected-basis-project!
  [dir opts]
  (let [exit (atom nil)
        output (with-out-str
                 (reset! exit (main/check-project
                                (merge {:project-dir (.getPath dir)
                                        :porcelain true}
                                       opts))))]
    {:exit @exit
     :records (json-records output)
     :output output}))

(deftest legacy-m-entrypoint-is-rejected
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (main/run-cli ["--help"])))))]
    (is (str/includes? err "clojure -M:skeptic is unsupported"))
    (is (str/includes? err "clj -T:skeptic check"))))

(deftest legacy-m-entrypoint-does-not-parse-project-options
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (main/run-cli ["--bogus-flag-xyz"])))))]
    (is (not (str/includes? err "Unknown option")))
    (is (str/includes? err "deps.edn tool alias"))))

(deftest deps-cli-options-present
  (testing "the deps.edn-side options are exposed as a vector for reuse"
    (is (vector? main/deps-cli-options))
    (is (some #(= "--paths PATHS" (nth % 1 nil)) main/deps-cli-options))
    (is (some #(= "--alias ALIAS" (nth % 1 nil)) main/deps-cli-options))))

(deftest deps-entrypoint-checks-project-code-end-to-end
  (testing "a clean project is checked and reports no findings (exit 0)"
    (is (= 0 (check-source! clean-source))
        "the deps path ran the checker over project code with a live worker"))
  (testing "a planted output mismatch is CHECKED and reported (exit 1)"
    (is (= 1 (check-source! bad-source))
        "the deps path admits the s/defn schema and flags the String/Int mismatch — proving project code is actually checked, not skipped")))

(deftest deps-entrypoint-selects-source-files-from-selected-basis
  (let [dir (temp-dir!)]
    (try
      (write-selected-basis-project! dir)
      (testing "the default basis does not analyze a namespace whose direct deps are unavailable"
        (let [{:keys [exit records output]} (check-selected-basis-project! dir {})
              summary (run-summary records)]
          (is (= 0 exit))
          (is (= 1 (:namespace_count summary)))
          (is (not (str/includes? output "demo.optional")))))
      (testing "the aliased basis includes the namespace once its deps are selected"
        (let [{:keys [exit records output]} (check-selected-basis-project! dir {:alias [:malli]})
              summary (run-summary records)]
          (is (= 0 exit))
          (is (= 2 (:namespace_count summary)))
          (is (not (str/includes? output "ns-discovery-warning")))))
      (finally
        (delete-recursively! dir)))))
