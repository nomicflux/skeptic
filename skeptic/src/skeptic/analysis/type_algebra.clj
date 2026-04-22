(ns skeptic.analysis.type-algebra
  (:require [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn type-var-name
  [type]
  (when (at/type-var-type? type)
    (:name type)))

(defn type-free-vars
  [type]
  (let [type (ato/normalize type)]
    (cond
      (or (at/dyn-type? type)
          (at/bottom-type? type)
          (at/ground-type? type)
          (at/numeric-dyn-type? type)
          (at/refinement-type? type)
          (at/adapter-leaf-type? type)
          (at/optional-key-type? type)
          (at/placeholder-type? type)
          (at/inf-cycle-type? type))
      #{}

      (at/fn-method-type? type)
      (into (type-free-vars (:output type))
            (mapcat type-free-vars (:inputs type)))

      (at/fun-type? type)
      (into #{} (mapcat type-free-vars (:methods type)))

      (at/maybe-type? type)
      (type-free-vars (:inner type))

      (at/union-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/intersection-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/map-type? type)
      (reduce (fn [acc [k v]]
                (into acc (concat (type-free-vars k)
                                  (type-free-vars v))))
              #{}
              (:entries type))

      (at/vector-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (at/set-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/seq-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (at/var-type? type)
      (type-free-vars (:inner type))

      (at/value-type? type)
      (type-free-vars (:inner type))

      (at/type-var-type? type)
      #{(:name type)}

      (at/forall-type? type)
      (disj (type-free-vars (:body type)) (:binder type))

      (at/sealed-dyn-type? type)
      (type-free-vars (:ground type))

      :else
      #{})))

(defn type-substitute
  [type binder replacement]
  (let [type (ato/normalize type)
        replacement (ato/normalize replacement)
        prov (ato/derive-prov type replacement)]
    (cond
      (or (at/dyn-type? type)
          (at/bottom-type? type)
          (at/ground-type? type)
          (at/numeric-dyn-type? type)
          (at/refinement-type? type)
          (at/adapter-leaf-type? type)
          (at/placeholder-type? type)
          (at/inf-cycle-type? type))
      type

      (at/optional-key-type? type)
      (at/->OptionalKeyT prov (type-substitute (:inner type) binder replacement))

      (at/fn-method-type? type)
      (at/->FnMethodT prov
                      (mapv #(type-substitute % binder replacement) (:inputs type))
                      (type-substitute (:output type) binder replacement)
                      (:min-arity type)
                      (:variadic? type)
                      (:names type))

      (at/fun-type? type)
      (at/->FunT prov (mapv #(type-substitute % binder replacement) (:methods type)))

      (at/maybe-type? type)
      (at/->MaybeT prov (type-substitute (:inner type) binder replacement))

      (at/union-type? type)
      (at/->UnionT prov (set (map #(type-substitute % binder replacement) (:members type))))

      (at/intersection-type? type)
      (at/->IntersectionT prov (set (map #(type-substitute % binder replacement) (:members type))))

      (at/map-type? type)
      (at/->MapT prov
                 (into {}
                       (map (fn [[k v]]
                              [(type-substitute k binder replacement)
                               (type-substitute v binder replacement)]))
                       (:entries type)))

      (at/vector-type? type)
      (at/->VectorT prov
                    (mapv #(type-substitute % binder replacement) (:items type))
                    (:homogeneous? type))

      (at/set-type? type)
      (at/->SetT prov
                 (set (map #(type-substitute % binder replacement) (:members type)))
                 (:homogeneous? type))

      (at/seq-type? type)
      (at/->SeqT prov
                 (mapv #(type-substitute % binder replacement) (:items type))
                 (:homogeneous? type))

      (at/var-type? type)
      (at/->VarT prov (type-substitute (:inner type) binder replacement))

      (at/value-type? type)
      (at/->ValueT prov
                   (type-substitute (:inner type) binder replacement)
                   (:value type))

      (at/type-var-type? type)
      (if (= binder (:name type))
        replacement
        type)

      (at/forall-type? type)
      (if (= binder (:binder type))
        type
        (at/->ForallT prov
                      (:binder type)
                      (type-substitute (:body type) binder replacement)))

      (at/sealed-dyn-type? type)
      (at/->SealedDynT prov (type-substitute (:ground type) binder replacement))

      :else
      type)))
