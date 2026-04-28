(ns skeptic.core-fns)
(ns skeptic.file
  (:require [clojure.java.io :as io]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt])
  (:import [java.io File]))

(defn is-ns-block?
  [[field & _]]
  (= field 'ns))

(defn try-read
  [stream]
  (tr/read {:eof nil} stream))

(defn pushback-reader
  [^File file]
  (rt/source-logging-push-back-reader (io/reader file) 1 (.getPath file)))

(defn ns-for-clojure-file
  [^File file]
  [(with-open [reader (pushback-reader file)]
     (loop [line (try-read reader)]
       (cond
         (nil? line) nil
         (is-ns-block? line) (->> line (drop 1) first)
         :else (recur (try-read reader)))))
   file])

(defn- clojure-file?
  [^File file]
  (and (.isFile file)
       (re-matches #".*\.clj" (.getName file))))

(defn- discovery-result
  ([]
   (discovery-result [] [] #{}))
  ([files failures visited]
   {:files files
    :failures failures
    :visited visited}))

(defn- merge-discovery-results
  [left right]
  (discovery-result (into (:files left) (:files right))
                    (into (:failures left) (:failures right))
                    (:visited right)))

(defn- canonical-file
  [^File file]
  (.getCanonicalFile file))

(defn- discovery-failure
  [^File file exception]
  {:path (.getPath file)
   :exception exception})

(defn- list-directory
  [^File dir]
  (let [children (.listFiles dir)]
    (if (nil? children)
      (throw (ex-info (format "Could not list directory %s" (.getPath dir))
                      {:path (.getPath dir)}))
      children)))

(defn- discover-clojure-files*
  [^File file visited active]
  (try
    (let [canonical (canonical-file file)]
      (cond
        (clojure-file? canonical)
        (discovery-result [canonical] [] visited)

        (.isFile canonical)
        (discovery-result [] [] visited)

        (not (.exists canonical))
        (discovery-result [] [(discovery-failure file
                                                 (ex-info (format "Path does not exist: %s" (.getPath file))
                                                          {:path (.getPath file)}))]
                          visited)

        (not (.isDirectory canonical))
        (discovery-result [] [] visited)

        :else
        (let [canonical-path (.getPath canonical)]
          (cond
            (contains? visited canonical-path)
            (discovery-result [] [] visited)

            (contains? active canonical-path)
            (discovery-result [] [] visited)

            :else
            (let [children (list-directory canonical)
                  child-active (conj active canonical-path)
                  result (reduce (fn [acc child]
                                   (merge-discovery-results acc
                                                            (discover-clojure-files* child
                                                                                     (:visited acc)
                                                                                     child-active)))
                                 (discovery-result [] [] visited)
                                 children)]
              (assoc result :visited (conj (:visited result) canonical-path)))))))
    (catch Exception e
      (discovery-result [] [(discovery-failure file e)] visited))))

(defn discover-clojure-files
  [^String path]
  (let [{:keys [files failures]} (discover-clojure-files* (io/file path) #{} #{})
        files (->> files
                   (reduce (fn [acc ^File file]
                             (assoc acc (.getPath file) file))
                           {})
                   vals
                   vec)]
    {:files files
     :failures failures}))

;; TODO: confirm working with CLJS & CLJC as well
(defn clojure-files-for-path
  [^String path]
  (:files (discover-clojure-files path)))

(defn relative-path
  [^File root ^String filename]
  (-> (.toURI root)
      (.relativize (.toURI (io/file filename)))
      (.getPath)))
