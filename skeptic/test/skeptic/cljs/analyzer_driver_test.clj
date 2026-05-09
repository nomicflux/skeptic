(ns skeptic.cljs.analyzer-driver-test
  (:require [cljs.analyzer :as ana]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cljs.analyzer-driver :as sut]
            [skeptic.cljs.compiler-env :as cenv-mod]))

(def fixture-file "dev-resources/cljs-fixtures/p1.cljs")

(defn- output-schema-binding
  [let-ast]
  (some (fn [b]
          (when (and (symbol? (:name b))
                     (.startsWith (name (:name b)) "output-schema"))
            b))
        (:bindings let-ast)))

(deftest load-source-bootstraps-and-admits-fixture-meta
  (require 'schema.core)
  (let [cenv (cenv-mod/fresh-state)
        asts (cenv-mod/load-source! cenv fixture-file)
        f-meta (get-in @cenv [::ana/namespaces 'p1 :defs 'f :meta])]
    (testing "compiler-env carries p1's f def with the expected meta keys"
      (is (some? f-meta))
      (is (every? (set (keys f-meta)) #{:schema :arglists :raw-arglists :doc})))
    (testing ":schema is a list starting with schema.core/->FnSchema"
      (let [s (:schema f-meta)]
        (is (seq? s))
        (is (= 'schema.core/->FnSchema (first s)))))
    (testing "load-source! returns a top-level :op :let with output-schema binding"
      (let [let-ast (some #(when (= :let (:op %)) %) asts)
            ob (some-> let-ast output-schema-binding)]
        (is (some? let-ast))
        (is (some? ob))
        (is (= 's/Int (-> ob :init :form)))))))

(deftest analyze-form-handles-bare-schema-defn
  (require 'schema.core)
  (let [cenv (cenv-mod/fresh-state)
        ast (sut/analyze-form cenv 'cljs.user
                              '(schema.core/defn g :- schema.core/Int
                                 [x :- schema.core/Int]
                                 (+ x 1)))]
    (is (= :let (:op ast)))))
