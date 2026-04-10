(ns skeptic.analysis.annotate.api
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.bridge.render :as abr]))

(def ^:private legacy-schema-mirror-keys
  #{:schema :output :expected-arglist :actual-arglist})

(defmacro ^:private def-node-getters
  [& specs]
  `(do
     ~@(map (fn [[fname key]]
              `(defn ~fname [node#] (~key node#)))
            specs)))

(def-node-getters
  [node-op :op]
  [node-form :form]
  [node-type :type]
  [node-output-type :output-type]
  [node-fn-type :fn-type]
  [node-origin :origin]
  [node-var :var]
  [node-name :name]
  [node-class :class]
  [node-method :method]
  [node-value :val]
  [node-tag :tag]
  [node-raw-forms :raw-forms]
  [node-test :test]
  [node-body :body]
  [node-init :init]
  [node-expr :expr]
  [node-ret :ret]
  [node-bindings :bindings]
  [node-target :target]
  [node-keyword :keyword]
  [node-arglists :arglists]
  [node-arglist :arglist]
  [call-fn-node :fn]
  [call-args :args]
  [recur-args :exprs]
  [call-actual-argtypes :actual-argtypes]
  [call-expected-argtypes :expected-argtypes]
  [binding-init :binding-init])

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn node-info
  [node]
  (select-keys node
               [:type :output-type :arglists :arglist :expected-argtypes
                :actual-argtypes :fn-type :origin]))

(defn synthetic-binding-node
  [idx sym]
  {:op :binding
   :name sym
   :form sym
   :local :arg
   :arg-id idx})

(defn node-children
  [node]
  (mapv (fn [key] [key (get node key)]) (:children node)))

(defn annotated-nodes
  [node]
  (sac/ast-nodes node))

(defn find-node
  [root pred]
  (some #(when (pred %) %) (annotated-nodes root)))

(defn unwrap-with-meta
  [node]
  (if (= :with-meta (:op node))
    (recur (:expr node))
    node))

(defn local-node?
  [node]
  (= :local (:op node)))

(defn if-node?
  [node]
  (= :if (:op node)))

(defn let-node?
  [node]
  (= :let (:op node)))

(defn recur-node?
  [node]
  (= :recur (:op node)))

(def invoke-ops
  #{:instance-call :invoke :keyword-invoke :prim-invoke :protocol-invoke :static-call})

(defn call-node?
  [node]
  (or (and (contains? invoke-ops (:op node))
           (vector? (:args node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))
      (and (recur-node? node)
           (vector? (:exprs node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))))

(defn node-ref
  [node]
  (when node {:form (:form node) :type (:type node)}))

(defn callee-ref
  [node]
  (when node
    (case (:op node)
      :invoke (node-ref (:fn node))
      :with-meta (recur (:expr node))
      nil)))

(defn local-resolution-path
  [local-node]
  (let [init (:binding-init local-node)]
    (if init
      (cond-> [(node-ref init)]
        (callee-ref init) (conj (callee-ref init)))
      [])))

(defn local-vars-context
  [node]
  (reduce (fn [acc local-node]
            (if (contains? acc (:form local-node))
              acc
              (assoc acc
                     (:form local-node)
                     {:form (:form local-node)
                      :type (:type local-node)
                      :resolution-path (local-resolution-path local-node)})))
          {}
          (filter local-node? (annotated-nodes node))))

(defn call-refs
  [node]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (local-node? fn-node) (into [(node-ref fn-node)] (local-resolution-path fn-node))
      :else (cond-> [] (node-ref fn-node) (conj (node-ref fn-node))))))

(defn function-methods
  [node]
  (:methods node))

(defn method-body
  [node]
  (:body node))

(defn def-init-node
  [node]
  (:init node))

(defn then-node
  [node]
  (:then node))

(defn else-node
  [node]
  (:else node))

(defn branch-origin-kind
  [node]
  (get-in node [:origin :kind]))

(defn branch-test-assumption
  [node]
  (get-in node [:origin :test]))

(defn arglist-types
  [node arity]
  (mapv :type (get-in node [:arglists arity :types])))

(defn typed-call-metadata-only?
  [node]
  (and (contains? node :type)
       (contains? node :actual-argtypes)
       (or (contains? node :expected-argtypes)
           (contains? node :output-type)
           (contains? node :type))
       (not-any? #(contains? node %) legacy-schema-mirror-keys)))

(defn strip-derived-types
  [value]
  (abr/strip-derived-types value))

(defn def-node?
  [node]
  (= :def (:op node)))

(defn def-value-node
  [node]
  (let [init-node (some-> node def-init-node unwrap-with-meta)]
    (or (some-> init-node :expr unwrap-with-meta) init-node)))

(defn analyzed-def-entry
  [ns-sym analyzed]
  (let [node (unwrap-with-meta analyzed)
        value-node (def-value-node node)
        raw-name (some-> (:name node) name symbol)
        qualified-name (when (and raw-name ns-sym)
                         (symbol (str ns-sym "/" raw-name)))]
    (when (and (def-node? node) qualified-name value-node)
      [qualified-name
       (strip-derived-types
        (into {}
              (remove (comp nil? val))
              {:type (:type value-node)
               :output-type (:output-type value-node)
               :arglists (:arglists value-node)
               :arglist (:arglist value-node)}))])))

(defn method-result-type
  [method]
  {:body (method-body method) :output-type (:output-type method)})

(defn resolved-def-entry
  [resolved-defs sym]
  (get resolved-defs sym))

(defn resolved-def-output-type
  [resolved-defs sym]
  (some-> (resolved-def-entry resolved-defs sym) :output-type))
