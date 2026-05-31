(ns skeptic.malli-spec.collect-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [skeptic.malli-spec.collect :as sut]))

(def ns-sym 'skeptic.malli-spec.collect-test)

;; Shipped clj-state entries the worker would produce — inert form/field data,
;; no live ns. Channel 1 (:malli/schema Var-meta) rides the captured field;
;; channels 2/3 (m/=>, mx/defn) ride the :source-form.
(def meta-entry
  {:source-form '(defn demo-fn {:malli/schema [:=> [:cat :int] :int]} [x] x)
   :malli-schema [:=> [:cat :int] :int]})

(def arrow-entry
  {:source-form '(m/=> arrow-fn [:=> [:cat :int] :int])})

(def mx-entry
  {:source-form '(mx/defn mx-fn :- :int [x :- :int] (inc x))})

(def plain-entry
  {:source-form '(defn plain-fn [x] x)})

(def aliases '{m malli.core mx malli.experimental})

(deftest channel-1-malli-schema-var-meta-admitted
  (let [result (sut/ns-malli-spec-results ns-sym aliases [meta-entry])]
    (is (= [] (:errors result)))
    (is (= {:name "skeptic.malli-spec.collect-test/demo-fn"
            :malli-spec (m/form (m/schema [:=> [:cat :int] :int]))}
           (get (:entries result) (symbol (name ns-sym) "demo-fn"))))))

(deftest channel-2-m-arrow-admitted
  (let [result (sut/ns-malli-spec-results ns-sym aliases [arrow-entry])]
    (is (= [] (:errors result)))
    (is (= {:name "skeptic.malli-spec.collect-test/arrow-fn"
            :malli-spec (m/form (m/schema [:=> [:cat :int] :int]))}
           (get (:entries result) (symbol (name ns-sym) "arrow-fn"))))))

(deftest channel-3-mx-defn-admitted
  (let [result (sut/ns-malli-spec-results ns-sym aliases [mx-entry])]
    (is (= [] (:errors result)))
    (is (= {:name "skeptic.malli-spec.collect-test/mx-fn"
            :malli-spec (m/form (m/schema [:=> [:cat :int] :int]))}
           (get (:entries result) (symbol (name ns-sym) "mx-fn"))))))

(deftest skips-entries-without-malli-source
  (let [result (sut/ns-malli-spec-results ns-sym aliases [plain-entry])]
    (is (not (contains? (:entries result) (symbol (name ns-sym) "plain-fn"))))))

(deftest malli-admitted-qsyms-matches-admission
  (let [entries [meta-entry arrow-entry mx-entry plain-entry]]
    (is (= (set (keys (:entries (sut/ns-malli-spec-results ns-sym aliases entries))))
           (sut/malli-admitted-qsyms ns-sym aliases entries)))))
