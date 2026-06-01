(ns p14-malli-mx
  (:require [malli.experimental :as mx]))

(mx/defn typed-id
  [x :- :int]
  x)
