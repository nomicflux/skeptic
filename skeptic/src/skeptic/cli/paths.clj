(ns skeptic.cli.paths
  "Source-path discovery for the deps.edn entrypoint. Reads the project's
  deps.edn through the official tools.deps API and returns the merged
  :paths vector for the given alias selection. The Leiningen plugin does
  not use this; it gets paths from the lein project map."
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
        create-basis-fn (requiring-resolve 'clojure.tools.deps/create-basis)
        basis (create-basis-fn {:project (.getAbsolutePath f)
                                :aliases (or aliases [])})]
    basis))

(defn classpath-entries
  [root aliases]
  (keys (:classpath (create-basis root aliases))))

(defn discover-paths
  [root aliases]
  (let [basis (create-basis root aliases)]
    (->> (:classpath basis)
         (remove (fn [[_ entry]] (contains? entry :lib-name)))
         (mapv key))))
