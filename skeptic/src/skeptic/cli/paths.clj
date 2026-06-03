(ns skeptic.cli.paths
  "Source-path discovery for the deps.edn entrypoint. Reads the project's
  deps.edn through the official tools.deps API and returns the merged
  :paths vector for the given alias selection. The Leiningen plugin does
  not use this; it gets paths from the lein project map.

  `clojure.tools.deps` is resolved lazily inside `create-basis` rather than
  required at namespace load. That keeps its heavyweight transitive graph
  (maven-resolver, maven-core, cognitect-aws, jetty) off any classpath that
  only loads this namespace without calling it -- specifically the Leiningen
  plugin classloader, which loads this ns transitively (via skeptic.cli.main)
  during self-analysis but never invokes deps.edn path discovery."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skeptic.file :as file])
  (:import [java.net URL URLClassLoader]))

(defn- deps-edn-file
  [root]
  (let [f (io/file root "deps.edn")]
    (when-not (.exists f)
      (throw (ex-info (str "No deps.edn found at " (.getAbsolutePath f))
                      {:root root})))
    f))

(defn create-basis
  [root aliases]
  (let [f (deps-edn-file root)
        create (requiring-resolve 'clojure.tools.deps/create-basis)]
    (create {:project (.getAbsolutePath f)
             :aliases (or aliases [])})))

(defn- root-absolute-path
  [root path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      path
      (.getPath (io/file root path)))))

(defn- basis-classpath-entries
  [root basis]
  (mapv (partial root-absolute-path root)
        (keys (:classpath basis))))

(defn source-paths-from-basis
  [basis]
  (->> (:classpath basis)
       (remove (fn [[_ entry]] (contains? entry :lib-name)))
       (mapv key)))

(def ^:private dependency-clause-heads
  #{:require :require-macros :use :use-macros})

(def ^:private source-resource-extensions
  [".clj" ".cljc" ".cljs"])

(defn- distinct-files
  [files]
  (->> files
       (reduce (fn [acc f]
                 (assoc acc (.getCanonicalPath f) f))
               {})
       vals
       vec))

(defn- discover-source-files
  [root source-paths]
  (reduce (fn [{:keys [files failures]} path]
            (let [resolved (io/file (root-absolute-path root path))]
              (if-not (.exists resolved)
                {:files files :failures failures}
                (let [{new-files :files
                       new-failures :failures}
                      (file/discover-clojure-files (.getPath resolved))]
                  {:files (into files new-files)
                   :failures (into failures new-failures)}))))
          {:files []
           :failures []}
          source-paths))

(defn- ns-form-for-source-file
  [source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (loop [form (file/try-read reader)]
      (cond
        (nil? form) nil
        (file/is-ns-block? form) form
        :else (recur (file/try-read reader))))))

(defn- prefixed-symbol
  [prefix suffix]
  (symbol (str prefix "." suffix)))

(defn- prefixed-libspec-symbol
  [prefix spec]
  (cond
    (symbol? spec) [(prefixed-symbol prefix spec)]
    (and (vector? spec) (symbol? (first spec))) [(prefixed-symbol prefix (first spec))]
    :else []))

(defn- libspec-symbols
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (vector? spec) (symbol? (first spec))) [(first spec)]
    (and (seq? spec) (symbol? (first spec))) (mapcat (partial prefixed-libspec-symbol
                                                               (first spec))
                                                     (rest spec))
    :else []))

(defn- ns-dependency-symbols
  [ns-form]
  (->> (drop 2 ns-form)
       (filter #(and (seq? %) (contains? dependency-clause-heads (first %))))
       (mapcat rest)
       (mapcat libspec-symbols)
       distinct
       vec))

(defn- source-entry
  [source-file]
  (try
    (when-let [ns-form (ns-form-for-source-file source-file)]
      {:ns (second ns-form)
       :source-file source-file
       :dependencies (ns-dependency-symbols ns-form)})
    (catch Exception e
      {:source-file source-file
       :read-error e})))

(defn- ns-resource-prefix
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- ns-resource-candidates
  [ns-sym]
  (let [prefix (ns-resource-prefix ns-sym)]
    (mapv #(str prefix %) source-resource-extensions)))

(defn- url-for-classpath-entry
  ^URL [entry]
  (-> (io/file entry) .toURI .toURL))

(defn- classpath-loader
  ^URLClassLoader [classpath-entries]
  (URLClassLoader. (into-array URL (map url-for-classpath-entry classpath-entries))
                   nil))

(defn- classpath-has-ns?
  [^URLClassLoader loader ns-sym]
  (boolean (some #(.findResource loader %) (ns-resource-candidates ns-sym))))

(defn- dependency-resolvable?
  [project-nss loader dep]
  (or (contains? project-nss dep)
      (classpath-has-ns? loader dep)))

(defn- source-entry-selected?
  [project-nss loader {:keys [ns dependencies read-error]}]
  (or read-error
      (nil? ns)
      (every? (partial dependency-resolvable? project-nss loader)
              dependencies)))

(defn- selected-source-scope
  [root basis classpath-entries]
  (let [source-paths (source-paths-from-basis basis)
        {:keys [files failures]} (discover-source-files root source-paths)
        files (distinct-files files)
        entries (keep source-entry files)
        project-nss (set (keep :ns entries))]
    (with-open [loader (classpath-loader classpath-entries)]
      {:source-files (->> entries
                          (filter (partial source-entry-selected? project-nss loader))
                          (mapv :source-file)
                          distinct-files)
       :source-discovery-failures failures})))

(defn project-context
  "Return the source paths and project classpath derived from one tools.deps
   basis for the selected aliases."
  [root aliases]
  (let [basis (create-basis root aliases)
        classpath-entries (basis-classpath-entries root basis)
        source-scope (selected-source-scope root basis classpath-entries)]
    {:basis basis
     :source-paths (source-paths-from-basis basis)
     :source-files (:source-files source-scope)
     :source-discovery-failures (:source-discovery-failures source-scope)
     :classpath-entries classpath-entries}))

(defn classpath-entries
  [root aliases]
  (basis-classpath-entries root (create-basis root aliases)))

(defn discover-paths
  [root aliases]
  (source-paths-from-basis (create-basis root aliases)))
