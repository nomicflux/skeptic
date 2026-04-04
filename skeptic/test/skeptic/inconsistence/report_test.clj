(ns skeptic.inconsistence.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.report :as sut]))

(def sample-ctx
  {:expr '(f x 2)
   :arg 'x})

(def conditional-int-or-str
  (s/conditional integer? s/Int string? s/Str))

(def cond-pre-int-or-str
  (s/cond-pre s/Int s/Str))

(def either-int-or-str
  (s/either s/Int s/Str))

(def if-int-or-str
  (s/if integer? s/Int s/Str))

(def both-any-int
  (s/both s/Any s/Int))

(def both-int-str
  (s/both s/Int s/Str))

(def both-int-and-constrained-int
  (s/both s/Int (s/constrained s/Int pos?)))

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

(defn strip-ansi
  [text]
  (str/replace (str text) #"\u001B\[[0-9;]*m" ""))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest cast-report-basic-failures-test
  (let [success (sut/cast-report sample-ctx (T s/Int) (T s/Int))
        nullable (sut/cast-report sample-ctx (T s/Int) (T (s/maybe s/Int)))
        mismatch (sut/cast-report sample-ctx (T s/Int) (T s/Str))]
    (is (:ok? success))
    (is (= [] (:errors success)))
    (is (= :exact (:rule success)))

    (is (not (:ok? nullable)))
    (is (= :maybe-source (:rule nullable)))
    (is (= :term (:blame-side nullable)))
    (is (= :positive (:blame-polarity nullable)))
    (is (str/includes? (first (:errors nullable)) "nullable"))

    (is (not (:ok? mismatch)))
    (is (= :leaf-overlap (:rule mismatch)))
    (is (= :term (:blame-side mismatch)))
    (is (= :positive (:blame-polarity mismatch)))
    (is (= (T s/Int) (:expected-type mismatch)))
    (is (= (T s/Str) (:actual-type mismatch)))
    (is (str/includes? (first (:errors mismatch)) "mismatched type"))))

(deftest output-cast-report-renders-canonical-output-test
  (let [report (sut/output-cast-report
                {:expr 'bad-user
                 :arg '{:name :bad
                        :nickname "x"}}
                (T {:name s/Str
                    :nickname (s/maybe s/Str)})
                (T {:name s/Keyword
                    :nickname (s/maybe s/Str)}))
        error (first (:errors report))]
    (is (not (:ok? report)))
    (is (= :term (:blame-side report)))
    (is (= :positive (:blame-polarity report)))
    (is (= :leaf-overlap (:rule report)))
    (is (str/includes? error "declared return schema"))
    (is (str/includes? error "{:name Keyword, :nickname (maybe Str)}"))
    (is (str/includes? error "{:name Str, :nickname (maybe Str)}"))))

(deftest nested-output-cast-report-includes-summary-and-path-details-test
  (let [report (sut/output-cast-report
                {:expr 'bad-user
                 :arg '{:user {:name :bad}}}
                (T {:user {:name s/Str}})
                (T {:user {:name s/Keyword}}))
        [error] (:errors report)]
    (is (not (:ok? report)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> report :cast-results first :path)))
    (is (= 1 (count (:errors report))))
    (is (str/includes? error "declared return schema"))
    (is (str/includes? error "{:user {:name Keyword}}"))
    (is (str/includes? error "{:user {:name Str}}"))
    (is (not (str/includes? error "Problem fields:")))
    (assert-no-ui-internals error)))

(deftest output-summary-prefers-leaf-mismatch-over-source-union-headline
  (let [placeholder (at/->PlaceholderT 'clj-threals.threals/Threal)
        triple (at/->VectorT [placeholder placeholder placeholder] false)
        slot (at/->SetT #{triple} false)
        expected-result (at/->VectorT [slot slot slot] false)
        actual-result (at/->SetT #{expected-result} false)
        summary (sut/report-summary
                 {:report-kind :output
                  :blame 'add-with-cache
                  :focuses ['(let [result (simplify gt_fn [g r b])]
                               {:result result})]
                  :cast-result {:rule :source-union
                                :source-type (at/->UnionT #{(ab/schema->type {:result s/Any
                                                                              :cache s/Any})
                                                            (ato/normalize-type {:result actual-result
                                                                                 :cache s/Any})})
                                :target-type (ato/normalize-type {:result expected-result
                                                                  :cache s/Any})}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type actual-result
                                  :target-type expected-result
                                  :path [{:kind :source-union-branch :index 1}
                                         {:kind :map-key :key :result}]}]})
        [error] (:errors summary)
        declared-block (second (re-find #"(?s)Declared return schema:\n\n(.*?)\n\nProblem fields:" error))]
    (is (= 1 (count (:errors summary))))
    (is (str/includes? error "declared return schema"))
    (is (str/includes? error "Problem fields:"))
    (is (str/includes? error "[:result] has:"))
    (is (not (str/includes? error "[:result] has #{")))
    (is (str/includes? declared-block "\n"))
    (is (not (str/includes? error "has output schema:")))
    (is (not (str/includes? error "(union")))
    (assert-no-ui-internals error)))

(deftest output-summary-uses-visible-path-as-headline-focus
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame '{:name :bad
                           :nickname "x"}
                  :focuses ['{:name :bad
                              :nickname "x"}]
                  :cast-result {:rule :map
                                :source-type (T {:name s/Keyword
                                                 :nickname (s/maybe s/Str)})
                                :target-type (T {:name s/Str
                                                 :nickname (s/maybe s/Str)})}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type (T s/Keyword)
                                  :target-type (T s/Str)
                                  :path [{:kind :map-key :key :name}]}]})
        [error] (:errors summary)
        text (strip-ansi error)]
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \"x\"\}" text))
    (is (str/includes? text "Declared return schema:"))
    (is (str/includes? text "[:name] has Keyword but expected Str"))))

