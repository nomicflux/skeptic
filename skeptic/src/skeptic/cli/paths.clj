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
  (:require [clojure.java.io :as io]))

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

(defn classpath-entries
  [root aliases]
  (basis-classpath-entries root (create-basis root aliases)))

(defn discover-paths
  [root aliases]
  (let [basis (create-basis root aliases)]
    (->> (:classpath basis)
         (remove (fn [[_ entry]] (contains? entry :lib-name)))
         (mapv key))))
