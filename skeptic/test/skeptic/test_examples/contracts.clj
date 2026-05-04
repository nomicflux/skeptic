(ns skeptic.test-examples.contracts
  (:require [clojure.string :as str]
            [schema.core :as s]
            [skeptic.test-examples.basics :as basics]
            [skeptic.test-examples.contracts-xns-schema :as xns]))

(s/defschema ConditionalIntOrStr
  (s/conditional integer? s/Int string? s/Str))

(s/defschema CondPreIntOrStr
  (s/cond-pre s/Int s/Str))

(s/defschema EitherIntOrStr
  (s/either s/Int s/Str))

(s/defschema IfIntOrStr
  (s/if integer? s/Int s/Str))

(s/defschema BothAnyInt
  (s/both s/Any s/Int))

(s/defschema BothIntStr
  (s/both s/Int s/Str))

(s/defn takes-conditional-branch :- s/Keyword
  [x :- ConditionalIntOrStr]
  :ok)

(s/defn takes-cond-pre-branch :- s/Keyword
  [x :- CondPreIntOrStr]
  :ok)

(s/defn takes-either-branch :- s/Keyword
  [x :- EitherIntOrStr]
  :ok)

(s/defn takes-if-branch :- s/Keyword
  [x :- IfIntOrStr]
  :ok)

(s/defn takes-both-any-int :- s/Keyword
  [x :- BothAnyInt]
  :ok)

(s/defn takes-both-int-str :- s/Keyword
  [x :- BothIntStr]
  :ok)

(defn conditional-input-int-success
  []
  (takes-conditional-branch 1))

(defn conditional-input-str-success
  []
  (takes-conditional-branch "hi"))

(defn conditional-input-keyword-failure
  []
  (takes-conditional-branch :bad))

(defn cond-pre-input-int-success
  []
  (takes-cond-pre-branch 1))

(defn cond-pre-input-str-success
  []
  (takes-cond-pre-branch "hi"))

(defn cond-pre-input-keyword-failure
  []
  (takes-cond-pre-branch :bad))

(defn either-input-int-success
  []
  (takes-either-branch 1))

(defn either-input-str-success
  []
  (takes-either-branch "hi"))

(defn either-input-keyword-failure
  []
  (takes-either-branch :bad))

(defn if-input-int-success
  []
  (takes-if-branch 1))

(defn if-input-str-success
  []
  (takes-if-branch "hi"))

(defn if-input-keyword-failure
  []
  (takes-if-branch :bad))

(defn both-any-int-input-success
  []
  (takes-both-any-int 1))

(defn both-any-int-input-str-failure
  []
  (takes-both-any-int "hi"))

(defn both-int-str-input-int-failure
  []
  (takes-both-int-str 1))

(defn both-int-str-input-str-failure
  []
  (takes-both-int-str "hi"))

(s/defn conditional-output-int-success :- ConditionalIntOrStr
  []
  1)

(s/defn conditional-output-str-success :- ConditionalIntOrStr
  []
  "hi")

(s/defn conditional-output-keyword-failure :- ConditionalIntOrStr
  []
  :bad)

(s/defn cond-pre-output-int-success :- CondPreIntOrStr
  []
  1)

(s/defn cond-pre-output-str-success :- CondPreIntOrStr
  []
  "hi")

(s/defn cond-pre-output-keyword-failure :- CondPreIntOrStr
  []
  :bad)

(s/defn either-output-int-success :- EitherIntOrStr
  []
  1)

(s/defn either-output-str-success :- EitherIntOrStr
  []
  "hi")

(s/defn either-output-keyword-failure :- EitherIntOrStr
  []
  :bad)

(s/defn if-output-int-success :- IfIntOrStr
  []
  1)

(s/defn if-output-str-success :- IfIntOrStr
  []
  "hi")

(s/defn if-output-keyword-failure :- IfIntOrStr
  []
  :bad)

