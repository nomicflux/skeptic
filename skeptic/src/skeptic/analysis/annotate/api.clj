(ns skeptic.analysis.annotate.api
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def ^:private legacy-schema-mirror-keys
  #{:schema :output :expected-arglist :actual-arglist})

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn node-op
  [node]
  (:op node))

(defn node-form
  [node]
  (:form node))

(defn node-type
  [node]
  (:type node))

(defn node-output-type
  [node]
  (:output-type node))

(defn node-fn-type
  [node]
  (:fn-type node))

(defn node-origin
  [node]
  (:origin node))

(defn node-var
  [node]
  (:var node))

(defn node-name
  [node]
  (:name node))

(defn node-class
  [node]
  (:class node))

(defn node-method
  [node]
  (:method node))

(defn node-value
  [node]
  (:val node))

(defn node-tag
  [node]
  (:tag node))

(defn node-raw-forms
  [node]
  (:raw-forms node))

(defn node-test
  [node]
  (:test node))

(defn node-body
  [node]
  (:body node))

(defn node-init
  [node]
  (:init node))

(defn node-expr
  [node]
  (:expr node))

(defn node-ret
  [node]
  (:ret node))

(defn node-bindings
  [node]
  (:bindings node))

(defn node-target
  [node]
  (:target node))

(defn node-keyword
  [node]
  (:keyword node))

(defn node-literal?
  [node]
  (:literal? node))

(defn node-validated?
  [node]
  (:validated? node))

(defn node-body?
  [node]
  (:body? node))

(defn node-local-kind
  [node]
  (:local node))

(defn node-arg-id
  [node]
  (:arg-id node))

(defn node-variadic?
  [node]
  (:variadic? node))

(defn synthetic-binding-node
  [idx sym]
  {:op :binding
   :name sym
   :form sym
   :local :arg
   :arg-id idx})

(defn node-children
  [node]
  (mapv (fn [key]
          [key (get node key)])
        (:children node)))

(defn child-node
  [node key]
  (->> (node-children node)
       (some (fn [[child-key child]]
               (when (= child-key key) child)))))

(defn annotated-nodes
  [node]
  (sac/ast-nodes node))

(defn find-node
  [root pred]
  (some #(when (pred %) %) (annotated-nodes root)))

(defn find-node-by-form
  [root form]
  (find-node root #(= form (node-form %))))

(defn unwrap-with-meta
  [node]
  (if (= :with-meta (node-op node))
    (recur (:expr node))
    node))

(defn def-node?
  [node]
  (= :def (node-op node)))

(defn fn-node?
  [node]
  (= :fn (node-op node)))

(defn if-node?
  [node]
  (= :if (node-op node)))

(defn let-node?
  [node]
  (= :let (node-op node)))

(defn local-node?
  [node]
  (= :local (node-op node)))

(defn recur-node?
  [node]
  (= :recur (node-op node)))

(def invoke-ops
  #{:instance-call
    :invoke
    :keyword-invoke
    :prim-invoke
    :protocol-invoke
    :static-call})

(defn call-node?
  [node]
  (or (and (contains? invoke-ops (node-op node))
           (vector? (:args node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))
      (and (recur-node? node)
           (vector? (:exprs node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))))

(defn node-ref
  [node]
  (when node
    {:form (node-form node)
     :type (node-type node)}))

(defn call-fn-node
  [node]
  (:fn node))

(defn call-args
  [node]
  (:args node))

(defn recur-args
  [node]
  (:exprs node))

(defn call-actual-argtypes
  [node]
  (:actual-argtypes node))

(defn call-expected-argtypes
  [node]
  (:expected-argtypes node))

(defn callee-ref
  [node]
  (when node
    (case (node-op node)
      :invoke (node-ref (call-fn-node node))
      :with-meta (recur (:expr node))
      nil)))

(defn binding-init
  [node]
  (:binding-init node))

(defn fn-binding-node
  [node]
  (:fn-binding-node node))

(defn local-resolution-path
  [local-node]
  (let [init (binding-init local-node)]
    (if init
      (cond-> [(node-ref init)]
        (callee-ref init)
        (conj (callee-ref init)))
      [])))

(defn local-vars-context
  [node]
  (->> (annotated-nodes node)
       (filter local-node?)
       (reduce (fn [acc local-node]
                 (if (contains? acc (node-form local-node))
                   acc
                   (assoc acc
                          (node-form local-node)
                          {:form (node-form local-node)
                           :type (node-type local-node)
                           :resolution-path (local-resolution-path local-node)})))
               {})))

(defn call-refs
  [node]
  (let [fn-node (call-fn-node node)]
    (cond
      (nil? fn-node) []
      (local-node? fn-node)
      (into [(node-ref fn-node)]
            (local-resolution-path fn-node))
      :else
      (cond-> []
        (node-ref fn-node)
        (conj (node-ref fn-node))))))

(defn node-arglists
  [node]
  (:arglists node))

(defn node-arglist
  [node]
  (:arglist node))

(defn node-param-specs
  [node]
  (:param-specs node))

(defn arglist-types
  [node arity]
  (-> node
      node-arglists
      (get arity)
      :types
      (->> (mapv :type))))

(defn function-methods
  [node]
  (:methods node))

(defn method-body
  [node]
  (:body node))

(defn def-init-node
  [node]
  (:init node))

(defn def-value-node
  [node]
  (let [init-node (some-> node def-init-node unwrap-with-meta)]
    (or (some-> init-node :expr unwrap-with-meta)
        init-node)))

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

(defn node-info
  [node]
  (select-keys node
               [:type :output-type :arglists :arglist :expected-argtypes
                :actual-argtypes :fn-type :origin]))

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

(defn analyzed-def-entry
  [ns-sym analyzed]
  (let [node (unwrap-with-meta analyzed)
        value-node (def-value-node node)
        raw-name (some-> (node-name node) name symbol)
        qualified-name (when (and raw-name ns-sym)
                         (symbol (str ns-sym "/" raw-name)))]
    (when (and (def-node? node)
               qualified-name
               value-node)
      [qualified-name
       (strip-derived-types
        (into {}
              (remove (comp nil? val))
              {:type (node-type value-node)
               :output-type (node-output-type value-node)
               :arglists (node-arglists value-node)
               :arglist (node-arglist value-node)}))])))

(defn method-result-type
  [method]
  {:body (method-body method)
   :output-type (node-output-type method)})

(defn resolved-def-entry
  [resolved-defs sym]
  (get resolved-defs sym))

(defn resolved-def-output-type
  [resolved-defs sym]
  (some-> (resolved-def-entry resolved-defs sym)
          :output-type))
