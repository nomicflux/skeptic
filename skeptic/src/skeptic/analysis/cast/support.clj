(ns skeptic.analysis.cast.support
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]))

(s/defn sealed-ground-name :- s/Any
  [type :- s/Any]
  (some-> type ato/normalize :ground ata/type-var-name))

(defn- missing-type-field
  [source-type target-type]
  (cond
    (nil? source-type) :source-type
    (nil? target-type) :target-type))

(s/defn cast-result :- csch/CastResult
  [{:keys [ok? source-type target-type rule polarity reason children details] :as inputs} :- s/Any]
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

(s/defn cast-ok :- csch/CastResult
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any]
   (cast-ok source-type target-type rule [] nil))
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any children :- s/Any]
   (cast-ok source-type target-type rule children nil))
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any children :- s/Any details :- s/Any]
   (cast-result {:ok? true
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity :none
                 :children children
                 :details details})))

(s/defn cast-fail :- csch/CastResult
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any polarity :- s/Any reason :- s/Any]
   (cast-fail source-type target-type rule polarity reason [] nil))
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any polarity :- s/Any reason :- s/Any children :- s/Any]
   (cast-fail source-type target-type rule polarity reason children nil))
  ([source-type :- s/Any target-type :- s/Any rule :- s/Any polarity :- s/Any reason :- s/Any children :- s/Any details :- s/Any]
   (cast-result {:ok? false
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity polarity
                 :reason reason
                 :children children
                 :details details})))

(s/defn with-cast-path :- csch/CastResult
  [result :- csch/CastResult segment :- s/Any]
  (cond-> result
    (some? segment) (update :path (fnil conj []) segment)))

(defn indexed-request
  [kind idx source-type target-type opts]
  {:source-type source-type
   :target-type target-type
   :opts opts
   :path-segment {:kind kind
                  :index idx}})

(s/defn all-ok? :- s/Bool
  [results :- [csch/CastResult]]
  (every? :ok? results))

(s/defn aggregate-children :- csch/CastResult
  [source-type :- s/Any target-type :- s/Any rule :- s/Any polarity :- s/Any reason :- s/Any children :- s/Any]
  (if (all-ok? children)
    (cast-ok source-type target-type rule children)
    (cast-fail source-type target-type rule polarity reason children)))

(s/defn semantic-type-children :- [s/Any]
  [type :- s/Any]
  (let [type (ato/normalize type)]
    (cond
      (at/sealed-dyn-type? type) [(:ground type)]
      (at/fn-method-type? type) (into [(:output type)] (:inputs type))
      (at/fun-type? type) (:methods type)
      (at/maybe-type? type) [(:inner type)]
      (or (at/union-type? type) (at/intersection-type? type)) (:members type)
      (at/conditional-type? type) (mapv second (:branches type))
      (at/map-type? type) (mapcat identity (:entries type))
      (or (at/vector-type? type) (at/seq-type? type)) (:items type)
      (at/set-type? type) (:members type)
      (at/var-type? type) [(:inner type)]
      (at/value-type? type) [(:inner type)]
      (at/forall-type? type) [(:body type)]
      :else [])))

(s/defn contains-sealed-ground? :- s/Bool
  [type :- s/Any binder :- s/Any]
  (boolean
   (some #(and (at/sealed-dyn-type? %)
               (= binder (sealed-ground-name %)))
         (tree-seq seq semantic-type-children (ato/normalize type)))))

(s/defn rule-seal-delta :- s/Int
  [result :- csch/CastResult binder :- s/Any]
  (cond
    (and (= :seal (:rule result))
         (= binder (sealed-ground-name (:sealed-type result))))
    1

    (and (= :sealed-collapse (:rule result))
         (= binder (sealed-ground-name (:source-type result))))
    -1

    :else
    0))

(s/defn seal-balance :- s/Int
  [cast-result :- csch/CastResult binder :- s/Any]
  (reduce + 0 (map #(rule-seal-delta % binder)
                   (tree-seq seq :children cast-result))))

(s/defn leaked-sealed-type :- s/Any
  [cast-result :- csch/CastResult binder :- s/Any]
  (some #(when (= 1 (rule-seal-delta % binder))
           (:sealed-type %))
        (tree-seq seq :children cast-result)))

(s/defn exit-nu-scope :- csch/CastResult
  [artifact :- s/Any binder :- s/Any]
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

(s/defn method-accepts-arity? :- s/Bool
  [method :- ats/SemanticType arity :- s/Int]
  (if (:variadic? method)
    (>= arity (:min-arity method))
    (= arity (:min-arity method))))

(s/defn matching-source-method :- (s/maybe ats/SemanticType)
  [source-fun :- ats/SemanticType target-method :- ats/SemanticType]
  (some #(when (method-accepts-arity? % (count (:inputs target-method))) %)
        (:methods source-fun)))

(s/defn optional-key-inner :- ats/SemanticType
  [type :- ats/SemanticType]
  (if (at/optional-key-type? type)
    (:inner type)
    type))

(s/defn check-type-test :- csch/CastResult
  ([value-type :- s/Any ground-type :- s/Any]
   (check-type-test value-type ground-type {}))
  ([value-type :- s/Any ground-type :- s/Any _opts :- s/Any]
   (let [value-type (ato/normalize value-type)
         ground-type (ato/normalize ground-type)
         details {:matches? (at/type=? value-type ground-type)}]
     (if (at/sealed-dyn-type? value-type)
       (cast-fail value-type ground-type :is-tamper :global :is-tamper [] details)
       (cast-ok value-type ground-type :dynamic-test [] details)))))
