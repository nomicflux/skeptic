(ns skeptic.analysis.conditional-arms
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]))

(defn- exact-key-query'
  [prov k]
  ((requiring-resolve 'skeptic.analysis.map-ops/exact-key-query) prov k))

(defn- refine-by-predicate'
  [t path pred-info polarity]
  ((requiring-resolve 'skeptic.analysis.map-ops/refine-map-path-by-predicate)
   t path pred-info polarity))

(defn- refine-by-values'
  [t path values polarity]
  ((requiring-resolve 'skeptic.analysis.map-ops/refine-map-path-by-values)
   t path values polarity))

(defn- query-path
  "Descriptor paths are raw keywords; map-ops walkers expect
   `exact-key-query` records. Wrap each element using the arm-type's prov."
  [arm-type path]
  (let [p (prov/of arm-type)]
    (mapv #(exact-key-query' p %) path)))

(defn- refine-by-descriptor
  [arm-type descriptor]
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
  [cond-type :- ats/SemanticType]
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

(s/defn effective-conditional-arms :- [ats/SemanticType]
  "Structural type of each surviving arm post-dispatch refinement.
   See `effective-conditional-branches`."
  [cond-type :- ats/SemanticType]
  (mapv second (effective-conditional-branches cond-type)))
