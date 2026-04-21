(ns skeptic.provenance)

(defrecord Provenance [source qualified-sym declared-in var-meta])

(defn provenance?
  [x]
  (instance? Provenance x))

(def ^:private source-rank-map
  {:type-override 0 :malli-spec 1 :schema 2 :native 3})

(defn- source-rank
  [p]
  (get source-rank-map (:source p) 999))

(defn merge-provenances
  [p1 p2]
  (let [p1-val (if (sequential? p1) (first p1) p1)]
    (if (<= (source-rank p1-val) (source-rank p2))
      p1-val
      p2)))