(s/defn both-any-int-output-success :- BothAnyInt
  []
  1)

(s/defn both-int-str-output-int-failure :- BothIntStr
  []
  1)

(s/defn both-int-str-output-str-failure :- BothIntStr
  []
  "hi")

(s/defschema NonEmptyStr
  (s/constrained s/Str #(not= "" %)))

(s/defschema HasA
  {(s/required-key :a) basics/PosInt})

(s/defschema HasB
  {(s/required-key :b) NonEmptyStr})

(s/defschema HasAOrB
  (s/conditional #(contains? % :a) HasA
                 #(contains? % :b) HasB))

(s/defn takes-has-a :- HasA
  [x :- HasA]
  x)

(s/defn takes-has-b :- HasB
  [x :- HasB]
  x)

(s/defn conditional-map-cond-thread-success :- HasAOrB
  [x :- HasAOrB]
  (cond-> x
    (contains? x :a) takes-has-a
    (contains? x :b) takes-has-b))

(s/defn mk-ab :- HasAOrB
  [x]
  (cond-> {}
    (integer? x) (assoc :a x)
    (not (integer? x)) (assoc :b x)))

(defn mk-ab-unannotated-int-success
  []
  (mk-ab 1))

(defn mk-ab-unannotated-str-success
  []
  (mk-ab "hello"))

(s/defn mk-ab-annotated-int-return-success :- HasAOrB
  []
  (mk-ab 1))

(s/defn mk-ab-annotated-str-return-success :- HasAOrB
  []
  (mk-ab "hello"))

(s/defn conditional-map-if-a-success :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :a)
    (takes-has-a x)
    x))

(s/defn conditional-map-if-b-success :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :b)
    (takes-has-b x)
    x))

(s/defn conditional-map-if-a-bad-branch :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :a)
    (takes-has-b x)
    x))

(s/defn conditional-map-if-b-bad-branch :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :b)
    (takes-has-a x)
    x))

(s/defn conditional-map-alias-success :- HasAOrB
  [x :- HasAOrB]
  (let [y x]
    (if (contains? x :a)
      (takes-has-a y)
      y)))

(s/defschema MaybeHasA
  {(s/optional-key :a) basics/PosInt})

(s/defn optional-map-contains-refines-success
  [x :- MaybeHasA]
  (if (contains? x :a)
    (takes-has-a x)
    x))

(s/defschema NestedHasAOrB
  {:m (s/maybe HasAOrB)})

(s/defn takes-a-or-b
  [{{:keys [a b]} :m} :- NestedHasAOrB]
  (or a b))

(s/defn mk-nested-ab :- NestedHasAOrB
  [ab]
  {:m (when ab (mk-ab ab))})

(s/defn mk-takes-a-or-b-success-int
  []
  (takes-a-or-b (mk-nested-ab 1)))

(s/defn mk-takes-a-or-b-success-str
  []
  (takes-a-or-b (mk-nested-ab "hello")))

(s/defn mk-takes-a-or-b-success-nil
  []
  (takes-a-or-b (mk-nested-ab nil)))

(s/defn mk-takes-a-or-b-failure-outer
  []
  (takes-a-or-b {:a :nope}))

(s/defn mk-takes-a-or-b-failure-inner
  []
  (takes-a-or-b {:c {:d :nope}}))

(s/defn mk-takes-a-or-b-failure-inner-inner
  []
  (takes-a-or-b {:c {:a :nope}}))

(s/defn has-a-or-b-identity :- HasAOrB
  [x :- HasAOrB]
  x)

(s/defn has-a-or-b-conditional-pass-through :- HasAOrB
  [{:keys [a] :as x} :- HasAOrB]
  (if a
    x
    (has-a-or-b-identity x)))

(s/defn nested-has-a-or-b-identity :- NestedHasAOrB
  [x :- NestedHasAOrB]
  x)

