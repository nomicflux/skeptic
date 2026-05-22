(ns skeptic.cljs.analyzer-driver-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.cljs.analyzer-driver :as sut]
            [cljs.analyzer.api :as ana-api]))

(deftest analyze-form-handles-aliased-schema-defn
  (require 'schema.core)
  (let [ns-ast (sut/parse-source-ns "dev-resources/cljs-fixtures/p4.cljs")
        ast (sut/analyze-form ns-ast
                              '(s/defn g :- s/Int [x :- s/Int] (+ x 1)))]
    (is (= :let (:op ast)))
    (is (= 5 (count (:bindings ast))))))

(deftest analyze-source-file-returns-ns-ast-and-asts
  (require 'schema.core)
  (let [result (sut/analyze-source-file "dev-resources/cljs-fixtures/p1.cljs")
        ns-ast (:ns-ast result)
        entries (:entries result)
        asts   (:asts result)]
    (is (map? result))
    (is (contains? result :ns-ast))
    (is (contains? result :entries))
    (is (contains? result :asts))
    (is (= 'p1 (:name ns-ast)))
    (is (contains? (set (vals (:requires ns-ast))) 'schema.core))
    (is (vector? entries))
    (is (vector? asts))
    (is (= (mapv :ast entries) asts))
    (is (= 1 (count asts)))
    (is (every? #(contains? % :op) asts))
    (is (= '(s/defn f :- s/Int [x :- s/Int] (+ x 1))
           (:source-form (first entries))))))

(deftest analyze-source-file-uses-cljs-reader-context
  (require 'malli.core)
  (let [{:keys [entries asts]} (sut/analyze-source-file "dev-resources/cljs-fixtures/p5.cljs")
        source-forms (mapv :source-form entries)
        names (set (keep :name asts))]
    (is (= 5 (count entries)))
    (is (not-any? #(and (seq? %) (= 'ns (first %))) source-forms))
    (is (some #(= '(def default-key :malli.core/default) %) source-forms))
    (is (some #(and (seq? %)
                    (= 'def (first %))
                    (= 'js-value (second %)))
              source-forms))
    (is (contains? names 'p5/default-key))
    (is (contains? names 'p5/js-value))))

(deftest analyze-source-file-arity-2-shares-state-across-calls
  ;; Regression: analyzing p6.tests then p6.core into a SHARED state lets
  ;; (run-tests 'p6.tests) in p6.core macroexpand successfully — the
  ;; cljs.test assertion (ana-api/find-ns 'p6.tests) finds the entry the
  ;; first call wrote at [::namespaces 'p6.tests].
  (require 'cljs.test)
  (let [state (ana-api/empty-state)
        _      (sut/analyze-source-file state
                                        "dev-resources/cljs-fixtures/p6-cross-require/src/p6/tests.cljs")
        result (sut/analyze-source-file state
                                        "dev-resources/cljs-fixtures/p6-cross-require/src/p6/core.cljs")]
    (is (= 'p6.core (get-in result [:ns-ast :name])))
    (is (= 'p6.tests
           (get-in @state [:cljs.analyzer/namespaces 'p6.tests :name])))))

(defn- ast-nodes
  [ast]
  (tree-seq #(and (map? %) (seq (:children %)))
            (fn [node]
              (mapcat (fn [k]
                        (let [v (get node k)]
                          (cond
                            (and (map? v) (:op v)) [v]
                            (vector? v) (filter #(and (map? %) (:op %)) v)
                            :else [])))
                      (:children node)))
            ast))

(deftest analyze-source-file-seeds-explicit-var-quote-without-analyzing-deps
  (let [{:keys [entries]} (sut/analyze-source-file "dev-resources/cljs-fixtures/p10_var_quote.cljs")
        entry (first (filter #(= 'read-inst (second (:source-form %))) entries))
        invoke-node (first (filter #(= '((var reader/read-date) form) (:form %))
                                   (ast-nodes (:ast entry))))
        fn-node (:fn invoke-node)]
    (is (nil? (:exception entry)))
    (is (= :the-var (:op fn-node)))
    (is (= 'cljs.reader/read-date (get-in fn-node [:var :info :name])))))

(deftest analyze-source-file-loads-macro-requested-analyzer-namespace
  (let [{:keys [entries]} (sut/analyze-source-file
                           "dev-resources/skeptic/cljs_fixtures/p13_macro_publics/core.cljs")
        entry (first entries)]
    (is (nil? (:exception entry)))
    (is (= '(def string-public-count
              (macros/public-count (quote clojure.string)))
           (:source-form entry)))
    (is (= 'skeptic.cljs-fixtures.p13-macro-publics.core/string-public-count
           (get-in entry [:ast :name])))))
