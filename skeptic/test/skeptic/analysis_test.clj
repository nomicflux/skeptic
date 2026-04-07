(ns skeptic.analysis-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.walk :as walk]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as checking]
            [skeptic.source :as source]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.test-examples :as test-examples])
  (:import [java.io File]))

(defn set-cache-value
  [& _args]
  nil)

(defn f
  [& _args]
  nil)

(defn int-add
  [& _args]
  nil)

(defn make-component
  [& _args]
  nil)

(defn start
  [& _args]
  nil)

(def x nil)
(def y nil)
(def z nil)
(def cache nil)

(defn T
  [schema]
  (ab/schema->type schema))

;; `GroundT` for JVM `java.lang.Number` — expected annotation for native math
;; (`Numbers` static-calls, `native-fn-dict` `+` / `inc`, etc.) in assertions.
(def num-ground (at/->GroundT {:class java.lang.Number} 'Number))

(defn arg-entry
  [[name schema]]
  {:type (T schema)
   :optional? false
   :name name})

(defn arity-entry
  [args]
  {:arglist (mapv first args)
   :count (count args)
   :types (mapv arg-entry args)})

(defn fn-entry
  [sym output & arities]
  (let [fn-schema (s/make-fn-schema output
                                    (mapv (fn [args]
                                            (mapv (fn [[name schema]]
                                                    (s/one schema name))
                                                  args))
                                          arities))]
    {:name (str sym)
     :type (T fn-schema)
     :output-type (T output)
     :arglists (into {}
                     (map (fn [args]
                            [(count args) (arity-entry args)]))
                     arities)}))

(def analysis-dict
  (merge (typed-decls/typed-ns-entries {} 'skeptic.test-examples)
         {'skeptic.analysis-test/f
          (fn-entry 'skeptic.analysis-test/f s/Any [['value s/Any]])
          'skeptic.analysis-test/int-add
          (fn-entry 'skeptic.analysis-test/int-add s/Any [['left s/Any]
                                                          ['right s/Any]])}
         {'skeptic.analysis-test/set-cache-value
          (fn-entry 'skeptic.analysis-test/set-cache-value s/Any [['value s/Any]])
          'skeptic.analysis-test/make-component
          (fn-entry 'skeptic.analysis-test/make-component s/Any [['opts s/Any]])
          'skeptic.analysis-test/start
          (fn-entry 'skeptic.analysis-test/start s/Any [['component s/Any]
                                                        ['opts s/Any]])}))

(def typed-test-examples-dict
  (typed-decls/typed-ns-entries {} 'skeptic.test-examples))

(def sample-dict
  {'f
   {:name "f"
    :type (T (s/=> s/Int s/Int))
    :output-type (T s/Int)
    :arglists {1 {:arglist ['x]
                   :count 1
                   :types [{:type (T s/Int) :optional? false :name 'x}]}
               2 {:arglist ['y 'z]
                  :count 2
                  :types [{:type (T s/Str) :optional? false :name 'y}
                          {:type (T s/Int) :optional? false :name 'z}]}}}})

(def test-examples-file (File. "test/skeptic/test_examples.clj"))
(def examples-file (File. "src/skeptic/examples.clj"))

(defn locals
  [& syms]
  {:locals (into {}
                 (map (fn [sym] [sym (T s/Any)]))
                 syms)})

(defn local-types
  [m]
  {:locals m})

(defn analyze-form
  ([form]
   (aa/annotate-form-loop analysis-dict form {:ns 'skeptic.analysis-test}))
  ([arg1 arg2]
   (if (map? arg1)
     (aa/annotate-form-loop arg1 arg2 {:ns 'skeptic.analysis-test})
     (aa/annotate-form-loop analysis-dict arg1 (merge {:ns 'skeptic.analysis-test}
                                                      arg2))))
  ([dict form opts]
   (aa/annotate-form-loop dict form (merge {:ns 'skeptic.analysis-test}
                                           opts))))

(defn normalize-symbol
  [value]
  (if (symbol? value)
    (let [name-part (name value)]
      (if (str/includes? name-part "__")
        (symbol (namespace value)
                (first (str/split name-part #"__")))
        value))
    value))

(defn normalize-form
  [form]
  (walk/postwalk normalize-symbol form))

(defn var->sym
  [value]
  (when (instance? clojure.lang.Var value)
    (let [m (meta value)]
      (symbol (str (ns-name (:ns m)) "/" (:name m))))))

(def stable-keys
  [:op :form :body? :local :arg-id :variadic? :class :method :validated?
   :literal? :type :output-type :fn-type :types :arglist :arglists :param-specs
   :actual-argtypes :expected-argtypes :raw-forms])

(defn arglist-types
  [root arity]
  (-> root :arglists (get arity) :types (->> (mapv :type))))

(declare project-node)

(defn project-children
  [node]
  (mapv (fn [key]
          [key (project-node (get node key))])
        (:children node)))

(defn project-node
  [node]
  (cond
    (nil? node) nil
    (vector? node) (mapv project-node node)
    (map? node)
    (let [base (cond-> (select-keys node stable-keys)
                 (:form node) (update :form normalize-form)
                 (:raw-forms node) (update :raw-forms #(mapv normalize-form %))
                 (and (= :def (:op node)) (:name node)) (assoc :name (:name node))
                 (#{:var :the-var} (:op node)) (assoc :resolved-var (var->sym (:var node)))
                 (:children node) (assoc :children (project-children node)))]
      (into {}
            (remove (comp nil? val))
            base))
    :else node))

(defn project-ast
  [root]
  (project-node root))

(defn projected-nodes
  [root]
  (letfn [(walk-projected [node]
            (lazy-seq
             (cond
               (nil? node) nil
               (vector? node) (mapcat walk-projected node)
               (map? node) (cons node
                                 (mapcat (comp walk-projected second)
                                         (:children node)))
               :else nil)))]
    (walk-projected root)))

(defn find-projected-node
  [root pred]
  (some #(when (pred %) %) (projected-nodes root)))

(defn child-projection
  [node key]
  (->> (:children node)
       (some (fn [[child-key child]]
               (when (= child-key key) child)))))

(defn ast-by-name
  [asts sym]
  (some #(when (= sym (:name %)) %) asts))

(defn node-by-form
  [ast form]
  (some #(when (= form (:form %)) %) (ana.ast/nodes ast)))

(defmacro source-exprs-in
  [ns-sym file]
  `(checking/block-in-ns ~ns-sym ~file
                         (checking/ns-exprs ~file)))

(deftest restored-resolution-contract-test
  (testing "unannotated helper lookup from collected entries alone stays plain Any"
    (let [dict (typed-decls/typed-ns-entries {} 'skeptic.test-examples)
          form (->> 'skeptic.test-examples/unannotated-local-helper-g
                    (source/get-fn-code {})
                    read-string)
          ast (aa/annotate-form-loop dict form {:ns 'skeptic.test-examples})
          call-node (node-by-form ast '(unannotated-local-helper-f))]
      (is (= (T s/Any) (:type call-node)))
      (is (not (at/union-type? (:type call-node))))))

  (testing "declared helper chains use declared outputs exactly"
    (let [dict (typed-decls/typed-ns-entries {} 'skeptic.test-examples)
          {:keys [resolved resolved-defs]} (checking/analyze-source-exprs dict
                                                                         'skeptic.test-examples
                                                                         test-examples-file
                                                                         (source-exprs-in 'skeptic.test-examples test-examples-file))
          failure-ast (ast-by-name resolved 'flat-multi-step-failure)
          call-node (node-by-form failure-ast '(flat-multi-step-takes-str (flat-multi-step-g)))]
      (is (= (T s/Int) (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-f :output-type])))
      (is (= (T s/Int) (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-g :output-type])))
      (is (= [(T s/Int)] (:actual-argtypes call-node)))
      (is (not (at/union-type? (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-g :output-type])))))))
