(ns skeptic.analysis.conditional-arms
  (:require [schema.core :as s]
            [skeptic.analysis.map-ops.schema :as amos]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(s/defn ^:private exact-key-query' :- amos/ExactKeyQuery
  [prov :- provs/Provenance
   k    :- s/Any]
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

(s/defn refine-by-descriptor :- at/SemanticType
  [arm-type   :- at/SemanticType
   descriptor :- (s/maybe {s/Keyword s/Any})]
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

(s/defn effective-conditional-branches :- at/ConditionalBranches
  "Return live branches whose effective type is narrowed by the negation
   of all earlier arms' recognized descriptors. Branches whose effective
   type is BottomType are dropped (unreachable). Unrecognized earlier
   descriptors are skipped."
  [cond-type :- at/SemanticType]
  (let [branches (vec (:branches cond-type))]
    (loop [k 0
           earlier-descriptors []
           acc []]
      (if (>= k (count branches))
        acc
        (let [b (nth branches k)
              eff (reduce refine-by-descriptor (:type b) earlier-descriptors)]
          (recur (inc k)
                 (conj earlier-descriptors (:descriptor b))
                 (if (at/bottom-type? eff)
                   acc
                   (conj acc (assoc b :type eff)))))))))

(s/defn effective-conditional-arms :- [at/SemanticType]
  "Structural type of each surviving arm post-dispatch refinement.
   See `effective-conditional-branches`."
  [cond-type :- at/SemanticType]
  (mapv :type (effective-conditional-branches cond-type)))

(defn unwrap-exact-path
  "Convert path (vector of `exact-key-query` records) to the raw key vector
   that descriptors store. Returns nil if any element is not an exact query."
  [path]
  (when (every? exact-key-query?' path)
    (mapv :value path)))

(s/defn dispatch-incompatible-with-predicate? :- s/Bool
  "True when arm K's runtime dispatch is ruled out by user assertion
   `(pred-info, raw-path, polarity)` under s/conditional first-match semantics.

   Path-type-predicate descriptors are positive (the arm's predicate must be
   true for the arm to fire). The dispatch-order rule says:

   - K's own descriptor matches (pred, path): K can dispatch only when the
     user's polarity is true. polarity=false drops K.
   - Any j < K with a matching descriptor: K can dispatch only when j's
     predicate is false on the value, i.e., polarity=false. polarity=true
     forces j to dispatch instead, dropping K."
  [branches  :- [{s/Any s/Any}]
   k         :- s/Int
   pred-info :- {s/Keyword s/Any}
   raw-path  :- [s/Any]
   polarity  :- s/Bool]
  (let [matches? (fn [d]
                   (and d
                        (= :path-type-predicate (:kind d))
                        (= raw-path (:path d))
                        (= (:pred pred-info) (:pred d))
                        (= (some-> (:class pred-info) at/ground-class) (some-> (:class d) at/ground-class))))
        own-conflict? (and (matches? (:descriptor (nth branches k)))
                           (not polarity))
        earlier-forces? (and polarity
                             (boolean
                              (some #(matches? (:descriptor (nth branches %)))
                                    (range k))))]
    (or own-conflict? earlier-forces?)))

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
      (if (matches? (:descriptor (nth branches k)))
        k
        (recur (inc k))))))

(defn- effective-arm-at
  [cond-type idx]
  (let [branches (vec (:branches cond-type))
        earlier-descs (mapv #(:descriptor (nth branches %)) (range idx))]
    (reduce refine-by-descriptor (:type (nth branches idx)) earlier-descs)))

(s/defn ^:private rebuild-without-arm :- at/SemanticType
  [cond-type :- at/SemanticType
   idx       :- s/Int]
  (let [branches (vec (:branches cond-type))
        kept (vec (concat (subvec branches 0 idx)
                          (subvec branches (inc idx))))
        prov (prov/of cond-type)]
    (cond
      (empty? kept)      (at/BottomType prov)
      (= 1 (count kept)) (:type (first kept))
      :else              (at/->ConditionalT prov kept))))

(s/defn route-conditional-by-values :- (s/maybe at/SemanticType)
  "Fast path for value-descriptor (enumerated tagged-dispatch) narrowing:
   if an arm's values-descriptor exactly matches `(path, values)`, return
   that arm's effective type (polarity=true) or the conditional minus that
   arm (polarity=false). Returns nil when no descriptor matches so callers
   fall back to structural refinement.

   Sound because values-descriptors define mutually exclusive enumerated
   sets at a single path; cf. the dropped predicate-descriptor variant,
   which assumed mutual exclusivity it could not guarantee."
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
