(ns skeptic.inconsistence.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema.cast :as as]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.report :as sut]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

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

(defn T
  [schema]
  (ab/schema->type tp schema))

(def HasA
  {(s/required-key :a) s/Int})

(def HasB
  {(s/required-key :b) s/Str})

(def HasAOrB
  (s/conditional #(contains? % :a) HasA
                 #(contains? % :b) HasB))

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
    (is (str/includes? error "declared return type"))
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
           (-> report :cast-diagnostics first :path)))
    (is (= 1 (count (:errors report))))
    (is (str/includes? error "declared return type"))
    (is (str/includes? error "{:user {:name Keyword}}"))
    (is (str/includes? error "{:user {:name Str}}"))
    (is (not (str/includes? error "Problem fields:")))
    (assert-no-ui-internals error)))

(deftest cast-report-prefers-source-aggregate-mismatch-over-branch-detail
  (let [report (sut/cast-report sample-ctx (T HasA) (T HasAOrB))
        [error] (:errors report)
        text (strip-ansi error)]
    (is (not (:ok? report)))
    (is (= :source-union (:rule report)))
    (is (= 1 (count (:cast-diagnostics report))))
    (is (= :source-union (-> report :cast-diagnostics first :rule)))
    (is (= [] (-> report :cast-diagnostics first :path)))
    (is (str/includes? text "has inferred type incompatible with the expected type"))
    (is (str/includes? text "(conditional"))
    (is (str/includes? text "{:a Int}"))
    (is (str/includes? text "{:b Str}"))
    (is (not (str/includes? text "is missing")))
    (is (not (str/includes? text "not allowed by the expected type")))
    (assert-no-ui-internals text)))

(deftest output-summary-declared-type-shows-full-conditional-union
  (let [bad (sut/output-cast-report sample-ctx (T s/Keyword) (T conditional-int-or-str))
        summary (sut/report-summary
                 (merge {:report-kind :output
                         :blame :bad}
                        (select-keys bad [:cast-summary :cast-diagnostics :expected-type :actual-type])))
        [err] (:errors summary)
        text (strip-ansi err)]
    (is (str/includes? text "Declared return type expects:"))
    (is (str/includes? text "Problem fields:"))
    (is (str/includes? text "(conditional Int Str)"))
    (is (str/includes? text "but expected Keyword"))
    (is (not (str/includes? text "does not match any of:")))
    (is (str/includes? text "Int"))
    (is (str/includes? text "Str"))
    (assert-no-ui-internals text)))

(deftest cast-report-keeps-narrower-source-to-wider-target-as-success
  (let [report (sut/cast-report sample-ctx (T HasAOrB) (T HasA))]
    (is (:ok? report))
    (is (= [] (:errors report)))))

(deftest output-report-summary-uses-root-expected-type-metadata
  (let [bad (sut/output-cast-report sample-ctx (T s/Keyword) (T conditional-int-or-str))
        summary (sut/report-summary
                 (merge {:report-kind :output
                         :blame :bad}
                        (select-keys bad [:cast-summary :cast-diagnostics :expected-type :actual-type])))
        root-target (:expected-type (:cast-summary bad))]
    (is (= root-target (:expected-type summary)))))

(deftest output-summary-uses-visible-path-as-headline-focus
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame '{:name :bad
                           :nickname "x"}
                  :focuses ['{:name :bad
                              :nickname "x"}]
                  :cast-summary {:rule :map
                                 :actual-type (T {:name s/Keyword
                                                  :nickname (s/maybe s/Str)})
                                 :expected-type (T {:name s/Str
                                                    :nickname (s/maybe s/Str)})}
                  :cast-diagnostics [{:reason :leaf-mismatch
                                      :rule :leaf-overlap
                                      :actual-type (T s/Keyword)
                                      :expected-type (T s/Str)
                                      :blame-side :term
                                      :blame-polarity :positive
                                      :path [{:kind :map-key :key :name}]}]})
        [error] (:errors summary)
        text (strip-ansi error)]
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \"x\"\}" text))
    (is (str/includes? text "Declared return type expects:"))
    (is (str/includes? text "[:name] has Keyword but expected Str"))))

(deftest exception-summary-is-clear-and-user-facing
  (let [summary (sut/report-summary
                 {:report-kind :exception
                  :phase :declaration
                  :blame 'example/bad
                  :exception-class 'clojure.lang.ExceptionInfo
                  :declaration-slot :output
                  :rejected-schema #"^[a-z]+$"
                  :exception-message "boom during analysis"})
        [error] (:errors summary)]
    (is (= 1 (count (:errors summary))))
    (is (str/includes? error "Skeptic hit an exception while checking"))
    (is (str/includes? error "boom during analysis"))
    (is (str/includes? error "Exception class: clojure.lang.ExceptionInfo"))
    (is (str/includes? error "Declaration slot: :output"))
    (is (str/includes? error "Rejected schema: #\"^[a-z]+$\""))
    (is (str/includes? error "continued with the rest of the namespace"))))

