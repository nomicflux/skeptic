(ns skeptic.local-resolution-import
  (:require [skeptic.test-examples :as te]))

(defn import-as-local
  [import]
  (te/int-add import nil))
