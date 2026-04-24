(ns skeptic.analysis.cast.support
  (:require [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn sealed-ground-name
  [type]
  (some-> type ato/normalize :ground ata/type-var-name))

(defn- missing-type-field
  [source-type target-type]
  (cond
    (nil? source-type) :source-type
    (nil? target-type) :target-type))

(defn cast-result
  [{:keys [ok? source-type target-type rule polarity reason children details] :as inputs}]
  (if-let [missing-field (missing-type-field source-type target-type)]
    (throw (ex-info "Cast result missing semantic type"
                    {:rule rule
                     :reason reason
                     :missing-field missing-field
                     :cast-result-inputs inputs}))
    (cond-> {:ok? ok?
             :blame-side (if ok? :none (abr/polarity->side polarity))
             :blame-polarity (if ok? :none polarity)
             :rule rule
             :source-type source-type
             :target-type target-type
             :children (vec children)
             :reason reason}
      (map? details) (merge details))))

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

(defn indexed-request
  [kind idx source-type target-type opts]
  {:source-type source-type
   :target-type target-type
   :opts opts
   :path-segment {:kind kind
                  :index idx}})

(defn all-ok?
  [results]
  (every? :ok? results))

(defn aggregate-children
  [source-type target-type rule polarity reason children]
  (if (all-ok? children)
    (cast-ok source-type target-type rule children)
    (cast-fail source-type target-type rule polarity reason children)))

(defn semantic-type-children
  [type]
  (let [type (ato/normalize type)]
    (cond
      (at/sealed-dyn-type? type) [(:ground type)]
      (at/fn-method-type? type) (into [(:output type)] (:inputs type))
      (at/fun-type? type) (:methods type)
      (at/maybe-type? type) [(:inner type)]
      (or (at/union-type? type) (at/intersection-type? type)) (:members type)
      (at/map-type? type) (mapcat identity (:entries type))
      (or (at/vector-type? type) (at/seq-type? type)) (:items type)
      (at/set-type? type) (:members type)
      (at/var-type? type) [(:inner type)]
      (at/value-type? type) [(:inner type)]
      (at/forall-type? type) [(:body type)]
      :else [])))

(defn contains-sealed-ground?
  [type binder]
  (boolean
   (some #(and (at/sealed-dyn-type? %)
               (= binder (sealed-ground-name %)))
         (tree-seq seq semantic-type-children (ato/normalize type)))))

(defn rule-seal-delta
  [result binder]
  (cond
    (and (= :seal (:rule result))
         (= binder (sealed-ground-name (:sealed-type result))))
    1

    (and (= :sealed-collapse (:rule result))
         (= binder (sealed-ground-name (:source-type result))))
    -1

    :else
    0))

(defn seal-balance
  [cast-result binder]
  (reduce + 0 (map #(rule-seal-delta % binder)
                   (tree-seq seq :children cast-result))))

(defn leaked-sealed-type
  [cast-result binder]
  (some #(when (= 1 (rule-seal-delta % binder))
           (:sealed-type %))
        (tree-seq seq :children cast-result)))

(defn exit-nu-scope
  [artifact binder]
  (if (and (map? artifact)
           (contains? artifact :ok?)
           (contains? artifact :rule)
           (contains? artifact :children))
    (if (pos? (seal-balance artifact binder))
      (let [source (or (leaked-sealed-type artifact binder)
                       (:source-type artifact))]
        (cast-fail source
                   (at/->TypeVarT (ato/derive-prov source) binder)
                   :nu-tamper
                   :global
                   :nu-tamper
                   [artifact]
                   nil))
      (let [target (:target-type artifact)]
        (cast-ok target (at/->TypeVarT (ato/derive-prov target) binder) :nu-pass [artifact] nil)))
    (let [type (ato/normalize artifact)]
      (if (contains-sealed-ground? type binder)
        (cast-fail type (at/->TypeVarT (ato/derive-prov type) binder) :nu-tamper :global :nu-tamper [] nil)
        (cast-ok type (at/->TypeVarT (ato/derive-prov type) binder) :nu-pass [] nil)))))

(defn method-accepts-arity?
  [method arity]
  (if (:variadic? method)
    (>= arity (:min-arity method))
    (= arity (:min-arity method))))

(defn matching-source-method
  [source-fun target-method]
  (some #(when (method-accepts-arity? % (count (:inputs target-method))) %)
        (:methods source-fun)))

(defn optional-key-inner
  [type]
  (if (at/optional-key-type? type)
    (:inner type)
    type))

(defn check-type-test
  ([value-type ground-type]
   (check-type-test value-type ground-type {}))
  ([value-type ground-type _opts]
   (let [value-type (ato/normalize value-type)
         ground-type (ato/normalize ground-type)
         details {:matches? (at/type=? value-type ground-type)}]
     (if (at/sealed-dyn-type? value-type)
       (cast-fail value-type ground-type :is-tamper :global :is-tamper [] details)
       (cast-ok value-type ground-type :dynamic-test [] details)))))