(deftest output-summary-omits-redundant-in-when-focus-equals-expression
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame '(get counts :count "zero")
                  :focuses ['(get counts :count "zero")]
                  :cast-summary {:rule :source-union
                                 :actual-type (ato/union-type tp [(T s/Int) (T s/Str)])
                                 :expected-type (T s/Int)}
                  :cast-diagnostics [{:reason :source-branch-failed
                                      :rule :source-union
                                      :actual-type (ato/union-type tp [(T s/Int) (T s/Str)])
                                      :expected-type (T s/Int)
                                      :blame-side :term
                                      :blame-polarity :positive
                                      :path []}]})
        [error] (:errors summary)
        text (strip-ansi error)]
    (is (re-find #"(?s)^\(get counts :count \"zero\"\)\s+has an output mismatch against the declared return type\." text))
    (is (not (re-find #"(?s)^\(get counts :count \"zero\"\)\s+\tin\s+\(get counts :count \"zero\"\)" text)))
    (is (str/includes? text "(union Int Str) but expected Int"))))

(deftest output-summary-falls-back-to-top-level-when-no-actionable-leaf-details
  (let [summary (sut/report-summary
                 {:report-kind :output
                  :blame 'bad-user
                  :focuses ['bad-user]
                  :cast-summary {:rule :source-union
                                 :actual-type (ab/schema->type tp (sb/join s/Any s/Keyword))
                                 :expected-type (T s/Int)}
                  :cast-diagnostics [{:reason :leaf-mismatch
                                      :rule :leaf-overlap
                                      :actual-type (T s/Any)
                                      :expected-type (T s/Int)
                                      :blame-side :term
                                      :blame-polarity :positive
                                      :path []}]})
        [error] (:errors summary)]
    (is (str/includes? error "has inferred output type:"))
    (is (str/includes? error "Problem fields:"))
    (is (str/includes? error "Any"))
    (assert-no-ui-internals error)))

(deftest nested-dynamic-map-cast-stays-structural-test
  (let [compatible (sut/output-cast-report sample-ctx (T {:a s/Int}) (T {:a s/Any}))
        incompatible (sut/output-cast-report sample-ctx (T {:a s/Int}) (T {:b s/Any}))]
    (is (:ok? compatible))
    (is (= :map (:rule compatible)))
    (is (= :map (:rule (:cast-summary compatible))))

    (is (not (:ok? incompatible)))
    (is (= :map (:rule (:cast-summary incompatible))))
    (is (some #(= :missing-key (:reason %)) (:cast-diagnostics incompatible)))
    (is (some #(= :unexpected-key (:reason %)) (:cast-diagnostics incompatible)))))

(deftest broad-key-map-cast-regression-test
  (let [failing-report (sut/cast-report sample-ctx
                                        (T {:a s/Int
                                            :b s/Int})
                                        (T {s/Keyword s/Int}))
        successful-cast (as/check-cast tp {s/Keyword s/Int}
                                       {:a s/Int
                                        s/Keyword s/Int})]
    (is (not (:ok? failing-report)))
    (is (some #(= :map-key-domain-not-covered (:reason %))
              (:cast-diagnostics failing-report)))
    (run! assert-no-ui-internals (:errors failing-report))

    (is (:ok? successful-cast))
    (is (= :map (:rule successful-cast)))))

(deftest input-summary-uses-single-focused-arg
  (let [summary (sut/report-summary
                 {:report-kind :input
                  :blame '(int-add y nil)
                  :focuses [nil]
                  :cast-diagnostics [{:reason :nullable-source
                                      :rule :leaf-overlap
                                      :actual-type (T (s/maybe s/Any))
                                      :expected-type (T s/Int)
                                      :blame-side :term
                                      :blame-polarity :positive
                                      :path []}]})
        [error] (:errors summary)]
    (is (= 1 (count (:errors summary))))
    (is (re-find #"(?s)^nil\s+\tin\s+\(int-add y nil\)\s+has inferred type incompatible with the expected type:" (strip-ansi error)))
    (is (str/includes? (strip-ansi error) "a nullable value was provided where the type requires a non-null value"))
    (is (not (re-find #"(?s)^\(int-add y nil\)\s+\tin\s+\(int-add y nil\)" (strip-ansi error))))))

(deftest input-summary-uses-blame-for-multiple-focused-args
  (let [summary (sut/report-summary
                 {:report-kind :input
                  :blame '(int-add x y nil)
                  :focuses ['y nil]
                  :cast-diagnostics [{:reason :nullable-source
                                      :rule :leaf-overlap
                                      :actual-type (T (s/maybe s/Any))
                                      :expected-type (T s/Int)
                                      :blame-side :term
                                      :blame-polarity :positive
                                      :path []}]})
        [error] (:errors summary)]
    (is (= 1 (count (:errors summary))))
    (is (re-find #"(?s)^\(int-add x y nil\)\s+\tin\s+\(int-add x y nil\)\s+has inferred type incompatible with the expected type:" (strip-ansi error)))
    (is (str/includes? (strip-ansi error) "a nullable value was provided where the type requires a non-null value"))))

(deftest semantic-tamper-message-test
  (let [type-var (at/->TypeVarT tp 'X)
        sealed (at/->SealedDynT tp type-var)
        inspect-message (sut/cast-result->message sample-ctx
                                                  {:actual-type sealed
                                                   :expected-type (T s/Int)
                                                   :rule :is-tamper
                                                   :reason :is-tamper
                                                   :path []
                                                   :blame-side :term
                                                   :blame-polarity :positive})
        escape-message (sut/cast-result->message sample-ctx
                                                 {:actual-type sealed
                                                  :expected-type type-var
                                                  :rule :nu-tamper
                                                  :reason :nu-tamper
                                                  :path []
                                                  :blame-side :term
                                                  :blame-polarity :positive})]
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
    (is (:ok? (as/check-cast tp s/Int non-negative-int)))
    (is (:ok? (sut/output-cast-report sample-ctx (T non-negative-int) (T s/Int))))
    (is (not (:ok? (as/check-cast tp s/Str non-negative-int))))
    (is (not (:ok? (as/check-cast tp (s/eq -1) non-negative-int))))

    (is (:ok? (as/check-cast tp s/Str hello)))
    (is (:ok? (sut/output-cast-report sample-ctx (T hello) (T s/Str))))
    (is (not (:ok? (as/check-cast tp s/Int hello))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T hello) (T s/Int)))))
    (is (not (:ok? (as/check-cast tp (s/eq "bye") hello))))))

(deftest enum-compatibility-test
  (let [hello-or-bye (s/enum "hello" "bye")
        hello-or-one (s/enum "hello" 1)]
    (is (:ok? (as/check-cast tp s/Str hello-or-bye)))
    (is (:ok? (sut/output-cast-report sample-ctx (T hello-or-bye) (T s/Str))))
    (is (not (:ok? (as/check-cast tp s/Int hello-or-bye))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T hello-or-bye) (T s/Int)))))

    (is (:ok? (as/check-cast tp s/Str hello-or-one)))
    (is (:ok? (as/check-cast tp s/Int hello-or-one)))
    (is (not (:ok? (as/check-cast tp s/Bool hello-or-one))))

    (is (not (:ok? (as/check-cast tp hello-or-one s/Str))))
    (is (not (:ok? (sut/output-cast-report sample-ctx (T s/Str) (T hello-or-one)))))
    (is (:ok? (as/check-cast tp hello-or-bye s/Str)))
    (is (:ok? (sut/output-cast-report sample-ctx (T s/Str) (T hello-or-bye))))))

(deftest report-summary-honours-fold-options
  (let [schema-prov (prov/make-provenance :schema 'foo/NamedVec 'foo.ns nil)
        inferred-prov (prov/make-provenance :inferred 'foo.caller/f 'foo.caller nil)
        named-vec (at/->VectorT schema-prov [(at/->GroundT schema-prov :int 'Int)] false)
        kw-key (at/->ValueT inferred-prov (at/->GroundT inferred-prov :keyword 'Keyword) :result)
        outer-map (at/->MapT inferred-prov {kw-key named-vec})
        actual-type (at/->GroundT inferred-prov :keyword 'Keyword)
        report {:report-kind :output
                :blame 'bad-user
                :focuses ['bad-user]
                :cast-summary {:rule :leaf-overlap
                               :actual-type actual-type
                               :expected-type outer-map}
                :cast-diagnostics [{:reason :leaf-mismatch
                                    :rule :leaf-overlap
                                    :actual-type actual-type
                                    :expected-type outer-map
                                    :blame-side :term
                                    :blame-polarity :positive
                                    :path []}]}
        folded (-> (sut/report-summary report {})
                   :errors
                   first
                   strip-ansi)
        full (-> (sut/report-summary report {:explain-full true})
                 :errors
                 first
                 strip-ansi)]
    (is (str/includes? folded "foo/NamedVec"))
    (is (str/includes? full "[Int]"))))
