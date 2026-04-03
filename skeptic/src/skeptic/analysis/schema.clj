(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema.cast-kernel :as ask]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema.map-cast :as asmc]
            [skeptic.analysis.types :as at]))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (ab/schema->type source-type)
         target-type (ab/schema->type target-type)
         opts (assoc opts :polarity polarity)]
     (cond
       (at/bottom-type? source-type)
       (ascs/cast-ok source-type target-type :bottom-source)

       (= source-type target-type)
       (ascs/cast-ok source-type target-type :exact)

       (or (at/forall-type? target-type)
           (at/forall-type? source-type))
       (ask/check-quantified-cast check-cast source-type target-type polarity opts)

       (or (and (at/type-var-type? source-type)
                (at/dyn-type? target-type))
           (at/type-var-type? target-type)
           (at/type-var-type? source-type)
           (at/sealed-dyn-type? source-type))
       (ask/check-abstract-type-cast source-type target-type polarity opts)

       (at/dyn-type? target-type)
       (ascs/cast-ok source-type target-type :target-dyn)

       (or (at/union-type? target-type)
           (at/union-type? source-type))
       (ask/check-union-cast check-cast source-type target-type polarity opts)

       (or (at/intersection-type? target-type)
           (at/intersection-type? source-type))
       (ask/check-intersection-cast check-cast source-type target-type polarity opts)

       (or (at/maybe-type? source-type)
           (at/maybe-type? target-type))
       (ask/check-maybe-cast check-cast source-type target-type polarity opts)

       (at/optional-key-type? source-type)
       (ask/check-wrapper-cast check-cast source-type target-type opts)

       (or (at/optional-key-type? target-type)
           (at/var-type? source-type)
           (at/var-type? target-type))
       (ask/check-wrapper-cast check-cast source-type target-type opts)

       (and (at/fun-type? source-type) (at/fun-type? target-type))
       (ask/check-function-cast check-cast source-type target-type polarity opts)

       (and (at/map-type? source-type) (at/map-type? target-type))
       (asmc/check-map-cast check-cast source-type target-type polarity opts)

       (and (at/vector-type? source-type) (at/vector-type? target-type))
       (ask/check-vector-cast check-cast source-type target-type polarity opts)

       (and (at/seq-type? source-type) (at/seq-type? target-type))
       (ask/check-seq-cast check-cast source-type target-type polarity opts)

       (and (at/set-type? source-type) (at/set-type? target-type))
       (ask/check-set-cast check-cast source-type target-type polarity opts)

      :else
      (ask/check-leaf-cast source-type target-type polarity)))))

(defn schema-compatible?
  [expected actual]
  (:ok? (check-cast (ab/schema->type actual) (ab/schema->type expected))))

(s/defschema WithPlaceholder
  {s/Keyword s/Any})

(s/defschema ArgCount
  (s/cond-pre s/Int (s/eq :varargs)))

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int

   (s/optional-key :resolution-path) [s/Any]
   (s/optional-key :schema) s/Any
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> (s/recursive #'AnnotatedExpression)
                                         {s/Int (s/recursive #'AnnotatedExpression)} (s/recursive #'AnnotatedExpression))
   (s/optional-key :expected-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :actual-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :output) s/Any
   (s/optional-key :arglists) {ArgCount s/Any}
   (s/optional-key :arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :map?) s/Bool
   (s/optional-key :finished?) s/Bool})
