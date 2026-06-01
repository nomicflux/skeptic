(ns skeptic.cli.cljs.shadow
  "Source discovery for cljs/cljc files in a shadow-cljs project. Reads
   shadow-cljs.edn at `root` as plain EDN and walks the top-level
   :source-paths key for cljs/cljc files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [schema.core :as s]
            [skeptic.cli.cljs.discover :as discover])
  (:import [java.io File PushbackReader]))

(defn- shadow-edn-file [root]
  (let [^File f (io/file root "shadow-cljs.edn")]
    (when-not (.exists f)
      (throw (ex-info (str "No shadow-cljs.edn found at " (.getAbsolutePath f))
                      {:root root})))
    f))

(defn- read-shadow-edn [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (edn/read r)))

(defn deps-aliases
  [root]
  (let [^File f (io/file root "shadow-cljs.edn")]
    (if-not (.exists f)
      []
      (let [aliases (get-in (read-shadow-edn f) [:deps :aliases])]
        (->> (cond
               (keyword? aliases) [aliases]
               (sequential? aliases) aliases
               :else [])
             (filter keyword?)
             distinct
             vec)))))

(defn- preload-symbols
  [x]
  (cond
    (map? x)
    (reduce-kv (fn [acc k v]
                 (into acc
                       (if (= :preloads k)
                         (filter symbol? v)
                         (preload-symbols v))))
               #{}
               x)

    (sequential? x)
    (into #{} (mapcat preload-symbols) x)

    :else
    #{}))

(defn preload-namespaces
  [root]
  (let [^File f (io/file root "shadow-cljs.edn")]
    (if-not (.exists f)
      #{}
      (preload-symbols (read-shadow-edn f)))))

(s/defn discover-sources :- discover/DiscoverySources
  [root]
  (let [config (read-shadow-edn (shadow-edn-file root))
        source-paths (mapv #(discover/absolutize root %) (:source-paths config))
        {:keys [cljs-files cljc-files]} (discover/discover-cljs-and-cljc source-paths)]
    {:source-paths source-paths
     :cljs-files cljs-files
     :cljc-files cljc-files}))
