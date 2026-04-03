(ns skeptic.inconsistence.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema-base :as sb]
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

(deftest cast-report-basic-failures-test
  (let [success (sut/cast-report sample-ctx s/Int s/Int)
        nullable (sut/cast-report sample-ctx s/Int (s/maybe s/Int))
        mismatch (sut/cast-report sample-ctx s/Int s/Str)]
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
    (is (= (ab/schema->type s/Int) (:expected-type mismatch)))
    (is (= (ab/schema->type s/Str) (:actual-type mismatch)))
    (is (str/includes? (first (:errors mismatch)) "mismatched type"))))

(deftest output-cast-report-renders-canonical-output-test
  (let [report (sut/output-cast-report
                {:expr 'bad-user
                 :arg '{:name :bad
                        :nickname "x"}}
                {:name s/Str
                 :nickname (s/maybe s/Str)}
                {:name s/Keyword
                 :nickname (s/maybe s/Str)})
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
                {:user {:name s/Str}}
                {:user {:name s/Keyword}})
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

(deftest output-summary-falls-back-to-top-level-when-no-actionable-leaf-details
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame 'bad-user
                  :focuses ['bad-user]
                  :cast-result {:rule :source-union
                                :source-type (ab/schema->type (sb/join s/Any s/Keyword))
                                :target-type (ab/schema->type s/Int)}
                  :cast-results [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :source-type (ab/schema->type s/Any)
                                  :target-type (ab/schema->type s/Int)
                                  :path []}]})
        [error] (:errors summary)]
    (is (str/includes? error "has output schema:"))
    (is (str/includes? error "Problem fields:"))
    (is (str/includes? error "Any"))
    (assert-no-ui-internals error)))

(deftest nested-dynamic-map-cast-stays-structural-test
  (let [compatible (sut/output-cast-report sample-ctx {:a s/Int} {:a s/Any})
        incompatible (sut/output-cast-report sample-ctx {:a s/Int} {:b s/Any})]
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
                                        {:a s/Int
                                         :b s/Int}
                                        {s/Keyword s/Int})
        successful-cast (as/check-cast {s/Keyword s/Int}
                                       {:a s/Int
                                        s/Keyword s/Int})]
    (is (not (:ok? failing-report)))
    (is (some #(= :map-key-domain-not-covered (:reason %))
              (:cast-results failing-report)))
    (run! assert-no-ui-internals (:errors failing-report))

    (is (:ok? successful-cast))
    (is (= :map (:rule successful-cast)))))

(deftest semantic-tamper-message-test
  (let [type-var (at/->TypeVarT 'X)
        sealed (at/->SealedDynT type-var)
        inspect-message (sut/cast-result->message sample-ctx
                                                  {:source-type sealed
                                                   :target-type (ab/schema->type s/Int)
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
    (is (:ok? (sut/output-cast-report sample-ctx schema s/Int)))
    (is (:ok? (sut/output-cast-report sample-ctx schema s/Str)))
    (is (not (:ok? (sut/output-cast-report sample-ctx schema s/Keyword))))))

(deftest both-schema-output-cast-report-test
  (is (:ok? (sut/output-cast-report sample-ctx both-any-int s/Int)))
  (is (:ok? (sut/output-cast-report sample-ctx both-int-and-constrained-int s/Int)))
  (is (not (:ok? (sut/output-cast-report sample-ctx both-any-int s/Str))))
  (is (not (:ok? (sut/output-cast-report sample-ctx both-int-str s/Int))))
  (is (not (:ok? (sut/output-cast-report sample-ctx both-int-str s/Str))))
  (is (not (:ok? (sut/output-cast-report sample-ctx both-int-str s/Keyword))))
  (is (not (:ok? (sut/output-cast-report sample-ctx {:value both-any-int}
                                         {:value s/Str})))))

(deftest constrained-and-eq-compatibility-test
  (let [non-negative-int (s/constrained s/Int (fn [n] (not (neg? n))))
        hello (s/eq "hello")]
    (is (:ok? (as/check-cast s/Int non-negative-int)))
    (is (:ok? (sut/output-cast-report sample-ctx non-negative-int s/Int)))
    (is (not (:ok? (as/check-cast s/Str non-negative-int))))
    (is (not (:ok? (as/check-cast (s/eq -1) non-negative-int))))

    (is (:ok? (as/check-cast s/Str hello)))
    (is (:ok? (sut/output-cast-report sample-ctx hello s/Str)))
    (is (not (:ok? (as/check-cast s/Int hello))))
    (is (not (:ok? (sut/output-cast-report sample-ctx hello s/Int))))
    (is (not (:ok? (as/check-cast (s/eq "bye") hello))))))

(deftest enum-compatibility-test
  (let [hello-or-bye (s/enum "hello" "bye")
        hello-or-one (s/enum "hello" 1)]
    (is (:ok? (as/check-cast s/Str hello-or-bye)))
    (is (:ok? (sut/output-cast-report sample-ctx hello-or-bye s/Str)))
    (is (not (:ok? (as/check-cast s/Int hello-or-bye))))
    (is (not (:ok? (sut/output-cast-report sample-ctx hello-or-bye s/Int))))

    (is (:ok? (as/check-cast s/Str hello-or-one)))
    (is (:ok? (as/check-cast s/Int hello-or-one)))
    (is (not (:ok? (as/check-cast s/Bool hello-or-one))))

    (is (not (:ok? (as/check-cast hello-or-one s/Str))))
    (is (not (:ok? (sut/output-cast-report sample-ctx s/Str hello-or-one))))
    (is (:ok? (as/check-cast hello-or-bye s/Str)))
    (is (:ok? (sut/output-cast-report sample-ctx s/Str hello-or-bye)))))
