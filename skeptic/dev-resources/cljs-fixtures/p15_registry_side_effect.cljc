(ns p15-registry-side-effect
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(def registry
  {:string (m/-string-schema)})

#?(:clj (mr/set-default-registry! registry))

(defn marker [] :ok)
