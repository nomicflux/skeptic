(ns skeptic.local-resolution-import
  (:require [skeptic.test-examples.basics :refer [int-add]]))

(defn import-as-local
  [import]
  (int-add import nil))
