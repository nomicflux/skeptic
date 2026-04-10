(ns skeptic.analysis.cast.boundary-test
  "Architecture regression: verifies that no non-cast namespace crosses the cast app boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- src-files
  "All .clj source files under src/."
  []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName %) ".clj"))))

(defn- cast-namespace?
  [f]
  (str/includes? (.getPath f) "/cast/"))

(defn- non-cast-src-files
  []
  (remove cast-namespace? (src-files)))

(deftest no-children-access-on-cast-data-outside-cast-subtree
  (doseq [f (non-cast-src-files)]
    (let [text (slurp f)]
      (is (not (re-find #":children\s+cast" text))
          (str (.getPath f) " accesses :children on cast data outside cast subtree")))))

(deftest no-direct-cast-support-require-outside-cast-subtree
  (doseq [f (non-cast-src-files)]
    (let [text (slurp f)]
      (is (or (not (re-find #"analysis\.cast\.support" text))
              (re-find #"optional-key-inner|with-cast-path" text))
          (str (.getPath f) " requires cast.support outside approved helpers")))))
