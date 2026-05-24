(ns skeptic.cli.main
  "Legacy deps.edn-side entrypoint for Skeptic.

  Hermetic Clojure CLI execution is exposed through `skeptic.tool/check`
  and `clj -T:skeptic check`. `clojure -M:skeptic` starts from the client
  project's runtime classpath, so it is intentionally unsupported."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]
            [skeptic.cli.cljs.shadow :as shadow]
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

(defn- sequentialize
  [x]
  (cond
    (nil? x) nil
    (sequential? x) (vec x)
    :else [x]))

(defn- normalize-alias
  [alias]
  (cond
    (keyword? alias) alias
    (string? alias)  (keyword (if (str/starts-with? alias ":")
                                (subs alias 1)
                                alias))
    (symbol? alias)  (keyword (name alias))
    :else alias))

(defn- normalize-tool-opts
  [opts]
  (let [normalize-string #(cond
                            (string? %) %
                            (symbol? %) (str %)
                            (keyword? %) (name %)
                            :else %)]
    (cond-> opts
      (contains? opts :alias)
      (update :alias #(mapv normalize-alias (sequentialize %)))

      (contains? opts :namespace)
      (update :namespace #(mapv normalize-string (sequentialize %))))))

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

(defn- basis-aliases
  [root aliases]
  (vec (distinct (concat (or aliases []) (shadow/deps-aliases root)))))

(defn- root-relative-path
  [root path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      path
      (.getPath (io/file root path)))))

(defn- resolve-paths
  "Both deps.edn and shadow-cljs.edn may be present and contribute paths.
  --paths overrides everything; --alias only affects deps.edn discovery."
  [{:keys [paths alias]} root]
  (cond
    (string? paths)     (mapv (partial root-relative-path root)
                              (split-paths-arg paths))
    (sequential? paths) (mapv (partial root-relative-path root) paths)
    :else               (vec (distinct (concat (deps-source-paths root (basis-aliases root alias))
                                               (map (partial root-relative-path root)
                                                    (shadow-source-paths root)))))))

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

(defn check-project
  "Run Skeptic from an already-hermetic tool/plugin runtime.

  `opts` is the arg map shape accepted by `clj -T:skeptic check`; `:project-dir`
  selects the client project to analyze. The client project's deps.edn basis is
  used only to discover input paths and is not added to this JVM's classpath."
  [opts]
  (let [opts (normalize-tool-opts (or opts {}))
        root-file (.getCanonicalFile
                   ^File (io/file (or (:project-dir opts)
                                      (:root opts)
                                      (System/getProperty "user.dir"))))
        root (.getPath root-file)
        opts (dissoc opts :project-dir :root)
        paths (resolve-paths opts root)
        result (atom 0)]
    (with-output-redirect (:output opts)
      #(reset! result (run-checker opts root paths)))
    @result))

(defn run-cli
  "Legacy -M entrypoint. Always returns 1 because -M is not hermetic."
  [_args]
  (binding [*out* *err*]
    (println "clojure -M:skeptic is unsupported for hermetic deps.edn execution.")
    (println "Add a deps.edn tool alias for org.clojars.nomicflux/skeptic and run:")
    (println "  clj -T:skeptic check"))
  1)

(defn -main
  [& args]
  (System/exit (run-cli (vec args))))

(defn run
  "Legacy exec-fn entrypoint. `-X` is not hermetic; use `clj -T:skeptic check`."
  [_opts]
  (System/exit (run-cli [])))
