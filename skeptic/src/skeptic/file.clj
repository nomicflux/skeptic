(ns skeptic.file
  (:require [clojure.java.io :as io])
  (:import [java.io File PushbackReader]
           [clojure.lang LispReader]))

(defn is-ns-block?
  [[field & _]]
  (= field 'ns))

(defn try-read
  [stream]
  (LispReader/read stream {:eof nil}))

(defn pushback-reader
  [^File file]
  (-> file io/reader (PushbackReader.)))

(defn ns-for-clojure-file
  [^File file]
  [(let [file-stream (pushback-reader file)]
     (loop [line (try-read file-stream)]
       (cond
         (nil? line) nil
         (is-ns-block? line) (->> line (drop 1) first)
         :else (recur (try-read file-stream)))))
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

;; https://stackoverflow.com/questions/45555191/is-there-a-way-to-get-clojure-files-source-when-a-namespace-provided
(defn source-clj
  [ns]
  (require ns)
  (some->> ns
           ns-publics
           vals
           first
           meta
           :file
           io/file))
