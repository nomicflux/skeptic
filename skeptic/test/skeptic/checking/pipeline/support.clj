(ns skeptic.checking.pipeline.support
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.best-effort-examples]
            [skeptic.checking.pipeline :as sut]
            [skeptic.examples]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect]
            [skeptic.static-call-examples]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-support.shared-worker :as shared-worker])
  (:import [java.io File]))

(defn with-worker
  "`:once` fixture for pipeline tests that build project-state and exercise
   `check-ns` against the real worker. Spawns one worker on the host classpath,
   interns the bootstrap host classes, and runs the namespace's tests with
   `class-oracle/*worker-conn*` and `*host-class-handles*` bound. Test runtime =
   production runtime: a real worker JVM, no host-local escape hatch."
  [f]
  (shared-worker/with-shared-worker f))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(def examples-file
  (File. "src/skeptic/examples.clj"))

(def schema-collect-file
  (File. "src/skeptic/schema/collect.clj"))

(def static-call-examples-file
  (File. "src/skeptic/static_call_examples.clj"))

(def best-effort-file
  (File. "test/skeptic/best_effort_examples.clj"))

(defn fixture-env-by-ns
  [ns-sym]
  (or (some (fn [[_ env]]
              (when (= ns-sym (:ns env))
                env))
            catalog/fixture-envs)
      (throw (ex-info "Unknown fixture namespace"
                      {:namespace ns-sym}))))

(defn fixture-file-for-ns
  ^File [ns-sym]
  (:file (fixture-env-by-ns ns-sym)))

(defn fixture-path-for-ns
  [ns-sym]
  (.getPath (fixture-file-for-ns ns-sym)))

(defn fixture-env
  [sym]
  (or (catalog/owner-of sym)
      (throw (ex-info "Unknown fixture symbol"
                      {:symbol sym}))))

(defn fixture-file
  ^File [sym]
  (:file (fixture-env sym)))

(defn fixture-path
  [sym]
  (.getPath (fixture-file sym)))

(defn fixture-ns
  [sym]
  (:ns (fixture-env sym)))

(defn- worker-opts
  []
  {:worker-conn class-oracle/*worker-conn*})

(def ^:private fixture-project-state
  (delay
    (sut/project-state
     (worker-opts)
     (into [] (map (juxt :ns :file)) (vals catalog/fixture-envs)))))

(defn- assert-no-per-ns-failures!
  "Pipeline phases (cljs-load, clj-load, admission, accessors, load) catch per-
   file/per-ns Throwables and record them on the project state's `per-ns-failures`
   map so one bad file does not abort the run. Production surfaces those as
   findings; tests do not, so a swallow here would hide a worker exception
   behind a silent `(not (contains? ...))` later. Throw at the helper boundary
   with EVERY exception's class+message, so every test built on top is loud by
   default."
  [project-state]
  (let [failures (:per-ns-failures project-state)]
    (when (seq failures)
      (throw (ex-info (str "project-state has " (count failures) " per-ns failure(s): "
                           (str/join " | "
                                     (map (fn [[k {:keys [^Throwable exception phase]}]]
                                            (str (pr-str k) " [" phase "] "
                                                 (.getName (class exception)) ": "
                                                 (.getMessage exception)))
                                          failures)))
                      {:per-ns-failures failures})))))

(defn project-state-for
  [ns-sym source-file]
  (doto (sut/project-state (worker-opts) [[ns-sym source-file]])
    assert-no-per-ns-failures!))

(defn project-state-for-nses
  [ns->file]
  (doto (sut/project-state (worker-opts) (vec ns->file))
    assert-no-per-ns-failures!))

(defn project-state-allowing-failures
  "Variant for tests that INTENTIONALLY produce a per-ns-failure (e.g. tests
   asserting the pipeline localizes reader errors as `:read`-phase findings).
   Bypasses `assert-no-per-ns-failures!`. Do not use as a general escape hatch
   for unexpected failures — those should remain loud through `project-state-for`."
  [ns-sym source-file]
  (sut/project-state (worker-opts) [[ns-sym source-file]]))

(def ^:private check-ns-cache
  "Per-(ns-sym, opts) cache of `check-ns` results against the shared
   `fixture-project-state`. `check-fixture` only filters by `:enclosing-form`
   after the fact, so dozens of distinct symbols inside one fixture namespace
   share one underlying `check-ns` run."
  (atom {}))

(defn- cached-check-ns-results
  [ns-sym file opts]
  (let [k [ns-sym opts]]
    (or (get @check-ns-cache k)
        (let [results (:results (sut/check-ns @fixture-project-state ns-sym file opts))]
          (swap! check-ns-cache assoc k results)
          results))))

(defn check-fixture
  ([sym]
   (check-fixture sym {}))
  ([sym opts]
   (filterv #(= sym (:enclosing-form %))
            (cached-check-ns-results (fixture-ns sym) (fixture-file sym) opts))))

(defn check-fixture-ns
  [ns-sym opts]
  (:results (sut/check-ns @fixture-project-state
                          ns-sym
                          (fixture-file-for-ns ns-sym)
                          opts)))

(defn check-fixture-namespace
  [ns-sym opts]
  (:results (sut/check-namespace @fixture-project-state
                                 ns-sym
                                 (fixture-file-for-ns ns-sym)
                                 opts)))

(defn run-with-timeout
  [timeout-ms f]
  (let [fut (future (f))
        result (deref fut timeout-ms ::timeout)]
    (future-cancel fut)
    result))

(defn result-errors
  [results]
  (mapcat (juxt :blame :errors) results))

(defn result-pairs
  [results]
  (set (map (juxt :blame :errors) results)))

(def ui-internal-markers
  [":skeptic.analysis.types/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(defn strip-ansi
  [text]
  (str/replace (str text) #"\u001B\[[0-9;]*m" ""))

(defn T
  [schema]
  (ab/schema->type tp schema))

(defn single-failure?
  [sym blame]
  (let [results (vec (check-fixture sym))
        result (first results)]
    (cond
      (not= 1 (count results)) (do (println (format "%d results returned" (count results)))
                                   false)
      (not= blame (:blame result)) (do (println (format "Actual blame \"%s\" does not match expected blame \"%s\""
                                                        (:blame result)
                                                        blame))
                                       false)
      (empty? (:errors result)) (do (println "No errors returned in result")
                                    false)
      :else true)))
