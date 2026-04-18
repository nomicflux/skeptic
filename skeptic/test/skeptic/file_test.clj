(ns skeptic.file-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.file :as sut])
  (:import [java.io File]
           [java.nio.file FileSystemException Files Path]))

(defn- empty-file-attrs
  []
  (make-array java.nio.file.attribute.FileAttribute 0))

(defn- write-ns-file!
  [^Path path ns-sym]
  (spit (.toFile path) (format "(ns %s)\n" ns-sym)))

(deftest discover-clojure-files-terminates-on-canonical-revisits
  (try
    (let [root (Files/createTempDirectory "skeptic-file-test" (empty-file-attrs))
          real-dir (.resolve root "real")
          alias-dir (.resolve root "alias")
          loop-dir (.resolve real-dir "loop")
          source-file (.resolve real-dir "alpha.clj")]
      (Files/createDirectories real-dir (empty-file-attrs))
      (write-ns-file! source-file 'example.alpha)
      (Files/createSymbolicLink alias-dir real-dir (empty-file-attrs))
      (Files/createSymbolicLink loop-dir real-dir (empty-file-attrs))
      (let [{:keys [files failures]} (sut/discover-clojure-files (.getPath ^File (.toFile root)))]
        (is (empty? failures))
        (is (= 1 (count files)))
        (is (= ["alpha.clj"]
               (mapv #(.getName ^File %) files)))))
    (catch UnsupportedOperationException _e
      (is true))
    (catch FileSystemException e
      (if (or (str/includes? (.getMessage e) "Operation not permitted")
              (str/includes? (.getMessage e) "not supported"))
        (is true)
        (throw e)))))
