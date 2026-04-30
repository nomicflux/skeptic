(ns skeptic.analysis.type-algebra
  (:require [schema.core :as s]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]))

(s/defn type-var-name :- (s/maybe s/Symbol)
  [type :- s/Any]
  (when (at/type-var-type? type)
    (:name type)))

(s/defn type-free-vars :- #{s/Symbol}
  [type :- ats/SemanticType]
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

      (at/conditional-type? type)
      (into #{} (mapcat type-free-vars (map second (:branches type))))

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

(s/defn type-substitute :- ats/SemanticType
  [type        :- ats/SemanticType
   binder      :- s/Any
   replacement :- ats/SemanticType]
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
      (let [members' (set (map #(type-substitute % binder replacement) (:members type)))]
        (at/->UnionT (prov/with-refs prov (mapv prov/of members'))
                     members'))

      (at/intersection-type? type)
      (at/->IntersectionT prov (set (map #(type-substitute % binder replacement) (:members type))))

      (at/conditional-type? type)
      (at/->ConditionalT prov
                         (mapv (fn [[pred branch-t pred-form]]
                                 [pred (type-substitute branch-t binder replacement) pred-form])
                               (:branches type)))

      (at/map-type? type)
      (let [entries' (into {}
                           (map (fn [[k v]]
                                  [(type-substitute k binder replacement)
                                   (type-substitute v binder replacement)]))
                           (:entries type))
            refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) entries')]
        (at/->MapT (prov/with-refs prov refs) entries'))

      (at/vector-type? type)
      (let [items' (mapv #(type-substitute % binder replacement) (:items type))]
        (at/->VectorT (prov/with-refs prov (mapv prov/of items'))
                      items'
                      (:homogeneous? type)))

      (at/set-type? type)
      (let [members' (set (map #(type-substitute % binder replacement) (:members type)))]
        (at/->SetT (prov/with-refs prov (mapv prov/of members'))
                   members'
                   (:homogeneous? type)))

      (at/seq-type? type)
      (let [items' (mapv #(type-substitute % binder replacement) (:items type))]
        (at/->SeqT (prov/with-refs prov (mapv prov/of items'))
                   items'
                   (:homogeneous? type)))

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
