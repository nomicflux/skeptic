(ns skeptic.analysis.cast.support
  (:require [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn schema-equivalent?
  [expected actual]
  (= (abc/canonicalize-schema expected)
     (abc/canonicalize-schema actual)))

(defn ensure-cast-state
  [cast-state]
  (merge {:nu-bindings []
          :abstract-vars #{}
          :active-seals #{}}
         cast-state))

(defn cast-state
  [opts]
  (ensure-cast-state (:cast-state opts)))

(defn with-abstract-var
  [opts binder]
  (assoc opts :cast-state (update (cast-state opts) :abstract-vars conj binder)))

(defn with-nu-binding
  [opts binder witness-type]
  (assoc opts :cast-state (-> (cast-state opts)
                              (update :nu-bindings conj {:type-var binder
                                                         :witness-type (ato/normalize-type witness-type)})
                              (update :abstract-vars conj binder))))

(defn register-seal
  [opts sealed-type]
  (assoc opts :cast-state (update (cast-state opts) :active-seals conj (ato/normalize-type sealed-type))))

(defn sealed-ground-name
  [type]
  (some-> type ato/normalize-type :ground ata/type-var-name))

(defn contains-sealed-ground?
  [type binder]
  (let [type (ato/normalize-type type)]
    (cond
      (at/sealed-dyn-type? type)
      (= binder (sealed-ground-name type))

      (at/fn-method-type? type)
      (or (contains-sealed-ground? (:output type) binder)
          (some #(contains-sealed-ground? % binder) (:inputs type)))

      (at/fun-type? type)
      (some #(contains-sealed-ground? % binder) (:methods type))

      (at/maybe-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (or (at/union-type? type)
          (at/intersection-type? type))
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/map-type? type)
      (some (fn [[k v]]
              (or (contains-sealed-ground? k binder)
                  (contains-sealed-ground? v binder)))
            (:entries type))

      (or (at/vector-type? type)
          (at/seq-type? type))
      (some #(contains-sealed-ground? % binder) (:items type))

      (at/set-type? type)
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/var-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/value-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/forall-type? type)
      (and (not= binder (:binder type))
           (contains-sealed-ground? (:body type) binder))

      :else
      false)))

(defn cast-result
  [{:keys [ok? source-type target-type rule polarity reason children details]}]
  (cond-> {:ok? ok?
           :blame-side (if ok? :none (abr/polarity->side polarity))
           :blame-polarity (if ok? :none polarity)
           :rule rule
           :source-type source-type
           :target-type target-type
           :children (vec children)
           :reason reason}
    (map? details) (merge details)))

(defn cast-ok
  ([source-type target-type rule]
   (cast-ok source-type target-type rule [] nil))
  ([source-type target-type rule children]
   (cast-ok source-type target-type rule children nil))
  ([source-type target-type rule children details]
   (cast-result {:ok? true
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity :none
                 :children children
                 :details details})))

(defn cast-fail
  ([source-type target-type rule polarity reason]
   (cast-fail source-type target-type rule polarity reason [] nil))
  ([source-type target-type rule polarity reason children]
   (cast-fail source-type target-type rule polarity reason children nil))
  ([source-type target-type rule polarity reason children details]
   (cast-result {:ok? false
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity polarity
                 :reason reason
                 :children children
                 :details details})))

(defn with-cast-path
  [result segment]
  (cond-> result
    (some? segment) (update :path (fnil conj []) segment)))

(defn indexed-cast-children
  [segment-kind build-child xs]
  (mapv (fn [idx x]
          (with-cast-path (build-child x)
            {:kind segment-kind
             :index idx}))
        (range)
        xs))

(defn all-ok?
  [results]
  (every? :ok? results))

(defn check-type-test
  ([value-type ground-type]
   (check-type-test value-type ground-type {}))
  ([value-type ground-type opts]
   (let [value-type (ato/normalize-type value-type)
         ground-type (ato/normalize-type ground-type)]
     (if (at/sealed-dyn-type? value-type)
       (cast-fail value-type
                  ground-type
                  :is-tamper
                  :global
                  :is-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok value-type
                ground-type
                :dynamic-test
                []
                {:matches? (= value-type ground-type)
                 :cast-state (cast-state opts)})))))

(defn exit-nu-scope
  ([type binder]
   (exit-nu-scope type binder {}))
  ([type binder opts]
   (let [type (ato/normalize-type type)
         binder (or (ata/type-var-name (ato/normalize-type binder))
                    binder)]
     (if (contains-sealed-ground? type binder)
       (cast-fail type
                  (at/->TypeVarT binder)
                  :nu-tamper
                  :global
                  :nu-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok type
                (at/->TypeVarT binder)
                :nu-pass
                []
                {:cast-state (cast-state opts)})))))

(defn method-accepts-arity?
  [method arity]
  (if (:variadic? method)
    (>= arity (:min-arity method))
    (= arity (:min-arity method))))

(defn matching-source-method
  [source-fun target-method]
  (some #(when (method-accepts-arity? % (count (:inputs target-method)))
           %)
        (:methods source-fun)))

(defn optional-key-inner
  [type]
  (if (at/optional-key-type? type)
    (:inner type)
    type))