(deftest output-summary-omits-redundant-in-when-focus-equals-expression
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame '(get counts :count "zero")
                  :focuses ['(get counts :count "zero")]
                  :cast-result {:rule :source-union
                                :source-type (ato/union-type [(T s/Int) (T s/Str)])
                                :target-type (T s/Int)}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type (T s/Str)
                                  :target-type (T s/Int)
                                  :path []}]})
        [error] (:errors summary)
        text (strip-ansi error)]
    (is (re-find #"(?s)^\(get counts :count \"zero\"\)\s+has an output mismatch against the declared return schema\." text))
    (is (not (re-find #"(?s)^\(get counts :count \"zero\"\)\s+\tin\s+\(get counts :count \"zero\"\)" text)))
    (is (str/includes? text "Str but expected Int"))))

(deftest output-summary-falls-back-to-top-level-when-no-actionable-leaf-details
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame 'bad-user
                  :focuses ['bad-user]
                  :cast-result {:rule :source-union
                                :source-type (ab/schema->type (sb/join s/Any s/Keyword))
                                :target-type (T s/Int)}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type (T s/Any)
                                  :target-type (T s/Int)
                                  :path []}]})
        [error] (:errors summary)]
    (is (str/includes? error "has output schema:"))
    (is (str/includes? error "Problem fields:"))
    (is (str/includes? error "Any"))
    (assert-no-ui-internals error)))

