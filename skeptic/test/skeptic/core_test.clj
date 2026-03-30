(ns skeptic.core-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.core :as sut]))

(deftest report-fields-include-blame-metadata
  (let [fields (sut/report-fields
                {:location {:file "src/example.clj"
                            :line 12
                            :column 3}
                 :blame-side :term
                 :blame-polarity :positive
                 :rule :function
                 :actual-type (as/schema->type s/Int)
                 :expected-type (as/schema->type s/Str)
                 :source-expression "(takes-str x)"
                 :focus-sources ["x"]
                 :enclosing-form 'example/takes-str
                 :expanded-expression '(takes-str x)})]
    (is (some #{["Location: \t\t" "src/example.clj:12:3"]} fields))
    (is (some #{["Blame: \t\t" "term / positive"]} fields))
    (is (some #{["Cast rule: \t\t" "function"]} fields))
    (is (some #{["Actual type: \t\t" "Int"]} fields))
    (is (some #{["Expected type: \t" "Str"]} fields))
    (is (some #{["Expression: \t\t" "(takes-str x)"]} fields))
    (is (some #{["Affected input: \t" "x"]} fields))
    (is (some #{["In enclosing form: \t" "example/takes-str"]} fields))
    (is (some #{["Analyzed expression: \t" "(takes-str x)"]} fields))))

(deftest report-fields-render-semantic-polymorphic-types
  (let [type-var (as/->TypeVarT 'X)
        fields (sut/report-fields
                {:rule :generalize
                 :actual-type (as/->SealedDynT type-var)
                 :expected-type (as/->ForallT 'X type-var)
                 :source-expression "(poly x)"})]
    (is (some #{["Cast rule: \t\t" "generalize"]} fields))
    (is (some #{["Actual type: \t\t" "(sealed X)"]} fields))
    (is (some #{["Expected type: \t" "(forall X X)"]} fields))))
