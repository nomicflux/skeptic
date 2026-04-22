(ns skeptic.test-examples.named-fold
  (:require [schema.core :as s]))

(s/defschema ThreeColour
  (s/maybe [(s/recursive #'ThreeColour)
            (s/recursive #'ThreeColour)
            (s/recursive #'ThreeColour)]))

(s/defschema ThreeColourCache
  {:primary ThreeColour
   :fallback ThreeColour})

(s/defn named-fold-output-failure :- {:result ThreeColour :cache ThreeColourCache}
  []
  :not-a-map)
