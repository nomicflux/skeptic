(ns skeptic.cli.main
  "Legacy deps.edn-side entrypoint for Skeptic.

  Hermetic Clojure CLI execution is exposed through `skeptic.tool/check`
  and `clj -T:skeptic check`. `clojure -M:skeptic` starts from the client
  project's runtime classpath, so it is intentionally unsupported."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [schema.core :as s]
            [skeptic.cli.cljs.shadow :as shadow]
            [skeptic.cli.paths :as paths]
            [skeptic.core :as core]
            [skeptic.profiling :as profiling]
            [skeptic.worker.classpath :as worker-classpath]
            [skeptic.worker.deps :as worker-deps])
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

(defn- basis-aliases
  "The single alias set the project resolves under: the user-selected
  --alias plus any deps.edn aliases the project's shadow-cljs.edn declares as
  its deps source (`{:deps {:aliases [...]}}`). Source-path discovery and the
  project portion of the worker classpath are resolved under this set, so
  Skeptic reads the project under the same basis the project compiles under."
  [root aliases]
  (vec (distinct (concat (or aliases []) (shadow/deps-aliases root)))))

(defn- root-relative-path
  [root path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      path
      (.getPath (io/file root path)))))

(defn- resolve-paths
  "Source paths come from the single deps.edn basis resolved under
  `basis-aliases`, the same basis the project portion of the worker classpath
  uses. --paths overrides discovery entirely."
  [{:keys [paths]} root basis-source-paths]
  (cond
    (string? paths)     (mapv (partial root-relative-path root)
                              (split-paths-arg paths))
    (sequential? paths) (mapv (partial root-relative-path root) paths)
    :else               (mapv (partial root-relative-path root)
                              basis-source-paths)))

(defn- paths-override?
  [opts]
  (contains? opts :paths))

(defn- with-selected-source-scope
  [opts project-context]
  (if (and project-context (not (paths-override? opts)))
    (assoc opts
           :skeptic/source-files (:source-files project-context)
           :skeptic/source-discovery-failures (:source-discovery-failures project-context))
    opts))

(defn- run-checker
  [opts root paths cp]
  (s/without-fn-validation
   (profiling/run opts (str root "/target")
                  (fn [] (apply core/check-project (assoc opts :worker-classpath cp) root paths)))))

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
        aliases (basis-aliases root (:alias opts))
        cljs-only-namespaces (shadow/preload-namespaces root)
        opts (cond-> opts
               (seq cljs-only-namespaces)
               (assoc :cljs-only-namespaces cljs-only-namespaces))
        project-context (when (has-file? root "deps.edn")
                          (paths/project-context root aliases))
        paths (resolve-paths opts root (:source-paths project-context))
        opts (with-selected-source-scope opts project-context)
        worker-jars (when project-context
                      (let [worker-basis (deps/create-basis
                                          {:project nil
                                           :aliases [:worker]
                                           :extra {:aliases {:worker {:replace-deps (worker-deps/worker-deps-as-mvn-map)}}}})]
                        ;; `:project nil` makes tools.deps inject a default "src" dir
                        ;; entry; the worker doesn't want a cwd-relative dir on its
                        ;; launch cp. Keep only .jar files.
                        (filterv #(str/ends-with? % ".jar") (:classpath-roots worker-basis))))
        cp (when project-context
             (worker-classpath/worker-classpath-entries
              worker-jars
              (:classpath-entries project-context)))
        result (atom 0)]
    (with-output-redirect (:output opts)
      #(reset! result (run-checker opts root paths cp)))
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
