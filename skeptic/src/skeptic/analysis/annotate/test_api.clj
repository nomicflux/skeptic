(ns skeptic.analysis.annotate.test-api
  (:require [schema.core :as s]
            [clojure.string :as str]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.walk :as walk]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]))

(s/defn normalize-symbol :- s/Any
  [value :- s/Any]
  (if (symbol? value)
    (let [name-part (name value)]
      (if (str/includes? name-part "__")
        (symbol (namespace value) (first (str/split name-part #"__")))
        value))
    value))

(s/defn normalize-form :- s/Any
  [form :- s/Any]
  (walk/postwalk normalize-symbol form))

(s/defn var->sym :- s/Any
  [value :- s/Any]
  (when (instance? clojure.lang.Var value)
    (let [meta-map (meta value)]
      (symbol (str (ns-name (:ns meta-map)) "/" (:name meta-map))))))

(def stable-keys
  [:op :form :body? :local :arg-id :variadic? :class :method :validated?
   :literal? :type :output-type :fn-type :types :arglist :arglists :param-specs
   :actual-argtypes :expected-argtypes :raw-forms])

(s/defn project-ast :- s/Any
  [root :- s/Any]
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

(s/defn projected-nodes :- s/Any
  [root :- s/Any]
  (letfn [(walk-projected [node]
            (lazy-seq
             (cond
               (nil? node) nil
               (vector? node) (mapcat walk-projected node)
               (map? node) (cons node (mapcat (comp walk-projected second) (:children node)))
               :else nil)))]
    (walk-projected root)))

(s/defn find-projected-node :- s/Any
  [root :- s/Any pred :- s/Any]
  (some #(when (pred %) %) (projected-nodes root)))

(s/defn child-projection :- s/Any
  [node :- s/Any key :- s/Any]
  (some (fn [[child-key child]] (when (= child-key key) child)) (:children node)))

(s/defn ast-by-name :- s/Any
  [asts :- s/Any sym :- s/Any]
  (some #(when (= sym (aapi/node-name %)) %) asts))

(s/defn node-by-form :- s/Any
  [ast :- s/Any form :- s/Any]
  (some #(when (= form (aapi/node-form %)) %) (ana.ast/nodes ast)))

(s/defn arglist-types :- s/Any
  [root :- s/Any arity :- s/Any]
  (aapi/arglist-types root arity))

(s/defn annotate-form-loop :- aas/AnnotatedNode
  ([dict :- s/Any form :- s/Any]
   (annotate-form-loop dict form {}))
  ([dict :- s/Any form :- s/Any opts :- s/Any]
   (aa/annotate-form-loop dict form opts)))

(s/defn test-local-node :- s/Any
  [sym :- s/Any init :- s/Any]
  {:op :local :form sym :type (aapi/node-type init) :binding-init init})

(s/defn test-fn-node :- s/Any
  [sym :- s/Any]
  {:op :var :form sym})

(s/defn test-typed-node :- s/Any
  [op :- s/Any form :- s/Any type :- s/Any]
  {:op op :form form :type type})

(s/defn test-const-node :- s/Any
  [value :- s/Any]
  {:op :const :val value})

(s/defn test-invoke-node :- s/Any
  [fn-node :- s/Any args :- s/Any expected :- s/Any actual :- s/Any]
  {:op :invoke
   :fn fn-node
   :args args
   :expected-argtypes expected
   :actual-argtypes actual})

(s/defn test-invoke-form-node :- s/Any
  [fn-node :- s/Any args :- s/Any form :- s/Any type :- s/Any]
  (assoc (test-invoke-node fn-node args [] []) :form form :type type))

(s/defn test-with-meta-node :- s/Any
  [expr :- s/Any]
  {:op :with-meta :expr expr})

(s/defn test-static-call-node :- s/Any
  [class :- s/Any method :- s/Any]
  {:op :static-call :class class :method method})
