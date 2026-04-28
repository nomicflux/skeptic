(ns skeptic.analysis.origin.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types.schema :as ats]))

(declare Origin Assumption RootOrigin)

(s/defschema RootOrigin
  {:kind (s/eq :root)
   :sym  s/Any
   :type ats/SemanticType})

(s/defschema OpaqueOrigin
  {:kind                          (s/eq :opaque)
   :type                          ats/SemanticType
   (s/optional-key :binding-sym)  s/Symbol})

(s/defschema MapKeyLookupOrigin
  {:kind                          (s/eq :map-key-lookup)
   :root                          (s/recursive #'RootOrigin)
   :path                          [s/Any]
   :defaults                      [s/Any]
   (s/optional-key :binding-sym)  s/Symbol})

(s/defschema BranchOrigin
  {:kind                          (s/eq :branch)
   :test                          (s/recursive #'Assumption)
   :then-origin                   (s/recursive #'Origin)
   :else-origin                   (s/recursive #'Origin)
   (s/optional-key :binding-sym)  s/Symbol})

(s/defschema Origin
  (s/conditional
    #(= :root           (:kind %)) RootOrigin
    #(= :opaque         (:kind %)) OpaqueOrigin
    #(= :map-key-lookup (:kind %)) MapKeyLookupOrigin
    #(= :branch         (:kind %)) BranchOrigin))

(s/defschema TruthyLocalAssumption
  {:kind     (s/eq :truthy-local)
   :root     RootOrigin
   :polarity s/Bool})

(s/defschema BooleanPropositionAssumption
  {:kind     (s/eq :boolean-proposition)
   :expr     s/Any
   :polarity s/Bool})

(s/defschema BlankCheckAssumption
  {:kind     (s/eq :blank-check)
   :root     RootOrigin
   :polarity s/Bool})

(s/defschema ContainsKeyAssumption
  {:kind     (s/eq :contains-key)
   :root     RootOrigin
   :key      s/Any
   :polarity s/Bool})

(s/defschema TypePredicateAssumption
  {:kind                    (s/eq :type-predicate)
   :root                    RootOrigin
   :pred                    s/Keyword
   (s/optional-key :class)  s/Any
   :polarity                s/Bool})

(s/defschema ValueEqualityAssumption
  {:kind     (s/eq :value-equality)
   :root     RootOrigin
   :values   [s/Any]
   :polarity s/Bool})

(s/defschema PathValueEqualityAssumption
  {:kind     (s/eq :path-value-equality)
   :root     RootOrigin
   :path     [s/Any]
   :values   [s/Any]
   :polarity s/Bool})

(s/defschema PathTypePredicateAssumption
  {:kind                    (s/eq :path-type-predicate)
   :root                    RootOrigin
   :path                    [s/Any]
   :pred                    s/Keyword
   (s/optional-key :class)  s/Any
   :polarity                s/Bool})

(s/defschema ConditionalBranchAssumption
  {:kind          (s/eq :conditional-branch)
   :root          RootOrigin
   :narrowed-type ats/SemanticType
   :polarity      s/Bool})

(s/defschema ConjunctionAssumption
  {:kind  (s/eq :conjunction)
   :parts [(s/recursive #'Assumption)]})

(s/defschema DisjunctionAssumption
  {:kind  (s/eq :disjunction)
   :parts [(s/recursive #'Assumption)]})

(s/defschema RootedAssumption
  (s/conditional
    #(= :truthy-local        (:kind %)) TruthyLocalAssumption
    #(= :blank-check         (:kind %)) BlankCheckAssumption
    #(= :contains-key        (:kind %)) ContainsKeyAssumption
    #(= :type-predicate      (:kind %)) TypePredicateAssumption
    #(= :value-equality      (:kind %)) ValueEqualityAssumption
    #(= :path-value-equality (:kind %)) PathValueEqualityAssumption
    #(= :path-type-predicate (:kind %)) PathTypePredicateAssumption
    #(= :conditional-branch  (:kind %)) ConditionalBranchAssumption))

(s/defschema Assumption
  (s/conditional
    #(= :truthy-local        (:kind %)) TruthyLocalAssumption
    #(= :boolean-proposition (:kind %)) BooleanPropositionAssumption
    #(= :blank-check         (:kind %)) BlankCheckAssumption
    #(= :contains-key        (:kind %)) ContainsKeyAssumption
    #(= :type-predicate      (:kind %)) TypePredicateAssumption
    #(= :value-equality      (:kind %)) ValueEqualityAssumption
    #(= :path-value-equality (:kind %)) PathValueEqualityAssumption
    #(= :path-type-predicate (:kind %)) PathTypePredicateAssumption
    #(= :conditional-branch  (:kind %)) ConditionalBranchAssumption
    #(= :conjunction         (:kind %)) ConjunctionAssumption
    #(= :disjunction         (:kind %)) DisjunctionAssumption))

(s/defschema Conjuncts
  {:then-conjuncts [Assumption]
   :else-conjuncts [Assumption]})

(s/defschema BranchEnvs
  {:then-locals      {s/Any s/Any}
   :then-assumptions [Assumption]
   :else-locals      {s/Any s/Any}
   :else-assumptions [Assumption]})

(s/defschema AssumptionTruth
  (s/enum :true :false :unknown))

(s/defschema PredInfo
  {:pred                   s/Keyword
   (s/optional-key :class) s/Any})
