(ns skeptic.provenance)

(defrecord Provenance [source qualified-sym declared-in var-meta])

(defn make-provenance
  [source qualified-sym declared-in var-meta]
  (->Provenance source qualified-sym declared-in var-meta))

(defn provenance?
  [x]
  (instance? Provenance x))

(def unknown
  (make-provenance :unknown nil nil nil))

(defn source
  [p]
  (if (provenance? p)
    (:source p)
    :unknown))

(def ^:private provenance-meta-key
  :skeptic.provenance/provenance)

(defn attach
  [t prov]
  (vary-meta t assoc provenance-meta-key prov))

(defn of
  [t]
  (some-> t meta (get provenance-meta-key)))

(def ^:private source-rank-map
  {:type-override 0
   :malli-spec 1
   :schema 2
   :native 3
   :inferred 5
   :unknown 6})

(defn- source-rank
  [p]
  (get source-rank-map (source p) 999))

(defn merge-provenances
  [p1 p2]
  (let [p1-val (if (sequential? p1) (first p1) p1)]
    (if (<= (source-rank p1-val) (source-rank p2))
      p1-val
      p2)))
