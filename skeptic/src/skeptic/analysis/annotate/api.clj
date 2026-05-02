(ns skeptic.analysis.annotate.api
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]))

(s/defn dyn :- ats/SemanticType [ctx] (at/Dyn (prov/with-ctx ctx)))
(s/defn bottom :- ats/SemanticType [ctx] (at/BottomType (prov/with-ctx ctx)))
(s/defn numeric-dyn :- ats/SemanticType [ctx] (at/NumericDyn (prov/with-ctx ctx)))

(s/defn normalize-type :- ats/SemanticType
  [ctx value]
  (ato/normalize-type (prov/with-ctx ctx) value))

(s/defn union-type :- ats/SemanticType
  [ctx members]
  (ato/union-type (prov/with-ctx ctx) members))

(s/defn intersection-type :- ats/SemanticType
  [ctx members]
  (ato/intersection-type (prov/with-ctx ctx) members))

(s/defn exact-value-type :- ats/SemanticType
  [ctx value]
  (ato/exact-value-type (prov/with-ctx ctx) value))

(s/defn de-maybe-type :- ats/SemanticType
  [ctx value]
  (ato/de-maybe-type (prov/with-ctx ctx) value))

(def ^:private legacy-schema-mirror-keys
  #{:schema :output :expected-arglist :actual-arglist})

(s/defn node-op :- s/Keyword [node :- aas/AnnotatedNode] (:op node))
(s/defn node-form :- s/Any [node :- aas/AnnotatedNode] (:form node))
(s/defn node-type :- (s/maybe ats/SemanticType) [node :- aas/AnnotatedNode] (:type node))
(s/defn node-output-type :- (s/maybe ats/SemanticType) [node :- aas/AnnotatedNode] (:output-type node))
(s/defn node-fn-type :- (s/maybe ats/SemanticType) [node :- aas/AnnotatedNode] (:fn-type node))
(s/defn node-origin :- (s/maybe aos/Origin) [node :- aas/AnnotatedNode] (:origin node))
(s/defn node-var :- s/Any [node :- aas/AnnotatedNode] (:var node))
(s/defn node-name :- s/Any [node :- aas/AnnotatedNode] (:name node))
(s/defn node-class :- s/Any [node :- aas/AnnotatedNode] (:class node))
(s/defn node-method :- s/Any [node :- aas/AnnotatedNode] (:method node))
(s/defn node-value :- s/Any [node :- aas/AnnotatedNode] (:val node))
(s/defn node-tag :- s/Any [node :- aas/AnnotatedNode] (:tag node))
(s/defn node-raw-forms :- s/Any [node :- aas/AnnotatedNode] (:raw-forms node))
(s/defn node-test :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:test node))
(s/defn node-body :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:body node))
(s/defn node-init :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:init node))
(s/defn node-expr :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:expr node))
(s/defn node-ret :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:ret node))
(s/defn node-bindings :- [aas/AnnotatedNode] [node :- aas/AnnotatedNode] (:bindings node))
(s/defn node-target :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:target node))
(s/defn node-keyword :- s/Any [node :- aas/AnnotatedNode] (:keyword node))
(s/defn node-arglist :- s/Any [node :- aas/AnnotatedNode] (:arglist node))
(s/defn call-fn-node :- (s/maybe aas/AnnotatedNode) [node :- aas/AnnotatedNode] (:fn node))
(s/defn call-args :- [aas/AnnotatedNode] [node :- aas/AnnotatedNode] (:args node))
(s/defn recur-args :- [aas/AnnotatedNode] [node :- aas/AnnotatedNode] (:exprs node))
(s/defn call-actual-argtypes :- [ats/SemanticType] [node :- aas/AnnotatedNode] (:actual-argtypes node))
(s/defn call-expected-argtypes :- [ats/SemanticType] [node :- aas/AnnotatedNode] (:expected-argtypes node))
(s/defn binding-init :- (s/maybe aas/AnnotatedNode) [holder :- aas/BindingHolder] (:binding-init holder))

(s/defn with-type :- s/Any
  "Public setter for the inferred value-type of an annotated node.
   Use this (not raw (assoc node :type ...)) from any code that does not own node shape."
  [node :- aas/AnnotatedNode type :- ats/SemanticType]
  (assoc node :type type))

