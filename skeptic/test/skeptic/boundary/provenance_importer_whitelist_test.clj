(ns skeptic.boundary.provenance-importer-whitelist-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private allow-list
  #{"skeptic/typed_decls.clj"
    "skeptic/typed_decls/malli.clj"
    "skeptic/analysis/native_fns.clj"
    "skeptic/checking/pipeline.clj"})

(defn- clj-file?
  [^java.io.File f]
  (and (.isFile f) (.endsWith (.getName f) ".clj")))

(defn- rel-path
  [^java.io.File root ^java.io.File f]
  (str/replace-first (.getPath f) (str (.getPath root) "/") ""))

(defn- imports-provenance?
  [^java.io.File f]
  (boolean (re-find #"\[skeptic\.provenance\b" (slurp f))))

(defn- find-violators
  [^java.io.File root]
  (->> (file-seq root)
       (filter clj-file?)
       (filter imports-provenance?)
       (map #(rel-path root %))
       (remove allow-list)))

(deftest only-whitelisted-namespaces-import-skeptic-provenance
  (let [violators (find-violators (io/file "src"))]
    (is (empty? violators)
        (str "Files outside the allow-list require skeptic.provenance: "
             (pr-str violators)))))
