(ns skeptic.typed-decls.malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.schema.collect :as scollect]
            [skeptic.test-helpers :refer [is-type= tp]]
            [skeptic.typed-decls.malli :as tdm]))

(def ^:private Int (at/->GroundT tp :int 'Int))

(deftest desc->type-callable-returns-fun-type
  (let [t (tdm/desc->type tp {:name 'foo/bar
                              :malli-spec [:=> [:cat :int :int] :int]})]
    (is (at/fun-type? t))
    (is (= 1 (count (at/fun-methods t))))
    (is (= [Int Int] (at/fn-method-inputs (first (at/fun-methods t)))))
    (is (= Int (at/fn-method-output (first (at/fun-methods t)))))
    (is (= 2 (count (at/fn-method-input-names (first (at/fun-methods t))))))))

(deftest desc->type-non-callable-returns-ground-type
  (let [t (tdm/desc->type tp {:name 'foo/baz :malli-spec :int})]
    (is (= Int t))
    (is (not (at/fun-type? t)))))

(deftest desc->type-enum-returns-union-of-exact-values
  (let [t (tdm/desc->type tp {:name 'foo/e :malli-spec [:enum :a :b]})
        expected (ato/union-type tp [(ato/exact-value-type tp :a)
                                     (ato/exact-value-type tp :b)])]
    (is-type= expected t)))

(deftest desc->type-enum-in-=>-output
  (let [t (tdm/desc->type tp {:name 'foo/f :malli-spec [:=> [:cat :int] [:enum :ok :bad]]})
        expected (ato/union-type tp [(ato/exact-value-type tp :ok)
                                     (ato/exact-value-type tp :bad)])]
    (is (at/fun-type? t))
    (is-type= expected (at/fn-method-output (first (at/fun-methods t))))))

(deftest typed-ns-malli-results-entries
  (let [{:keys [dict errors]} (tdm/typed-ns-malli-results {} 'skeptic.test-examples.malli :clj)]
    (is (empty? errors))
    (is (contains? dict 'skeptic.test-examples.malli/demo-fn))
    (let [t (get dict 'skeptic.test-examples.malli/demo-fn)]
      (is (at/fun-type? t))
      (is-type= Int (at/fn-method-output (first (at/fun-methods t))))
      (let [inputs (at/fn-method-inputs (first (at/fun-methods t)))]
        (is (= 1 (count inputs)))
        (is-type= Int (first inputs))))))

(deftest malli-declaration-error-shape
  (testing "shared declaration-error-result phased for malli"
    (let [err (scollect/declaration-error-result
               :malli-declaration 'foo 'foo/bar nil (ex-info "boom" {}))]
      (is (= :malli-declaration (:phase err)))
      (is (= 'foo/bar (:blame err)))
      (is (= 'foo (:namespace err)))
      (is (= :exception (:report-kind err))))))

(def ^:private Str (at/->GroundT tp :str 'Str))

(defn- desc->type
  [malli-spec]
  (tdm/desc->type tp {:name 'foo/x :malli-spec malli-spec}))

(deftest desc->type-maybe
  (is-type= (at/->MaybeT tp Int) (desc->type [:maybe :int])))

(deftest desc->type-or
  (is-type= (ato/union-type tp [Int Str]) (desc->type [:or :int :string])))

(deftest desc->type-and
  (is-type= (ato/intersection-type tp [Int Str]) (desc->type [:and :int :string])))

(deftest desc->type-tuple
  (is-type= (at/->VectorT tp [Int Str] nil) (desc->type [:tuple :int :string])))

(deftest desc->type-eq-form
  (is-type= (ato/exact-value-type tp :hello) (desc->type [:= :hello])))

(deftest desc->type-map-closed-required-key
  (let [t (desc->type [:map {:closed true} [:x :int]])
        expected (at/->MapT tp {(ato/exact-value-type tp :x) Int})]
    (is-type= expected t)))

(deftest desc->type-map-closed-optional-key
  (let [t (desc->type [:map {:closed true} [:x {:optional true} :int]])
        expected (at/->MapT tp {(at/->OptionalKeyT tp (ato/exact-value-type tp :x)) Int})]
    (is-type= expected t)))

(deftest desc->type-map-open-default-adds-keyword-catch-all
  (let [t (desc->type [:map [:x :int]])]
    (is (at/map-type? t))
    (let [explicit-key (ato/exact-value-type tp :x)
          keyword-key  (at/->GroundT tp :keyword 'Keyword)]
      (is (contains? (:entries t) explicit-key))
      (is-type= Int (get (:entries t) explicit-key))
      (is (contains? (:entries t) keyword-key))
      (is (at/dyn-type? (get (:entries t) keyword-key))))))

(deftest desc->type-multi-keyword-dispatch
  (let [t (desc->type [:multi {:dispatch :tag}
                       [:a [:map {:closed true} [:tag [:= :a]] [:value :int]]]
                       [:b [:map {:closed true} [:tag [:= :b]] [:value :string]]]])]
    (is (at/conditional-type? t))
    (is (= 2 (count (:branches t))))
    (is (= [:a :b] (mapv :pred (:branches t))))))

(deftest desc->type-schema-with-registry
  (is-type= Int (desc->type [:schema {:registry {::int :int}} [:ref ::int]])))

(deftest desc->type-ref-cycle
  (let [t (desc->type [:schema {:registry {::ints [:maybe [:tuple :int [:ref ::ints]]]}}
                       [:ref ::ints]])]
    (is (at/maybe-type? t))))

(deftest desc->type-ref-miss-rejected-at-admission
  (is (thrown? Exception (desc->type [:schema {:registry {::other :int}} [:ref ::missing]]))
      "Malli rejects [:ref ::missing] at m/schema when ::missing is not in the active registry"))

(deftest desc->type-function-multi-arity
  (let [t (desc->type [:function
                       [:=> [:cat :int] :int]
                       [:=> [:cat :int :int] :int]])]
    (is (at/fun-type? t))
    (is (= 2 (count (at/fun-methods t))))
    (is (= [1 2] (mapv #(count (at/fn-method-inputs %)) (at/fun-methods t))))))

(deftest desc->type-vector
  (is-type= (at/->VectorT tp [] Int) (desc->type [:vector :int])))

(deftest desc->type-sequential
  (let [t (desc->type [:sequential :int])]
    (is (at/seq-type? t))))

(deftest desc->type-set
  (let [t (desc->type [:set :int])]
    (is (at/set-type? t))))

(deftest desc->type-primitive-leaves
  (doseq [[malli-leaf expected] [[:string Str]
                                  [:keyword (at/->GroundT tp :keyword 'Keyword)]
                                  [:symbol  (at/->GroundT tp :symbol 'Symbol)]
                                  [:boolean (at/->GroundT tp :bool 'Bool)]
                                  [:double  (at/->GroundT tp :double 'Double)]
                                  [:float   (at/->GroundT tp :float 'Float)]
                                  [:qualified-keyword (at/->GroundT tp :keyword 'Keyword)]
                                  [:qualified-symbol  (at/->GroundT tp :symbol 'Symbol)]]]
    (is-type= expected (desc->type malli-leaf))))

(deftest desc->type-any-leaf
  (is-type= (at/Dyn tp) (desc->type :any)))

(deftest desc->type-nil-leaf
  (let [t (desc->type :nil)]
    (is (at/value-type? t))
    (is (nil? (:value t)))))

(deftest desc->type-uuid-falls-to-dyn
  (is-type= (at/Dyn tp) (desc->type :uuid)))

(deftest desc->type-rejected-leaves
  (doseq [k [:char :pos-int :neg-int :nat-int]]
    (is (thrown? Exception (desc->type k))
        (str "expected admission boundary to reject " k))))
