(ns skeptic.analysis.origin.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types.schema :as ats]))

(declare Assumption)

(s/defschema Origin
  {:kind s/Keyword
   (s/optional-key :sym)         s/Any
   (s/optional-key :type)        ats/SemanticType
   (s/optional-key :root)        (s/recursive #'Origin)
   (s/optional-key :path)        [s/Any]
   (s/optional-key :defaults)    [s/Any]
   (s/optional-key :test)        (s/recursive #'Assumption)
   (s/optional-key :then-origin) (s/maybe (s/recursive #'Origin))
   (s/optional-key :else-origin) (s/maybe (s/recursive #'Origin))
   (s/optional-key :binding-sym) s/Symbol
   s/Keyword                     s/Any})

(s/defschema Assumption
  {:kind s/Keyword
   (s/optional-key :polarity)      s/Any
   (s/optional-key :root)          (s/recursive #'Origin)
   (s/optional-key :parts)         [(s/recursive #'Assumption)]
   (s/optional-key :narrowed-type) ats/SemanticType
   (s/optional-key :path)          [s/Any]
   (s/optional-key :pred)          s/Keyword
   (s/optional-key :class)         s/Any
   (s/optional-key :key)           s/Any
   (s/optional-key :values)        s/Any
   (s/optional-key :expr)          s/Any
   s/Keyword                       s/Any})

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