(s/defn nested-has-a-or-b-conditional-pass-through :- NestedHasAOrB
  [{{:keys [a]} :m :as x}]
  (cond-> x
    a
    (assoc x :a a)))

(s/defn has-a-or-b-identity-success
  []
  (has-a-or-b-identity (mk-ab 1)))

(s/defn nested-has-a-or-b-identity-success
  []
  (nested-has-a-or-b-identity (mk-nested-ab "hello")))

(s/defn has-a-or-b-conditional-success-a
  []
  (has-a-or-b-conditional-pass-through (mk-ab 1)))

(s/defn has-a-or-b-conditional-success-b
  []
  (has-a-or-b-conditional-pass-through (mk-ab "hello")))

(s/defn nested-has-a-or-b-conditional-success-a
  []
  (nested-has-a-or-b-conditional-pass-through (mk-nested-ab 1)))

(s/defn nested-has-a-or-b-conditional-success-b
  []
  (nested-has-a-or-b-conditional-pass-through (mk-nested-ab "hello")))

(s/defschema ConditionalAorB
  (s/conditional
    #(= :a (:route %)) HasA
    #(= :b (:route %)) HasB))

(s/defn handles-a :- HasA
  [x :- HasA]
  x)

(s/defn handles-b :- HasB
  [x :- HasB]
  x)

(s/defn handles-ab :- ConditionalAorB
  [x :- HasAOrB]
  (case (:route x)
    :a (handles-a x)
    :b (handles-b x)))

(s/defn handles-ab-destructured-route :- (s/eq true)
  [{:keys [route]} :- ConditionalAorB]
  (case route
    :a true
    :b true))

(s/defschema VariantA
  {:k (s/eq :a) :x s/Int})

(s/defschema VariantB
  {:k (s/eq :b) :y s/Str})

(s/defschema Variants
  (s/conditional #(= :a (:k %)) VariantA
                 #(= :b (:k %)) VariantB))

(s/defn handle-a :- s/Any
  [v :- VariantA]
  v)

(s/defn handle-b :- s/Any
  [v :- VariantB]
  v)

(s/defn vtype
  [v]
  (:k v))

(s/defn conditional-dispatch-success :- s/Any
  [v :- Variants]
  (case (vtype v)
    :a (handle-a v)
    :b (handle-b v)))

(s/defn handle-variant-a-strict :- s/Int
  [v :- VariantA]
  (:x v))

(s/defn handle-variant-b-strict :- s/Str
  [v :- VariantB]
  (:y v))

(s/defn variant-strict-dispatch :- s/Any
  [v :- Variants]
  (case (:k v)
    :a (handle-variant-a-strict v)
    :b (handle-variant-b-strict v)))

(s/defn variant-let-bound-dispatch
  [x :- Variants]
  (let [k-route (:k x)]
    (case k-route
      :a (handle-variant-a-strict x)
      :b (handle-variant-b-strict x))))

(s/defn cond-branch-pick-success :- (s/maybe (s/conditional :x {:x s/Int}))
  []
  {:x 1})

(s/defn narrowing-string-predicate-success :- s/Int
  [x :- (s/cond-pre s/Str s/Int)]
  (if (string? x)
    (count x)
    (basics/int-add x 1)))

(s/defn narrowing-keyword-invoke-presence-success :- s/Int
  [m :- {:a s/Int}]
  (if (:a m)
    (basics/int-add (:a m) 1)
    0))

(s/defn narrowing-case-success :- s/Int
  [x :- (s/cond-pre (s/eq :a) (s/eq :b))]
  (case x
    :a 1
    :b 2))

(s/defn narrowing-assoc-get-success :- s/Int
  [m :- {:a s/Int}]
  (basics/int-add (:a (assoc m :a 1)) 0))

(s/defn narrowing-fn-helper :- s/Int
  [x :- s/Int]
  x)

(s/defn narrowing-fn-qmark-success :- s/Int
  [f :- s/Any]
  (if (fn? f)
    (narrowing-fn-helper 1)
    0))

(s/defn fn-type-satisfies-pred-fn-success :- (s/pred fn?)
  [f :- (s/=> s/Int s/Int)]
  f)

(s/defn outer-fn
  [f :- (s/=> s/Any s/Str)]
  (outer-fn (fn [] nil)))

(s/defn if-nullable-guard-success :- s/Str
  [x :- (s/maybe s/Str)]
  (if (not (nil? x))
    x
    "fallback"))

(s/defn cond->-guard-success :- {(s/optional-key :p) s/Str}
  [raw :- (s/maybe s/Str)]
  (let [p (when (some? raw) raw)]
    (cond-> {}
      (some? p) (assoc :p p))))

(s/defschema OutOptionalAB
  {(s/optional-key :a) s/Str
   (s/optional-key :b) s/Str})

(s/defn if-blank-guard-optional-keys-branches-success :- OutOptionalAB
  [{:keys [a]} :- {:a (s/maybe s/Str) s/Keyword s/Any}]
  (if (str/blank? a)
    {:b "x"}
    {:a a :b "y"}))

(s/defn if-some-guard-destructured-success :- s/Str
  [{:keys [a]} :- {:a (s/maybe s/Str) s/Keyword s/Any}]
  (if (some? a)
    a
    "fallback"))

(s/defschema NestedConditionalBase {:k s/Str})
(s/defschema NestedConditionalA (assoc NestedConditionalBase :k (s/eq "a")))
(s/defschema NestedConditionalB (assoc NestedConditionalBase :k (s/eq "b")))

(s/defn nested-conditional-classify :- s/Keyword
  [{:keys [k]} :- NestedConditionalBase]
  (case k
    "a" :a
    "b" :b
    :unclassified))

(s/defschema NestedConditionalIn
  (let [is? (fn [v] (comp #{v} nested-conditional-classify))]
    (s/conditional
     (is? :a) NestedConditionalA
     (is? :b) NestedConditionalB
     'SupportedType)))

(s/defschema NestedConditionalX {:x NestedConditionalIn})
(s/defschema NestedConditionalY {:x NestedConditionalB})

(s/defn nested-conditional-f :- [[s/Str]]
  [_ :- NestedConditionalY]
  [])

(s/defn nested-conditional-repro :- s/Any
  [{{:keys [k]} :x :as x} :- NestedConditionalX]
  (cond->> x
    (= k "b") (nested-conditional-f)
    (= k "a") ((constantly []))))

(s/defn or-default-int-helper
  [x :- s/Int]
  s/Int)

(s/defn or-default-optional-key-success
  [{:keys [x] :or {x 1}} :- {(s/optional-key :x) s/Int}]
  (or-default-int-helper x))

(s/defn or-default-optional-key-keyword-default-failure
  [{:keys [x] :or {x :no-val}} :- {(s/optional-key :x) s/Int}]
  (or-default-int-helper x))

(s/defschema NestedConditionalInTopPred
  (s/conditional
   (comp #{:b} nested-conditional-classify) NestedConditionalB
   'SupportedType))

(s/defschema NestedConditionalXTop {:x NestedConditionalInTopPred})

(s/defn nested-conditional-repro-top :- s/Any
  [{{:keys [k]} :x :as x} :- NestedConditionalXTop]
  (cond->> x
    (= k "b") (nested-conditional-f)))

(s/defschema A {})
(s/defschema B {:k (s/eq :b)})

(s/defn choose :- (s/enum :a :b)
  [x]
  (keyword (get x :k :a)))

(s/defschema In
  (s/conditional
    #(= :a (choose %)) A
    #(= :b (choose %)) B))

(s/defn f-a :- [s/Any]
  [x :- A]
  [])

(s/defn f-b :- [s/Any]
  [x :- B]
  [])

(s/defn chooses-conditional-success :- [s/Any]
  [x :- In]
  (case (choose x)
    :a (f-a x)
    :b (f-b x)))

(s/defschema PlainDefnDiscA {})
(defn plain-defn-disc-f [m] (get m :k :a))
(s/defschema PlainDefnDiscIn
  (s/conditional
    #(= :a (plain-defn-disc-f %)) PlainDefnDiscA
    #(= :b (plain-defn-disc-f %)) {(s/optional-key :k) s/Any}))

(s/defn plain-defn-disc-g
  [_m :- PlainDefnDiscA]
  nil)

(s/defn plain-defn-disc-repro-success
  [m :- PlainDefnDiscIn]
  (case (plain-defn-disc-f m)
    :a (plain-defn-disc-g m)))

(s/defschema PlainDefnKindKind (s/enum :a :b))
(s/defschema PlainDefnKindInA
  {(s/optional-key :k) (s/eq :a)
   :k1 (s/constrained s/Str string? 'PlainDefnKindARefined)
   :k2 (s/constrained s/Str string? 'PlainDefnKindARefined)})

(s/defschema PlainDefnKindInB
  {:k (s/eq :b)
   :k3 (s/constrained s/Int integer? 'PlainDefnKindBRefined)})

(declare plain-defn-kind-params->kind)

(s/defschema PlainDefnKindIn
  (s/conditional
    #(= :a (plain-defn-kind-params->kind %)) PlainDefnKindInA
    #(= :b (plain-defn-kind-params->kind %)) PlainDefnKindInB))

(defn plain-defn-kind-params->kind
  [params]
  (keyword (get params :k :a)))

(s/defn plain-defn-kind-f-a :- s/Any
  [params :- PlainDefnKindInA]
  nil)

(s/defn plain-defn-kind-repro-success :- s/Any
  [params :- PlainDefnKindIn]
  (case (plain-defn-kind-params->kind params)
    :a (plain-defn-kind-f-a params)))

(s/defn xns-consume-a
  [_m :- xns/XnsA]
  nil)

(s/defn xns-cross-ns-conditional-narrowing-success
  [m :- xns/XnsIn]
  (case (xns/xns-dispatch m)
    :a (xns-consume-a m)))

(s/defn take-str :- s/Str
  [x :- s/Str]
  x)

(s/defn xns-int-into-str-arg-failure
  []
  (let [x (xns/f)
        y (take-str x)]
    y))

(s/defschema KeyOpenBase {s/Keyword s/Any})

(s/defschema KeyOpenSingle (merge KeyOpenBase {:k1 s/Str}))

(s/defschema KeyOpenCond
  (s/conditional
   #(vector? (:k1 %)) (merge KeyOpenBase {:k1 [s/Str]})
   #(some? (:k1 %)) (merge KeyOpenBase {:k1 s/Str})
   :else KeyOpenBase))

(s/defn consume-key-open-single :- s/Any
  [_ :- KeyOpenSingle]
  nil)

(s/defn cond-key-some-narrowing-repro
  [x :- KeyOpenCond]
  (cond
    (vector? (:k1 x)) nil

    (some? (:k1 x)) (consume-key-open-single x)

    :else nil))

(s/defschema KeyIntBase {s/Keyword s/Int})

(s/defschema KeyIntSingle (merge KeyIntBase {:k1 s/Str}))

(s/defschema KeyIntCond
  (s/conditional
   #(vector? (:k1 %)) (merge KeyIntBase {:k1 [s/Str]})
   #(some? (:k1 %)) (merge KeyIntBase {:k1 s/Str})
   :else KeyIntBase))

(s/defn consume-key-int-single :- s/Any
  [_ :- KeyIntSingle]
  nil)

(s/defn cond-key-some-narrowing-int-wildcard-repro
  [x :- KeyIntCond]
  (cond
    (vector? (:k1 x)) nil

    (some? (:k1 x)) (consume-key-int-single x)

    :else nil))
