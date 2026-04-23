(ns skeptic.provenance)

(defrecord Provenance [source qualified-sym declared-in var-meta refs])

(def ^:private source-rank-map
  {:type-override 0
   :malli 1
   :schema 2
   :native 3
   :inferred 4})

(defn- valid-source?
  [source]
  (contains? source-rank-map source))

(defn- assert-source
  [source]
  (when-not (valid-source? source)
    (throw (IllegalArgumentException.
            (format "Invalid provenance source: %s" (pr-str source)))))
  source)

(defn make-provenance
  ([source qualified-sym declared-in var-meta]
   (make-provenance source qualified-sym declared-in var-meta []))
  ([source qualified-sym declared-in var-meta refs]
   (->Provenance (assert-source source) qualified-sym declared-in var-meta (vec refs))))

(defn with-refs
  "Return prov with :refs replaced by the given constituent provs."
  [prov refs]
  (assoc prov :refs (vec refs)))

(defn inferred
  [{:keys [name ns]}]
  (make-provenance :inferred name ns nil []))

(def ^:private ctx-key :skeptic.provenance/ctx-provenance)

(defn with-ctx
  "Read the current provenance from an analyzer ctx."
  [ctx]
  (or (get ctx ctx-key)
      (throw (IllegalArgumentException.
              (format "prov/with-ctx called with ctx missing provenance: %s" ctx)))))

(defn set-ctx
  "Return ctx with the given provenance installed."
  [ctx prov]
  (assoc ctx ctx-key prov))

(defn provenance?
  [x]
  (instance? Provenance x))

(defn source
  [{:keys [source]}]
  source)

(defn of
  [t]
  (or (:prov t)
      (throw (IllegalArgumentException.
              "prov/of called on value without provenance"))))

(defn- source-rank
  [p]
  (get source-rank-map (source p) 999))

(defn merge-provenances
  [p1 p2]
  (let [p1-val (if (sequential? p1) (first p1) p1)]
    (if (<= (source-rank p1-val) (source-rank p2))
      p1-val
      p2)))
