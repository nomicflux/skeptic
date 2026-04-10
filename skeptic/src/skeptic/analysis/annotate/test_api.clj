(ns skeptic.analysis.annotate.test-api
  (:require [clojure.string :as str]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.walk :as walk]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.api :as aapi]))

(defn normalize-symbol
  [value]
  (if (symbol? value)
    (let [name-part (name value)]
      (if (str/includes? name-part "__")
        (symbol (namespace value) (first (str/split name-part #"__")))
        value))
    value))

(defn normalize-form
  [form]
  (walk/postwalk normalize-symbol form))

(defn var->sym
  [value]
  (when (instance? clojure.lang.Var value)
    (let [meta-map (meta value)]
      (symbol (str (ns-name (:ns meta-map)) "/" (:name meta-map))))))

(def stable-keys
  [:op :form :body? :local :arg-id :variadic? :class :method :validated?
   :literal? :type :output-type :fn-type :types :arglist :arglists :param-specs
   :actual-argtypes :expected-argtypes :raw-forms])

(defn project-ast
  [root]
  (letfn [(project-node [node]
            (cond
              (nil? node) nil
              (vector? node) (mapv project-node node)
              (map? node)
              (let [base (cond-> (select-keys node stable-keys)
                           (aapi/node-form node) (update :form normalize-form)
                           (aapi/node-raw-forms node) (update :raw-forms #(mapv normalize-form %))
                           (and (= :def (aapi/node-op node)) (aapi/node-name node))
                           (assoc :name (aapi/node-name node))
                           (#{:var :the-var} (aapi/node-op node))
                           (assoc :resolved-var (var->sym (:var node)))
                           (seq (aapi/node-children node))
                           (assoc :children
                                  (mapv (fn [[key child]] [key (project-node child)])
                                        (aapi/node-children node))))]
                (into {} (remove (comp nil? val)) base))
              :else node))]
    (project-node root)))

(defn projected-nodes
  [root]
  (letfn [(walk-projected [node]
            (lazy-seq
             (cond
               (nil? node) nil
               (vector? node) (mapcat walk-projected node)
               (map? node) (cons node (mapcat (comp walk-projected second) (:children node)))
               :else nil)))]
    (walk-projected root)))

(defn find-projected-node
  [root pred]
  (some #(when (pred %) %) (projected-nodes root)))

(defn child-projection
  [node key]
  (some (fn [[child-key child]] (when (= child-key key) child)) (:children node)))

(defn ast-by-name
  [asts sym]
  (some #(when (= sym (aapi/node-name %)) %) asts))

(defn node-by-form
  [ast form]
  (some #(when (= form (aapi/node-form %)) %) (ana.ast/nodes ast)))

(defn arglist-types
  [root arity]
  (aapi/arglist-types root arity))

(defn annotate-form-loop
  ([dict form]
   (annotate-form-loop dict form {}))
  ([dict form opts]
   (aa/annotate-form-loop dict form opts)))

(defn test-local-node
  [sym init]
  {:op :local :form sym :type (aapi/node-type init) :binding-init init})

(defn test-fn-node
  [sym]
  {:op :var :form sym})

(defn test-typed-node
  [op form type]
  {:op op :form form :type type})

(defn test-const-node
  [value]
  {:op :const :val value})

(defn test-invoke-node
  [fn-node args expected actual]
  {:op :invoke
   :fn fn-node
   :args args
   :expected-argtypes expected
   :actual-argtypes actual})

(defn test-invoke-form-node
  [fn-node args form type]
  (assoc (test-invoke-node fn-node args [] []) :form form :type type))

(defn test-with-meta-node
  [expr]
  {:op :with-meta :expr expr})

(defn test-static-call-node
  [class method]
  {:op :static-call :class class :method method})
