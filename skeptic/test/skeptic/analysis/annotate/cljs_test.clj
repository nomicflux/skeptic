(ns skeptic.analysis.annotate.cljs-test
  "Phase 6 dispatch smoke: cljs `:host-call`, `:host-field`, `:js`, `:js-var`
  ops route through the new dispatch arms and pick up types translated from
  the cljs `:tag`."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate :as annotate]
            [skeptic.analysis.types :as at]
            [skeptic.cljs.analyzer-driver :as ad]
            [skeptic.cljs.compiler-env :as compiler-env]))

(defn- annotated [form]
  (let [cenv (compiler-env/fresh-state)
        ast (ad/analyze-form cenv 'cljs.user form)]
    (annotate/annotate-ast {} ast)))

(deftest host-call-routes-and-types
  (let [node (annotated '(.toUpperCase "abc"))]
    (is (= :host-call (:op node)))
    (is (some? (:type node)))))

(deftest host-field-routes-and-types
  (let [node (annotated '(.-length "abc"))]
    (is (= :host-field (:op node)))
    (is (some? (:type node)))))

(deftest js-var-types-from-tag
  (let [node (annotated 'js/console)]
    (is (= :js-var (:op node)))
    (is (at/dyn-type? (:type node)))))

(deftest js-form-routes
  (let [node (annotated '(js* "~{} + ~{}" 1 2))]
    (is (= :js (:op node)))
    (is (some? (:type node)))))
