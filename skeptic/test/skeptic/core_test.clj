(ns skeptic.core-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.checking.pipeline :as checking]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.core :as sut]
            [skeptic.inconsistence.report :as inrep]))

(def ui-internal-markers
  [":skeptic.analysis.types/"
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

(defn strip-ansi
  [text]
  (str/replace (str text) #"\u001B\[[0-9;]*m" ""))

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
    (is (= "context( value )"
           (some->> fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
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
    (is (= "context( value )"
           (some->> fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
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
        missing-fields (sut/report-fields {:blame-side :none
                                           :blame-polarity :none})
        global-fields (sut/report-fields {:blame-side :global
                                          :blame-polarity :global})]
    (is (= "context( value )"
           (some->> context-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (= "<missing>"
           (some->> missing-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (= "scope escape"
           (some->> global-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (some->> context-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (and (str/includes? value "\u001B[37;2mvalue")
                                (str/includes? value "\u001B[37;1mcontext")
                                (str/includes? value "\u001B[37;1m( ")
                                (str/includes? value "\u001B[37;1m )")))))))
    (is (some->> (sut/report-fields report-summary)
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (and (str/includes? value "\u001B[37;1mvalue")
                                (str/includes? value "\u001B[37;2mcontext")
                                (str/includes? value "\u001B[37;2m( ")
                                (str/includes? value "\u001B[37;2m )")))))))
    (is (some->> global-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (str/includes? value "\u001B[37;1mscope escape"))))))
    (is (some->> missing-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (str/includes? value "\u001B[37m<missing>"))))))))

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
    (is (some-> summary :errors first (.contains "declared return type")))
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
    (is (some-> summary :errors first (.contains "expected type")))
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
                                              (ato/normalize-type {:result actual-result
                                                                   :cache (ab/schema->type s/Any)})})
                  :expected-type (ato/normalize-type {:result expected-result
                                                      :cache (ab/schema->type s/Any)})
                  :cast-result {:rule :source-union
                                :source-type (at/->UnionT #{(ab/schema->type {:result s/Any
                                                                              :cache s/Any})
                                                            (ato/normalize-type {:result actual-result
                                                                                 :cache (ab/schema->type s/Any)})})
                                :target-type (ato/normalize-type {:result expected-result
                                                                  :cache (ab/schema->type s/Any)})}
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

(deftest exception-report-fields-skip-blame-and-show-phase
  (let [fields (sut/report-fields
                {:report-kind :exception
                 :phase :declaration
                 :location {:file "test.clj" :line 7}
                 :blame 'example/bad
                 :source-expression "(bad)"
                 :errors ["oops"]}
                true)]
    (is (some #{["Location: \t\t" "test.clj:7"]} fields))
    (is (some #{["Phase: \t\t\t" "declaration"]} fields))
    (is (some #{["Expression: \t\t" "(bad)"]} fields))
    (is (not-any? #(= "Blame: \t\t\t" (first %)) fields))))

(deftest check-project-localizes-lazy-form-exceptions
  (require 'skeptic.check-project-best-effort-examples)
  (let [real-check-resolved-form checking/check-resolved-form
        source-file (java.io.File. "test/skeptic/check_project_best_effort_examples.clj")]
    (with-redefs [checking/check-resolved-form
                  (fn [dict ns-sym source-file source-form analyzed opts]
                    (if (= 'exploding-form (second source-form))
                      (map (fn [_]
                             (throw (ex-info "boom during realization" {})))
                           [::explode])
                      (real-check-resolved-form dict ns-sym source-file source-form analyzed opts)))]
      (let [results (checking/check-namespace {:remove-context true}
                                              'skeptic.check-project-best-effort-examples
                                              source-file)
            exception-result (some #(when (= :expression (:phase %)) %) results)
            mismatch-result (some #(when (= 'skeptic.check-project-best-effort-examples/later-mismatch
                                            (:enclosing-form %))
                                     %)
                                  results)]
        (is (some? exception-result)
            "The exploding form should produce a localized expression exception")
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (= 'skeptic.check-project-best-effort-examples/exploding-form
               (:enclosing-form exception-result)))
        (is (some? mismatch-result)
            "The later mismatch should still be found after the exception")
        (is (seq (:errors mismatch-result))
            "The later mismatch should have actual errors")))))
