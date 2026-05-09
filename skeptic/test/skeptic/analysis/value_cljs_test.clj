(ns skeptic.analysis.value-cljs-test
  "Tag translator (Phase 6): cljs `:tag` symbols and set-tags map to semantic
  Types via `av/cljs-tag->type`, with set-tags containing `clj-nil` becoming
  `MaybeT(union of remainder)`."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(def ^:private p (prov/make-provenance :inferred 'sym 'ns nil))

(deftest cljs-tag-symbol-string
  (is (at/type=? (at/->GroundT p :str 'Str) (av/cljs-tag->type p 'string))))

(deftest cljs-tag-symbol-boolean
  (is (at/type=? (at/->GroundT p :bool 'Bool) (av/cljs-tag->type p 'boolean))))

(deftest cljs-tag-symbol-keyword
  (is (at/type=? (at/->GroundT p :keyword 'Keyword)
                 (av/cljs-tag->type p 'cljs.core/Keyword))))

(deftest cljs-tag-symbol-clj-nil
  (is (at/type=? (at/->ValueT p (at/Dyn p) nil)
                 (av/cljs-tag->type p 'clj-nil))))

(deftest cljs-tag-symbol-number-numeric-dyn
  (is (at/type=? (at/NumericDyn p) (av/cljs-tag->type p 'number))))

(deftest cljs-tag-symbol-unknown-is-dyn
  (is (at/type=? (at/Dyn p) (av/cljs-tag->type p 'js/Console)))
  (is (at/type=? (at/Dyn p) (av/cljs-tag->type p 'cljs.core/IMap)))
  (is (at/type=? (at/Dyn p) (av/cljs-tag->type p 'any)))
  (is (at/type=? (at/Dyn p) (av/cljs-tag->type p 'function))))

(deftest cljs-tag-set-with-clj-nil-is-maybe
  (let [actual (av/cljs-tag->type p '#{string clj-nil})]
    (is (at/maybe-type? actual))
    (is (at/type=? (at/->GroundT p :str 'Str) (:inner actual)))))

(deftest cljs-tag-set-multi-non-nil-is-union
  (let [actual (av/cljs-tag->type p '#{string number})]
    (is (at/union-type? actual))))

(deftest cljs-tag-set-only-clj-nil-is-maybe-dyn
  (let [actual (av/cljs-tag->type p '#{clj-nil})]
    (is (at/maybe-type? actual))
    (is (at/dyn-type? (:inner actual)))))

(deftest class-to-type-routes-symbols-and-sets
  (is (at/type=? (at/->GroundT p :str 'Str) (av/class->type p 'string)))
  (let [actual (av/class->type p '#{cljs.core/Keyword clj-nil})]
    (is (at/maybe-type? actual))
    (is (at/type=? (at/->GroundT p :keyword 'Keyword) (:inner actual)))))
