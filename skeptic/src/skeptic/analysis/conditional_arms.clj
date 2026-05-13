(ns skeptic.analysis.conditional-arms
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- exact-key-query'
  [prov k]
  ((requiring-resolve 'skeptic.analysis.map-ops/exact-key-query) prov k))

(defn- exact-key-query?'
  [q]
  ((requiring-resolve 'skeptic.analysis.map-ops/exact-key-query?) q))

(defn- refine-by-predicate'
  [t path pred-info polarity]
  ((requiring-resolve 'skeptic.analysis.map-ops/refine-map-path-by-predicate)
   t path pred-info polarity))

(defn- refine-by-values'
  [t path values polarity]
  ((requiring-resolve 'skeptic.analysis.map-ops/refine-map-path-by-values)
   t path values polarity))

(s/defn ^:private query-path :- [{s/Keyword s/Any}]
  "Descriptor paths are raw keywords; map-ops walkers expect
   `exact-key-query` records. Wrap each element using the arm-type's prov."
  [arm-type :- at/SemanticType
   path     :- [s/Any]]
  (let [p (prov/of arm-type)]
    (mapv #(exact-key-query' p %) path)))

(s/defn ^:private refine-by-descriptor :- at/SemanticType
  "ConditionalT slot3's lifecycle: bridge admission emits the raw predicate
   form for Plumatic conditional (a symbol like 'integer? or an inline-fn
   form), which `enrich-conditional-descriptors` replaces post-admission with
   a descriptor map (or nil) computed via accessor-summaries. Production runs
   accessor-summary collection — which analyzes user code — against the
   unenriched dict (chicken-and-egg: enrichment requires accessor-summaries),
   so this function sees the full lifecycle range: nil, descriptor map, raw
   pred symbol, fn-form vector, runtime fn. Only descriptor maps drive
   narrowing; everything else falls through to `arm-type` unchanged. The
   `descriptor` schema is `s/Any` because that's the honest contract."
  [arm-type   :- at/SemanticType
   descriptor :- s/Any]
  (cond
    (nil? descriptor) arm-type

    (= :path-type-predicate (:kind descriptor))
    (refine-by-predicate' arm-type
                          (query-path arm-type (:path descriptor))
                          (cond-> {:pred (:pred descriptor)}
                            (:class descriptor) (assoc :class (:class descriptor)))
                          false)

    (and (:path descriptor) (:values descriptor))
    (refine-by-values' arm-type
                       (query-path arm-type (:path descriptor))
                       (:values descriptor)
                       false)

    :else arm-type))

(s/defn effective-conditional-branches :- [s/Any]
  "Return live arm triples `[pred eff-type slot3]`, where each arm's
   structural type is narrowed by the negation of all earlier arms'
   recognized descriptors. Arms whose effective type is BottomType are
   dropped (unreachable). Unrecognized earlier descriptors are skipped."
  [cond-type :- at/SemanticType]
  (let [branches (vec (:branches cond-type))]
    (loop [k 0
           earlier-descriptors []
           acc []]
      (if (>= k (count branches))
        acc
        (let [[pred typ slot3] (nth branches k)
              eff (reduce refine-by-descriptor typ earlier-descriptors)]
          (recur (inc k)
                 (conj earlier-descriptors slot3)
                 (if (at/bottom-type? eff)
                   acc
                   (conj acc [pred eff slot3]))))))))

(s/defn effective-conditional-arms :- [at/SemanticType]
  "Structural type of each surviving arm post-dispatch refinement.
   See `effective-conditional-branches`."
  [cond-type :- at/SemanticType]
  (mapv second (effective-conditional-branches cond-type)))

(defn- unwrap-exact-path
  "Convert path (vector of `exact-key-query` records) to the raw key vector
   that descriptors store. Returns nil if any element is not an exact query
   (so routing can defer to the structural fallback)."
  [path]
  (when (every? exact-key-query?' path)
    (mapv :value path)))

(defn- pred-descriptor-matches?
  [desc raw-path pred-info]
  (and desc
       (= :path-type-predicate (:kind desc))
       (= raw-path (:path desc))
       (= (:pred pred-info) (:pred desc))
       (= (:class pred-info) (:class desc))))

(defn- values-descriptor-matches?
  [desc raw-path values]
  (and desc
       (not= :path-type-predicate (:kind desc))
       (:path desc)
       (:values desc)
       (= raw-path (:path desc))
       (= (set values) (set (:values desc)))))

(defn- match-arm-index
  [branches matches?]
  (loop [k 0]
    (when (< k (count branches))
      (let [[_pred _typ slot3] (nth branches k)]
        (if (matches? slot3)
          k
          (recur (inc k)))))))

(defn- effective-arm-at
  [cond-type idx]
  (let [branches (vec (:branches cond-type))
        earlier-descs (mapv #(nth (nth branches %) 2) (range idx))
        [_pred typ _slot3] (nth branches idx)]
    (reduce refine-by-descriptor typ earlier-descs)))

(s/defn ^:private rebuild-without-arm :- at/SemanticType
  [cond-type :- at/SemanticType
   idx       :- s/Int]
  (let [branches (vec (:branches cond-type))
        kept (vec (concat (subvec branches 0 idx)
                          (subvec branches (inc idx))))
        prov (prov/of cond-type)]
    (cond
      (empty? kept)      (at/BottomType prov)
      (= 1 (count kept)) (second (first kept))
      :else              (at/->ConditionalT prov kept))))

(s/defn route-conditional-by-predicate :- (s/maybe at/SemanticType)
  "If any arm's descriptor matches `(path, pred-info)`, return that arm's
   effective type (polarity=true) or the ConditionalT minus that arm
   (polarity=false). Else nil so callers can fall back to a structural walk."
  [cond-type :- at/SemanticType
   path      :- [s/Any]
   pred-info :- {s/Keyword s/Any}
   polarity  :- s/Bool]
  (when-let [raw-path (unwrap-exact-path path)]
    (when-let [idx (match-arm-index
                    (:branches cond-type)
                    #(pred-descriptor-matches? % raw-path pred-info))]
      (if polarity
        (effective-arm-at cond-type idx)
        (rebuild-without-arm cond-type idx)))))

(s/defn route-conditional-by-values :- (s/maybe at/SemanticType)
  "Like `route-conditional-by-predicate`, but for descriptors of `:values`
   shape (e.g. discriminator-keyword case)."
  [cond-type :- at/SemanticType
   path      :- [s/Any]
   values    :- [s/Any]
   polarity  :- s/Bool]
  (when-let [raw-path (unwrap-exact-path path)]
    (when-let [idx (match-arm-index
                    (:branches cond-type)
                    #(values-descriptor-matches? % raw-path values))]
      (if polarity
        (effective-arm-at cond-type idx)
        (rebuild-without-arm cond-type idx)))))
