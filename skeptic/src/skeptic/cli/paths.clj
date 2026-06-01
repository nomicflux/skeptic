(ns skeptic.cli.paths
  "Source-path discovery for the deps.edn entrypoint. Reads the project's
  deps.edn through the official tools.deps API and returns the merged
  :paths vector for the given alias selection. The Leiningen plugin does
  not use this; it gets paths from the lein project map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.deps :as deps])
  (:import [java.net JarURLConnection]))

(def ^:private worker-extra-deps
  '{org.clojure/clojure            {:mvn/version "1.12.0"}
    org.clojure/clojurescript      {:mvn/version "1.11.132"}
    org.clojure/tools.analyzer     {:mvn/version "1.2.2"}
    org.clojure/tools.analyzer.jvm {:mvn/version "1.4.0-beta1"}
    com.taoensso/nippy             {:mvn/version "3.4.2"}
    nrepl/nrepl                    {:mvn/version "1.3.1"}})

(defn- deps-edn-file
  [root]
  (let [f (io/file root "deps.edn")]
    (when-not (.exists f)
      (throw (ex-info (str "No deps.edn found at " (.getAbsolutePath f))
                      {:root root})))
    f))

(defn create-basis
  ([root aliases]
   (create-basis root aliases nil))
  ([root aliases args]
   (let [f (deps-edn-file root)
         basis-args (cond-> {:project (.getAbsolutePath f)
                             :aliases (or aliases [])}
                      (seq args) (assoc :args args))]
     (deps/create-basis basis-args))))

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

(defn- parent-n
  [^java.io.File f n]
  (if (zero? n)
    f
    (recur (.getParentFile f) (dec n))))

(defn- file-resource-classpath-entry
  [resource url]
  (let [resource-file (io/file (.toURI url))
        segment-count (count (str/split resource #"/"))]
    (.getPath (parent-n resource-file segment-count))))

(defn- jar-resource-classpath-entry
  [url]
  (let [conn (.openConnection url)]
    (when-not (instance? JarURLConnection conn)
      (throw (ex-info (str "Unsupported jar resource connection for " url)
                      {:url (str url)})))
    (.getPath (io/file (.toURI (.getJarFileURL ^JarURLConnection conn))))))

(defn- resource-classpath-entry
  [resource]
  (let [url (or (io/resource resource)
                (throw (ex-info (str "Could not locate worker resource " resource)
                                {:resource resource})))]
    (case (.getProtocol url)
      "file" (file-resource-classpath-entry resource url)
      "jar"  (jar-resource-classpath-entry url)
      (throw (ex-info (str "Unsupported worker resource protocol "
                           (.getProtocol url))
                      {:resource resource
                       :url (str url)})))))

(defn worker-classpath-entries
  [root aliases]
  (vec
   (distinct
    (cons (resource-classpath-entry "skeptic/worker/server.clj")
          (basis-classpath-entries
           root
           (create-basis root aliases {:extra-deps worker-extra-deps}))))))

(defn discover-paths
  [root aliases]
  (let [basis (create-basis root aliases)]
    (->> (:classpath basis)
         (remove (fn [[_ entry]] (contains? entry :lib-name)))
         (mapv key))))
