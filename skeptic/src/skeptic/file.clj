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

;; TODO: confirm working with CLJS & CLJC as well
(defn clojure-files-for-path
  [^String path]
  (->> (io/file path)
       file-seq
       (filter #(.isFile ^File %))
       (filter #(re-matches #".*\.clj" (.getName ^File %)))))

(defn relative-path
  [^File root ^String filename]
  (-> (.toURI root)
      (.relativize (.toURI (io/file filename)))
      (.getPath)))
