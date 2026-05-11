(ns skeptic.cli.main
  "deps.edn-side entrypoint for Skeptic. Invoked as:
    clojure -M:skeptic [flags]   (calls -main)
    clojure -X:skeptic           (calls run with EDN args)
  Library code lives elsewhere; this namespace only handles CLI parsing,
  source-path discovery, and the call into skeptic.core/check-project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]
            [skeptic.cli.cljs.shadow :as shadow]
            [skeptic.cli.options :as opts]
            [skeptic.cli.paths :as paths]
            [skeptic.core :as core]
            [skeptic.profiling :as profiling])
  (:import [java.io File]))

(def deps-cli-options
  [[nil "--paths PATHS"
    "Comma-separated source paths (overrides deps.edn discovery)"]
   [nil "--alias ALIAS"
    "deps.edn alias to merge for path discovery (repeatable; e.g. --alias :test)"
    :multi true
    :parse-fn (fn [s] (keyword (if (str/starts-with? s ":") (subs s 1) s)))
    :update-fn (fnil conj [])]])

(defn- split-paths-arg
  [s]
  (->> (str/split s #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- has-file?
  [root name]
  (.exists ^File (io/file root name)))

(defn- deps-source-paths
  [root aliases]
  (when (has-file? root "deps.edn")
    (paths/discover-paths root (or aliases []))))

(defn- shadow-source-paths
  [root]
  (when (has-file? root "shadow-cljs.edn")
    (:source-paths (shadow/discover-sources root))))

(defn- resolve-paths
  "Both deps.edn and shadow-cljs.edn may be present and contribute paths.
  --paths overrides everything; --alias only affects deps.edn discovery."
  [{:keys [paths alias]} root]
  (cond
    (string? paths)     (split-paths-arg paths)
    (sequential? paths) (vec paths)
    :else               (vec (distinct (concat (deps-source-paths root alias)
                                               (shadow-source-paths root))))))

(defn- run-checker
  [opts root paths]
  (s/without-fn-validation
   (profiling/run opts (str root "/target")
                  (fn [] (apply core/check-project opts root paths)))))

(defn- with-output-redirect
  [output-path f]
  (if-let [path output-path]
    (with-open [w (io/writer path)]
      (binding [*out* w] (f)))
    (f)))

(defn run-cli
  "Returns an exit code (0 clean, 1 inconsistencies / parse error / help)."
  [args]
  (let [{:keys [options summary errors]} (opts/parse args deps-cli-options)
        {:keys [help output]} options]
    (cond
      help   (do (println summary) 0)
      errors (do (doseq [e errors] (println e)) (println summary) 1)
      :else  (let [root  (System/getProperty "user.dir")
                   paths (resolve-paths options root)
                   result (atom 0)]
               (with-output-redirect output
                 #(reset! result (run-checker options root paths)))
               @result))))

(defn -main
  [& args]
  (System/exit (run-cli (vec args))))

(defn run
  "exec-fn entrypoint for `clojure -X:skeptic`. Opts is an EDN map."
  [opts]
  (let [root  (or (:root opts) (System/getProperty "user.dir"))
        paths (resolve-paths opts root)
        result (atom 0)]
    (with-output-redirect (:output opts)
      #(reset! result (run-checker opts root paths)))
    (System/exit @result)))
