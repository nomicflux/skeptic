(ns skeptic.analysis.annotate.api
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn dyn          [ctx] (at/Dyn (prov/with-ctx ctx)))
(defn bottom       [ctx] (at/BottomType (prov/with-ctx ctx)))
(defn numeric-dyn  [ctx] (at/NumericDyn (prov/with-ctx ctx)))

(defn normalize-type
  [ctx value]
  (ato/normalize-type (prov/with-ctx ctx) value))

(defn union-type
  [ctx members]
  (ato/union-type (prov/with-ctx ctx) members))

(defn intersection-type
  [ctx members]
  (ato/intersection-type (prov/with-ctx ctx) members))

(defn exact-value-type
  [ctx value]
  (ato/exact-value-type (prov/with-ctx ctx) value))

(defn de-maybe-type
  [ctx value]
  (ato/de-maybe-type (prov/with-ctx ctx) value))

(defn unknown-type?
  [ctx value]
  (ato/unknown-type? (prov/with-ctx ctx) value))

(def ^:private legacy-schema-mirror-keys
  #{:schema :output :expected-arglist :actual-arglist})

(defn node-op [node] (:op node))
(defn node-form [node] (:form node))
(defn node-type [node] (:type node))
(defn node-output-type [node] (:output-type node))
(defn node-fn-type [node] (:fn-type node))
(defn node-origin [node] (:origin node))
(defn node-var [node] (:var node))
(defn node-name [node] (:name node))
(defn node-class [node] (:class node))
(defn node-method [node] (:method node))
(defn node-value [node] (:val node))
(defn node-tag [node] (:tag node))
(defn node-raw-forms [node] (:raw-forms node))
(defn node-test [node] (:test node))
(defn node-body [node] (:body node))
(defn node-init [node] (:init node))
(defn node-expr [node] (:expr node))
(defn node-ret [node] (:ret node))
(defn node-bindings [node] (:bindings node))
(defn node-target [node] (:target node))
(defn node-keyword [node] (:keyword node))
(defn node-arglists [node] (:arglists node))
(defn node-arglist [node] (:arglist node))
(defn call-fn-node [node] (:fn node))
(defn call-args [node] (:args node))
(defn recur-args [node] (:exprs node))
(defn call-actual-argtypes [node] (:actual-argtypes node))
(defn call-expected-argtypes [node] (:expected-argtypes node))
(defn binding-init [node] (:binding-init node))

(defn with-type
  "Public setter for the inferred value-type of an annotated node.
   Use this (not raw (assoc node :type ...)) from any code that does not own node shape."
  [node type]
  (assoc node :type type))

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

(defn stable-identity-node?
  "True for any node that names a stable identity for narrowing
   purposes: lexical locals, top-level vars, and var-ref forms."
  [node]
  (or (local-node? node)
      (= :var (:op node))
      (= :the-var (:op node))))

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

(defn node-arg-names-at-arity
  [node arity]
  (when (at/fun-type? (:type node))
    (some-> (at/select-method (at/fun-methods (:type node)) arity)
            at/fn-method-input-names)))

(defn node-fun-type
  [node]
  (when (at/fun-type? (:type node))
    (:type node)))

(defn node-method-output
  [node arity]
  (when-let [ft (node-fun-type node)]
    (some-> (at/select-method (at/fun-methods ft) arity) :output)))

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