(s/defn node-location :- s/Any
  [node :- aas/AnnotatedNode]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(s/defn node-info :- s/Any
  [node :- aas/AnnotatedNode]
  (select-keys node
               [:type :output-type :arglists :arglist :expected-argtypes
                :actual-argtypes :fn-type :origin]))

(s/defn synthetic-binding-node :- aas/AnnotatedNode
  [idx :- s/Any sym :- s/Any]
  {:op :binding
   :name sym
   :form sym
   :local :arg
   :arg-id idx})

(s/defn node-children :- s/Any
  [node :- aas/AnnotatedNode]
  (mapv (fn [key] [key (get node key)]) (:children node)))

(s/defn annotated-nodes :- s/Any
  [node :- aas/AnnotatedNode]
  (sac/ast-nodes node))

(s/defn find-node :- s/Any
  [root :- aas/AnnotatedNode pred :- s/Any]
  (some #(when (pred %) %) (annotated-nodes root)))

(s/defn unwrap-with-meta :- s/Any
  [node :- aas/AnnotatedNode]
  (if (= :with-meta (:op node))
    (recur (:expr node))
    node))

(s/defn local-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (= :local (:op node)))

(s/defn stable-identity-node? :- s/Any
  "True for any node that names a stable identity for narrowing
   purposes: lexical locals, top-level vars, and var-ref forms."
  [node :- aas/AnnotatedNode]
  (or (local-node? node)
      (= :var (:op node))
      (= :the-var (:op node))))

(s/defn if-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (= :if (:op node)))

(s/defn let-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (= :let (:op node)))

(s/defn recur-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (= :recur (:op node)))

(def invoke-ops
  #{:instance-call :invoke :keyword-invoke :prim-invoke :protocol-invoke :static-call})

(s/defn call-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (or (and (contains? invoke-ops (:op node))
           (vector? (:args node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))
      (and (recur-node? node)
           (vector? (:exprs node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))))

(s/defn node-ref :- s/Any
  [node :- (s/maybe aas/AnnotatedNode)]
  (when node {:form (:form node) :type (:type node)}))

(s/defn callee-ref :- s/Any
  [node :- (s/maybe aas/AnnotatedNode)]
  (when node
    (case (:op node)
      :invoke (node-ref (:fn node))
      :with-meta (recur (:expr node))
      nil)))

(s/defn local-resolution-path :- s/Any
  [local-node :- aas/AnnotatedNode]
  (let [init (:binding-init local-node)]
    (if init
      (cond-> [(node-ref init)]
        (callee-ref init) (conj (callee-ref init)))
      [])))

(s/defn local-vars-context :- s/Any
  [node :- aas/AnnotatedNode]
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

(s/defn call-refs :- s/Any
  [node :- aas/AnnotatedNode]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (local-node? fn-node) (into [(node-ref fn-node)] (local-resolution-path fn-node))
      :else (cond-> [] (node-ref fn-node) (conj (node-ref fn-node))))))

(s/defn function-methods :- s/Any
  [node :- aas/AnnotatedNode]
  (:methods node))

(s/defn method-body :- s/Any
  [node :- aas/AnnotatedNode]
  (:body node))

(s/defn def-init-node :- s/Any
  [node :- aas/AnnotatedNode]
  (:init node))

(s/defn then-node :- s/Any
  [node :- aas/AnnotatedNode]
  (:then node))

(s/defn else-node :- s/Any
  [node :- aas/AnnotatedNode]
  (:else node))

(s/defn branch-origin-kind :- (s/maybe s/Keyword)
  [node :- aas/AnnotatedNode]
  (get-in node [:origin :kind]))

(s/defn branch-test-assumption :- (s/maybe aos/Assumption)
  [node :- aas/AnnotatedNode]
  (get-in node [:origin :test]))

(s/defn arglist-types :- s/Any
  [node :- aas/AnnotatedNode arity :- s/Any]
  (mapv :type (get-in node [:arglists arity :types])))

(s/defn typed-call-metadata-only? :- s/Any
  [node :- aas/AnnotatedNode]
  (and (contains? node :type)
       (contains? node :actual-argtypes)
       (or (contains? node :expected-argtypes)
           (contains? node :output-type)
           (contains? node :type))
       (not-any? #(contains? node %) legacy-schema-mirror-keys)))

(s/defn strip-derived-types :- s/Any
  [value :- s/Any]
  (abr/strip-derived-types value))

(s/defn node-arg-names-at-arity :- s/Any
  [node :- aas/AnnotatedNode arity :- s/Any]
  (when (at/fun-type? (:type node))
    (some-> (at/select-method (at/fun-methods (:type node)) arity)
            at/fn-method-input-names)))

(s/defn node-fun-type :- s/Any
  [node :- aas/AnnotatedNode]
  (when (at/fun-type? (:type node))
    (:type node)))

(s/defn node-method-output :- s/Any
  [node :- aas/AnnotatedNode arity :- s/Any]
  (when-let [ft (node-fun-type node)]
    (some-> (at/select-method (at/fun-methods ft) arity) :output)))

(s/defn def-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (= :def (:op node)))

(s/defn def-value-node :- s/Any
  [node :- aas/AnnotatedNode]
  (let [init-node (some-> node def-init-node unwrap-with-meta)]
    (or (some-> init-node :expr unwrap-with-meta) init-node)))

(s/defn analyzed-def-entry :- s/Any
  [ns-sym :- s/Any analyzed :- s/Any]
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

(s/defn method-result-type :- s/Any
  [method :- s/Any]
  {:body (method-body method) :output-type (:output-type method)})

(s/defn resolved-def-entry :- s/Any
  [resolved-defs :- s/Any sym :- s/Any]
  (get resolved-defs sym))

(s/defn resolved-def-output-type :- s/Any
  [resolved-defs :- s/Any sym :- s/Any]
  (some-> (resolved-def-entry resolved-defs sym) :output-type))