(deftest nested-dynamic-map-cast-stays-structural-test
  (let [compatible (sut/output-cast-report sample-ctx (T {:a s/Int}) (T {:a s/Any}))
        incompatible (sut/output-cast-report sample-ctx (T {:a s/Int}) (T {:b s/Any}))]
    (is (:ok? compatible))
    (is (= :map (:rule compatible)))
    (is (= :map (:rule (:cast-result compatible))))
    (is (= :residual-dynamic (-> compatible :cast-result :children first :rule)))

    (is (not (:ok? incompatible)))
    (is (= :map (:rule (:cast-result incompatible))))
    (is (some #(= :missing-key (:reason %)) (:cast-results incompatible)))
    (is (some #(= :unexpected-key (:reason %)) (:cast-results incompatible)))))

(deftest broad-key-map-cast-regression-test
  (let [failing-report (sut/cast-report sample-ctx
                                        (T {:a s/Int
                                            :b s/Int})
                                        (T {s/Keyword s/Int}))
        successful-cast (as/check-cast {s/Keyword s/Int}
                                       {:a s/Int
                                        s/Keyword s/Int})]
    (is (not (:ok? failing-report)))
    (is (some #(= :map-key-domain-not-covered (:reason %))
              (:cast-results failing-report)))
    (run! assert-no-ui-internals (:errors failing-report))

    (is (:ok? successful-cast))
    (is (= :map (:rule successful-cast)))))

(deftest input-summary-uses-single-focused-arg
  (let [summary (sut/report-summary
                 {:report-kind :input
                  :blame '(int-add y nil)
                  :focuses [nil]
                  :cast-results [{:reason :nullable-source
                                  :source-type (T (s/maybe s/Any))
                                  :target-type (T s/Int)
                                  :path []}]})
        [error] (:errors summary)]
    (is (= 1 (count (:errors summary))))
    (is (re-find #"(?s)^nil\s+\tin\s+\(int-add y nil\)\s+has incompatible schema:" (strip-ansi error)))
    (is (str/includes? (strip-ansi error) "a nullable value was provided where the schema requires a non-null value"))
    (is (not (re-find #"(?s)^\(int-add y nil\)\s+\tin\s+\(int-add y nil\)" (strip-ansi error))))))

(deftest input-summary-uses-blame-for-multiple-focused-args
  (let [summary (sut/report-summary
                 {:report-kind :input
                  :blame '(int-add x y nil)
                  :focuses ['y nil]
                  :cast-results [{:reason :nullable-source
                                  :source-type (T (s/maybe s/Any))
                                  :target-type (T s/Int)
                                  :path []}]})
        [error] (:errors summary)]
    (is (= 1 (count (:errors summary))))
    (is (re-find #"(?s)^\(int-add x y nil\)\s+\tin\s+\(int-add x y nil\)\s+has incompatible schema:" (strip-ansi error)))
    (is (str/includes? (strip-ansi error) "a nullable value was provided where the schema requires a non-null value"))))

(deftest semantic-tamper-message-test
  (let [type-var (at/->TypeVarT 'X)
        sealed (at/->SealedDynT type-var)
        inspect-message (sut/cast-result->message sample-ctx
                                                  {:source-type sealed
                                                   :target-type (T s/Int)
                                                   :rule :is-tamper
                                                   :reason :is-tamper})
        escape-message (sut/cast-result->message sample-ctx
                                                 {:source-type sealed
                                                  :target-type type-var
                                                  :rule :nu-tamper
                                                  :reason :nu-tamper})]
    (is (str/includes? inspect-message "inspect a sealed value"))
    (is (str/includes? inspect-message "(sealed X)"))
    (is (str/includes? escape-message "move a sealed value out of scope"))
    (is (str/includes? escape-message "(sealed X)"))))

(deftest union-like-output-cast-report-test
  (doseq [schema [conditional-int-or-str
                  cond-pre-int-or-str
                  either-int-or-str
                  if-int-or-str]]
    (is (:ok? (sut/output-cast-report sample-ctx (T schema) (T s/Int))))
    (is (:ok? (sut/output-cast-report sample-ctx (T schema) (T s/Str))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T schema) (T s/Keyword)))))))

(deftest both-schema-output-cast-report-test
  (is (:ok? (sut/output-cast-report sample-ctx (T both-any-int) (T s/Int))))
  (is (:ok? (sut/output-cast-report sample-ctx (T both-int-and-constrained-int) (T s/Int))))
  (is (not (:ok? (sut/output-cast-report sample-ctx (T both-any-int) (T s/Str)))))
  (is (not (:ok? (sut/output-cast-report sample-ctx (T both-int-str) (T s/Int)))))
  (is (not (:ok? (sut/output-cast-report sample-ctx (T both-int-str) (T s/Str)))))
  (is (not (:ok? (sut/output-cast-report sample-ctx (T both-int-str) (T s/Keyword)))))
  (is (not (:ok? (sut/output-cast-report sample-ctx (T {:value both-any-int})
                                         (T {:value s/Str})))))) 

(deftest constrained-and-eq-compatibility-test
  (let [non-negative-int (s/constrained s/Int (fn [n] (not (neg? n))))
        hello (s/eq "hello")]
    (is (:ok? (as/check-cast s/Int non-negative-int)))
    (is (:ok? (sut/output-cast-report sample-ctx (T non-negative-int) (T s/Int))))
    (is (not (:ok? (as/check-cast s/Str non-negative-int))))
    (is (not (:ok? (as/check-cast (s/eq -1) non-negative-int))))

    (is (:ok? (as/check-cast s/Str hello)))
    (is (:ok? (sut/output-cast-report sample-ctx (T hello) (T s/Str))))
    (is (not (:ok? (as/check-cast s/Int hello))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T hello) (T s/Int)))))
    (is (not (:ok? (as/check-cast (s/eq "bye") hello))))))

(deftest enum-compatibility-test
  (let [hello-or-bye (s/enum "hello" "bye")
        hello-or-one (s/enum "hello" 1)]
    (is (:ok? (as/check-cast s/Str hello-or-bye)))
    (is (:ok? (sut/output-cast-report sample-ctx (T hello-or-bye) (T s/Str))))
    (is (not (:ok? (as/check-cast s/Int hello-or-bye))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T hello-or-bye) (T s/Int)))))

    (is (:ok? (as/check-cast s/Str hello-or-one)))
    (is (:ok? (as/check-cast s/Int hello-or-one)))
    (is (not (:ok? (as/check-cast s/Bool hello-or-one))))

    (is (not (:ok? (as/check-cast hello-or-one s/Str))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T s/Str) (T hello-or-one)))))
    (is (:ok? (as/check-cast hello-or-bye s/Str)))
    (is (:ok? (sut/output-cast-report sample-ctx (T s/Str) (T hello-or-bye))))))
