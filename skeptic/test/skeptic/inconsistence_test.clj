(ns skeptic.inconsistence-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence :as sut]))

(def sample-ctx
  {:expr '(f x 2)
   :arg 'x})

(defn schema-or-value
  [schema value]
  (sb/valued-schema schema value))

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

(deftest nested-map-cast-report-includes-field-path-test
  (let [report (sut/cast-report sample-ctx
                                {:user {:name s/Str}}
                                {:user {:name s/Keyword}})
        leaf (first (:cast-results report))
        error (first (:errors report))]
    (is (not (:ok? report)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[:user :name]"))))

(deftest map-cast-report-key-errors-test
  (let [missing (sut/cast-report {:expr '{:a 1}
                                  :arg '{:a 1}}
                                 {:a s/Int
                                  :b s/Int}
                                 {:a s/Int})
        unexpected (sut/cast-report {:expr '{:a 1 :c 2}
                                     :arg '{:a 1 :c 2}}
                                    {:a s/Int}
                                    {:a s/Int
                                     :c s/Int})]
    (is (not (:ok? missing)))
    (is (some #(= :missing-key (:reason %)) (:cast-results missing)))
    (is (some #(str/includes? % "[:b] is missing") (:errors missing)))

    (is (not (:ok? unexpected)))
    (is (some #(= :unexpected-key (:reason %)) (:cast-results unexpected)))
    (is (some #(str/includes? % "[:c] is not allowed by the expected schema") (:errors unexpected)))
    (run! assert-no-ui-internals (concat (:errors missing)
                                         (:errors unexpected)))))

(deftest nested-map-key-errors-include-full-path-test
  (let [missing (sut/cast-report sample-ctx
                                 {:user {:name s/Str}}
                                 {:user {}})
        unexpected (sut/cast-report sample-ctx
                                    {:user {:name s/Str}}
                                    {:user {:name s/Str
                                            :age s/Int}})
        nullable (sut/cast-report sample-ctx
                                  {:user {:name s/Str}}
                                  {:user {(s/optional-key :name) s/Str}})]
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> missing :cast-results first :path)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :age}]
           (-> unexpected :cast-results first :path)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> nullable :cast-results first :path)))
    (is (some #(str/includes? % "[:user :name]") (:errors missing)))
    (is (some #(str/includes? % "[:user :age]") (:errors unexpected)))
    (is (some #(str/includes? % "[:user :name]") (:errors nullable)))
    (run! assert-no-ui-internals (concat (:errors missing)
                                         (:errors unexpected)
                                         (:errors nullable)))))

(deftest non-literal-map-key-path-stops-at-last-code-segment-test
  (let [report (sut/cast-report sample-ctx
                                {:account {:state {s/Keyword s/Int}}}
                                {:account {:state {:name s/Str}}})
        [leaf] (:cast-results report)
        [error] (:errors report)]
    (is (not (:ok? report)))
    (is (= [{:kind :map-key :key :account}
            {:kind :map-key :key :state}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[:account :state]"))
    (is (not (str/includes? error "[:account :state :name]")))
    (assert-no-ui-internals error)))

(deftest map-nullable-key-message-test
  (let [message (sut/cast-result->message
                 {:expr '{:a 1}
                  :arg '{:a 1}}
                 {:source-type (ab/schema->type {(s/optional-key :a) s/Int})
                  :target-type (ab/schema->type {:a s/Int})
                  :rule :map-nullable-key
                  :reason :nullable-key
                  :actual-key (s/optional-key :a)
                  :expected-key :a})]
    (is (str/includes? message "potentially nullable"))))

(deftest vector-cast-report-includes-index-path-test
  (let [report (sut/cast-report sample-ctx
                                [s/Int s/Int]
                                [s/Int s/Str])
        leaf (first (:cast-results report))
        error (first (:errors report))]
    (is (not (:ok? report)))
    (is (= [{:kind :vector-index :index 1}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[1]"))))

(deftest rendered-path-hides-internal-cast-branches
  (let [message (sut/cast-result->message
                 sample-ctx
                 {:source-type (ab/schema->type s/Int)
                  :target-type (ab/schema->type s/Str)
                  :rule :leaf-overlap
                  :reason :leaf-mismatch
                  :path [{:kind :source-union-branch :index 1}
                         {:kind :map-key :key :name}]})]
    (is (str/includes? message "[:name]"))
    (assert-no-ui-internals message)))

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

(deftest placeholder-heavy-output-summary-renders-public-names-only
  (let [placeholder (at/->PlaceholderT 'clj-threals.threals/Threal)
        message (sut/mismatched-output-schema-msg
                 {:expr 'for-birthday
                  :arg '[g r b]}
                 (at/->VectorT [(at/->SetT #{(at/->VectorT [placeholder placeholder placeholder]
                                                      false)}
                                            false)]
                               true)
                 [s/Any])]
    (is (str/includes? message "Threal"))
    (assert-no-ui-internals message)))

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

(deftest directional-cast-kernel-test
  (let [exact (as/check-cast s/Int s/Int)
        target-dyn (as/check-cast s/Int s/Any)
        nested-dyn (as/check-cast {:a s/Any} {:a s/Int})
        target-union (as/check-cast s/Int (s/either s/Int s/Str))
        source-union (as/check-cast (s/either s/Int s/Str) s/Int)
        nilability (as/check-cast (s/maybe s/Any) s/Int)
        domain-failure (as/check-cast (s/=> s/Int s/Int)
                                      (s/=> s/Int s/Str))
        domain-child (first (-> domain-failure :children first :children))
        range-failure (as/check-cast (s/=> s/Str s/Int)
                                     (s/=> s/Int s/Int))
        range-child (last (-> range-failure :children first :children))
        target-intersection (as/check-cast s/Int (s/both s/Any s/Int))]
    (is (:ok? exact))
    (is (= :exact (:rule exact)))

    (is (:ok? target-dyn))
    (is (= :target-dyn (:rule target-dyn)))

    (is (:ok? nested-dyn))
    (is (= :map (:rule nested-dyn)))
    (is (= :residual-dynamic (-> nested-dyn :children first :rule)))

    (is (:ok? target-union))
    (is (= :target-union (:rule target-union)))

    (is (not (:ok? source-union)))
    (is (= :source-union (:rule source-union)))

    (is (not (:ok? nilability)))
    (is (= :maybe-source (:rule nilability)))

    (is (not (:ok? domain-failure)))
    (is (= :negative (:blame-polarity domain-child)))

    (is (not (:ok? range-failure)))
    (is (= :positive (:blame-polarity range-child)))

    (is (:ok? target-intersection))
    (is (= :target-intersection (:rule target-intersection)))))

(deftest semantic-function-type-rendering-test
  (let [fun-type (at/->FunT [(at/->FnMethodT [(ab/schema->type s/Int)]
                                             (ab/intersection-type [s/Any s/Int])
                                             1
                                             false)])
        polymorphic-fun (at/->FunT [(at/->FnMethodT [(at/->TypeVarT 'X)]
                                                    (at/->SealedDynT (at/->TypeVarT 'X))
                                                    1
                                                    false)])]
    (is (= fun-type (ab/schema->type fun-type)))
    (is (= "(=> (intersection Any Int) Int)"
           (abr/render-type fun-type)))
    (is (= "(=> (sealed X) X)"
           (abr/render-type polymorphic-fun)))))

(deftest valued-helper-logic-lives-in-analysis-schema-test
  (is (= 1 (as/get-by-matching-schema {s/Symbol 1} clojure.lang.Symbol)))
  (is (= 2 (as/get-by-matching-schema {s/Int 2} java.lang.Integer)))

  (is (= 1 (as/valued-get {:a 1 :b 2} :a)))
  (is (= 1 (as/valued-get {:a 1 :b 2} (schema-or-value s/Keyword :a))))
  (is (= 2 (as/valued-get {:a 1 s/Keyword 2} (schema-or-value s/Keyword :b))))

  (is (as/valued-compatible? {s/Keyword s/Int :b s/Str} {:a 1 :b "x"}))
  (is (not (as/valued-compatible? {s/Keyword s/Int :b s/Str} {:b 1 :a "x"})))
  (is (as/valued-compatible? {:name s/Str
                              :schema (s/maybe s/Any)}
                             {(schema-or-value s/Keyword :name) (schema-or-value s/Str "x")
                              (schema-or-value s/Keyword :schema) (schema-or-value s/Int 1)}))

  (is (as/matches-map {s/Keyword s/Str :b 2} :a "x"))
  (is (not (as/matches-map {s/Keyword s/Str :b 2} :a 1))))

(deftest mixed-map-schema-requiredness-regression-test
  (let [schema {:a s/Int :b s/Str s/Keyword s/Any}
        valid {:a 1 :b "hello"}
        valid-with-extra {:a 1 :b "hello" :c 5}
        missing-required {:a 1}
        cast-result (as/check-cast {:a s/Int :b s/Str} schema)]
    (is (nil? (s/check schema valid)))
    (is (nil? (s/check schema valid-with-extra)))
    (is (some? (s/check schema missing-required)))

    (is (asv/value-satisfies-type? valid schema))
    (is (asv/value-satisfies-type? valid-with-extra schema))
    (is (not (asv/value-satisfies-type? missing-required schema)))

    (is (:ok? cast-result))
    (is (= :map (:rule cast-result)))))

(deftest extra-schema-row-does-not-imply-required-presence-regression-test
  (let [schema {s/Keyword s/Int}
        empty-value {}
        cast-result (as/check-cast empty-value schema)]
    (is (nil? (s/check schema empty-value)))
    (is (asv/value-satisfies-type? empty-value schema))
    (is (:ok? cast-result))
    (is (= :map (:rule cast-result)))))

(deftest pattern-map-key-presence-classification-regression-test
  (is (= :unknown
         (asv/contains-key-classification {s/Keyword s/Any} :a)))
  (is (= :always
         (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :a)))
  (is (= :unknown
         (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :b))))

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

(deftest unknown-output-schema-test
  (is (sut/unknown-output-schema? s/Any))
  (is (sut/unknown-output-schema? (s/maybe s/Any)))
  (is (sut/unknown-output-schema? (sb/placeholder-schema [:output 'example/f])))
  (is (not (sut/unknown-output-schema? s/Int))))

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
