(ns skeptic.analysis.native-fns-cljs-test
  "Phase 8: cljs.core entries appear in `native-fn-dict` with `:lang :cljs`,
  while existing clojure.core entries keep `:lang :clj`. Plumatic-side
  predicate registry is mirrored: every `clojure.core/X` predicate has a
  matching `cljs.core/X` entry."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.native-fns :as sut]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.provenance :as prov]))

(def ^:private cljs-fn-syms
  '#{cljs.core/+ cljs.core/* cljs.core/inc cljs.core/str
     cljs.core/even? cljs.core/odd?})

(deftest cljs-core-entries-present
  (doseq [sym cljs-fn-syms]
    (is (contains? sut/native-fn-dict sym)
        (str "expected " sym " in native-fn-dict"))))

(deftest cljs-core-entries-tagged-cljs
  (doseq [sym cljs-fn-syms]
    (let [t (get sut/native-fn-dict sym)
          p (prov/of t)]
      (is (= :native (prov/source p)))
      (is (= :cljs (prov/lang p))
          (str "expected " sym " prov :lang :cljs, got " (prov/lang p))))))

(deftest clj-core-entries-tagged-clj
  (doseq [sym '#{clojure.core/+ clojure.core/* clojure.core/inc
                 clojure.core/str clojure.core/format
                 clojure.core/even? clojure.core/odd?}]
    (let [t (get sut/native-fn-dict sym)
          p (prov/of t)]
      (is (= :native (prov/source p)))
      (is (= :clj (prov/lang p))
          (str "expected " sym " prov :lang :clj")))))

(deftest predicate-registry-mirrored-to-cljs
  (testing "every clojure.core predicate has a cljs.core counterpart"
    (doseq [clj-sym predicates/predicate-symbols
            :let [cljs-sym (symbol "cljs.core" (name clj-sym))]]
      (is (contains? sut/native-fn-dict cljs-sym)
          (str "expected " cljs-sym " mirroring " clj-sym))))
  (testing "cljs predicate entries are :native + :cljs"
    (doseq [cljs-sym predicates/cljs-predicate-symbols]
      (let [p (prov/of (get sut/native-fn-dict cljs-sym))]
        (is (= :native (prov/source p)))
        (is (= :cljs (prov/lang p)))))))

(deftest native-fn-provenance-matches-dict-langs
  (doseq [[sym t] sut/native-fn-dict]
    (let [p (get sut/native-fn-provenance sym)]
      (is (= (prov/lang (prov/of t)) (prov/lang p))
          (str "provenance map lang for " sym " must match dict Type's lang")))))

(deftest cljs-format-not-included
  (is (not (contains? sut/native-fn-dict 'cljs.core/format))
      "cljs.core has no `format`; it lives in goog.string"))

(deftest lookup-type-resolves-cljs-var-shape
  (testing "cljs `:var` op nodes carry qualified sym at :info :name; lookup-type
  must read it as a candidate so cljs.core entries get found"
    (let [cljs-var-node {:op :var
                         :form 'inc
                         :name 'cljs.core/inc
                         :info {:name 'cljs.core/inc}}
          t (ac/lookup-type sut/native-fn-dict 'some.user.ns cljs-var-node)]
      (is (some? t) "cljs `:var` node should resolve via :info :name candidate")
      (is (= :cljs (prov/lang (prov/of t)))
          "resolved entry must be the cljs.core/inc one, not clojure.core/inc"))))
