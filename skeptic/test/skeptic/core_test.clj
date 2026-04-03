(ns skeptic.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.core :as sut]
            [skeptic.inconsistence.report :as inrep]))

(def ui-internal-markers
  [":skeptic.analysis.schema/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(def report-summary
  {:location {:file "src/example.clj"
              :line 12
              :column 3}
   :blame-side :term
   :blame-polarity :positive
   :rule :function
   :actual-type (ab/schema->type s/Int)
   :expected-type (ab/schema->type s/Str)
   :source-expression "(takes-str x)"
   :focus-sources ["x"]
   :enclosing-form 'example/takes-str
   :expanded-expression '(takes-str x)})

(deftest report-fields-hide-detail-fields-when-not-verbose
  (let [fields (sut/report-fields report-summary)]
    (is (some #{["Location: \t\t" "src/example.clj:12:3"]} fields))
    (is (some #{["Blame: \t\t\t"
                 "this expression or returned value does not match what the surrounding code expects"]}
              fields))
    (is (not (some #{["Cast rule: \t\t" "function"]} fields)))
    (is (not (some #{["Actual type: \t\t" "Int"]} fields)))
    (is (not (some #{["Expected type: \t" "Str"]} fields)))
    (is (not (some #{["Expression: \t\t" "(takes-str x)"]} fields)))
    (is (not (some #{["Affected input: \t" "x"]} fields)))
    (is (not (some #{["In enclosing form: \t" "example/takes-str"]} fields)))
    (is (not (some #{["Analyzed expression: \t" "(takes-str x)"]} fields)))))

(deftest report-fields-include-detail-fields-when-verbose
  (let [fields (sut/report-fields report-summary true)]
    (is (some #{["Location: \t\t" "src/example.clj:12:3"]} fields))
    (is (some #{["Blame: \t\t\t"
                 "this expression or returned value does not match what the surrounding code expects"]}
              fields))
    (is (some #{["Cast rule: \t\t" "function"]} fields))
    (is (some #{["Actual type: \t\t" "Int"]} fields))
    (is (some #{["Expected type: \t" "Str"]} fields))
    (is (some #{["Expression: \t\t" "(takes-str x)"]} fields))
    (is (some #{["Affected input: \t" "x"]} fields))
    (is (some #{["In enclosing form: \t" "example/takes-str"]} fields))
    (is (some #{["Analyzed expression: \t" "(takes-str x)"]} fields))))

(deftest report-fields-render-user-friendly-blame-for-context-and-global-cases
  (let [context-fields (sut/report-fields {:blame-side :context
                                           :blame-polarity :negative})
        global-fields (sut/report-fields {:blame-side :global
                                          :blame-polarity :global})]
    (is (some #{["Blame: \t\t\t"
                 "the surrounding code is using this value in a way its schema does not allow"]}
              context-fields))
    (is (some #{["Blame: \t\t\t"
                 "an abstract value was inspected or escaped the scope where it is valid"]}
              global-fields))))

(deftest report-fields-render-semantic-polymorphic-types-in-verbose-mode
  (let [type-var (at/->TypeVarT 'X)
        fields (sut/report-fields
                {:rule :generalize
                 :actual-type (at/->SealedDynT type-var)
                 :expected-type (at/->ForallT 'X type-var)
                 :source-expression "(poly x)"}
                true)]
    (is (some #{["Cast rule: \t\t" "generalize"]} fields))
    (is (some #{["Actual type: \t\t" "(sealed X)"]} fields))
    (is (some #{["Expected type: \t" "(forall X X)"]} fields))))

(deftest report-summary-collapses-output-into-single-entry
  (let [summary (inrep/report-summary
                 {:report-kind :output
                  :cast-result {:source-type (ab/schema->type {:name s/Keyword})
                                :target-type (ab/schema->type {:name s/Str})}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :path [{:kind :map-key :key :name}]
                                  :source-type (ab/schema->type s/Keyword)
                                  :target-type (ab/schema->type s/Str)}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "declared return schema")))
    (is (some-> summary :errors first (.contains "Problem fields:")))
    (is (some-> summary :errors first (.contains "[:name] has Keyword but expected Str")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-hides-internal-cast-branches
  (let [summary (inrep/report-summary
                 {:report-kind :output
                  :cast-result {:source-type (ab/schema->type {:b s/Int})
                                :target-type (ab/schema->type {:b s/Int})}
                  :cast-results [{:reason :missing-key
                                  :path [{:kind :source-union-branch :index 1}
                                         {:kind :map-key :key :b}]}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "[:b] is missing")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-collapses-input-union-branches
  (let [summary (inrep/report-summary
                 {:report-kind :input
                  :blame '(takes-either-branch :bad)
                  :focuses [:bad]
                  :errors ["err-1" "err-2"]
                  :cast-results [{:reason :leaf-mismatch
                                  :source-type (ab/schema->type s/Keyword)
                                  :target-type (ab/schema->type s/Int)
                                  :path [{:kind :target-union-branch :index 0}]}
                                 {:reason :leaf-mismatch
                                  :source-type (ab/schema->type s/Keyword)
                                  :target-type (ab/schema->type s/Str)
                                  :path [{:kind :target-union-branch :index 1}]}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "has incompatible schema:")))
    (is (some-> summary :errors first (.contains "Keyword does not match any of: Int, Str")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-and-fields-sanitize-placeholder-heavy-types
  (let [placeholder (at/->PlaceholderT 'clj-threals.threals/Threal)
        summary (inrep/report-summary
                 {:report-kind :input
                  :blame '(simplify gt_fn [g r b])
                  :focuses ['[g r b]]
                  :cast-result {:rule :vector
                                :source-type (ab/schema->type [s/Any])
                                :target-type (at/->VectorT [(at/->SetT #{(at/->VectorT [placeholder placeholder placeholder]
                                                                             false)}
                                                                       false)]
                                                           true)}
                  :cast-results [{:reason :leaf-mismatch
                                  :source-type (ab/schema->type s/Any)
                                  :target-type placeholder
                                  :path [{:kind :vector-index :index 0}]}]})
        fields (sut/report-fields summary)
        printed (str/join "\n"
                          (concat (map (fn [[label value]]
                                         (str label value))
                                       fields)
                                  (:errors summary)))]
    (is (not-any? (fn [[label _]]
                    (#{"Actual type: \t\t" "Expected type: \t"} label))
                  fields))
    (let [verbose-fields (sut/report-fields summary true)]
      (is (some #{["Actual type: \t\t" "[Any]"]} verbose-fields))
      (is (some (fn [[label value]]
                  (and (= "Expected type: \t" label)
                       (str/includes? value "Threal")))
                verbose-fields)))
    (assert-no-ui-internals printed)))

(deftest report-fields-prefer-top-level-cast-metadata-in-verbose-mode
  (let [fields (sut/report-fields
                (inrep/report-summary
                 {:rule :leaf-overlap
                  :actual-type (ab/schema->type s/Keyword)
                  :expected-type (ab/schema->type s/Int)
                  :cast-result {:rule :target-union
                                :source-type (ab/schema->type s/Keyword)
                                :target-type (ab/schema->type (s/either s/Int s/Str))}
                  :source-expression "(takes-either-branch :bad)"
                  :errors ["err"]
                  :cast-results []})
                true)]
    (is (some #{["Cast rule: \t\t" "target-union"]} fields))
    (is (some #{["Actual type: \t\t" "Keyword"]} fields))
    (is (some (fn [[label value]]
                (and (= "Expected type: \t" label)
                     (.contains value "Int")
                     (.contains value "Str")))
              fields))))

(deftest output-report-fields-prefer-actionable-leaf-metadata-in-verbose-mode
  (let [placeholder (at/->PlaceholderT 'clj-threals.threals/Threal)
        triple (at/->VectorT [placeholder placeholder placeholder] false)
        slot (at/->SetT #{triple} false)
        expected-result (at/->VectorT [slot slot slot] false)
        actual-result (at/->SetT #{expected-result} false)
        summary (inrep/report-summary
                 {:report-kind :output
                  :rule :source-union
                  :actual-type (at/->UnionT #{(ab/schema->type {:result s/Any
                                                                :cache s/Any})
                                              (ab/schema->type {:result actual-result
                                                                :cache s/Any})})
                  :expected-type (ab/schema->type {:result expected-result
                                                   :cache s/Any})
                  :cast-result {:rule :source-union
                                :source-type (at/->UnionT #{(ab/schema->type {:result s/Any
                                                                              :cache s/Any})
                                                            (ab/schema->type {:result actual-result
                                                                              :cache s/Any})})
                                :target-type (ab/schema->type {:result expected-result
                                                              :cache s/Any})}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type actual-result
                                  :target-type expected-result
                                  :path [{:kind :source-union-branch :index 1}
                                         {:kind :map-key :key :result}]}]})
        fields (sut/report-fields summary true)]
    (is (some #{["Cast rule: \t\t" "leaf-overlap"]} fields))
    (is (some (fn [[label value]]
                (and (= "Actual type: \t\t" label)
                     (str/includes? value "Threal")
                     (not (str/includes? value "union"))
                     (not= "Any" value)))
              fields))
    (is (some (fn [[label value]]
                (and (= "Expected type: \t" label)
                     (str/includes? value "Threal")
                     (not (str/includes? value "union"))))
              fields))))
