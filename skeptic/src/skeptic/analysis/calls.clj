(ns skeptic.analysis.calls
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.resolvers :as ar]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(defn node-info
  [node]
  (select-keys node [:type :output-type :arglists :arglist :expected-argtypes :actual-argtypes :fn-type
                     :schema :output :expected-arglist :actual-arglist :fn-schema :origin]))

(defn literal-map-key?
  [node]
  (contains? #{:const :quote} (:op node)))

(defn semantic-map-key
  [node]
  (ab/normalize-type
   (if (literal-map-key? node)
     (:form node)
     (:type node))))

(defn get-key-query
  [node]
  (if (literal-map-key? node)
    (asm/exact-key-query (:type node) (:form node) (:form node))
    (asm/domain-key-query (:type node) (:form node))))

(defn local-binding-ast
  [idx sym]
  {:op :binding
   :name sym
   :form sym
   :local :arg
   :arg-id idx})

(defn var->sym
  [var]
  (when (instance? clojure.lang.Var var)
    (let [m (meta var)]
      (symbol (str (ns-name (:ns m)) "/" (:name m))))))

(defn qualify-symbol
  [ns-sym sym]
  (cond
    (nil? sym) nil
    (not (symbol? sym)) sym
    (namespace sym) sym
    ns-sym (symbol (str ns-sym "/" sym))
    :else sym))

(defn lookup-entry
  [dict ns-sym node]
  (let [candidates (remove nil?
                           [(:form node)
                            (qualify-symbol ns-sym (:form node))
                            (var->sym (:var node))])]
    (some (comp an/normalize-entry dict) candidates)))

(defn default-call-info
  [arity output]
  (let [output-type (or output at/Dyn)
        expected-argtypes (vec (repeat arity at/Dyn))
        fn-type (an/normalize-declared-type (sb/dynamic-fn-schema arity s/Any))]
    {:expected-argtypes expected-argtypes
     :expected-arglist (an/compat-schemas expected-argtypes)
     :output-type output-type
     :output (an/compat-schema output-type)
     :fn-type fn-type
     :fn-schema (an/compat-schema fn-type)}))

(defn typed-arglist-entry?
  [{:keys [types]}]
  (boolean (seq types)))

(defn typed-callable?
  [fn-node]
  (and (:arglists fn-node)
       (some typed-arglist-entry?
             (vals (:arglists fn-node)))))

(defn merge-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/merge 'merge} resolved)))

(defn contains-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/contains? 'contains? 'contains} resolved)))

(defn get-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/get 'get} resolved)))

(defn static-get-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/get 'get} (:method node))))

(defn static-merge-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/merge 'merge} (:method node))))

(defn static-contains-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/contains? 'contains? 'contains} (:method node))))

(defn call-info
  [fn-node args]
  (if (typed-callable? fn-node)
    (let [converted (ar/convert-arglists args
                                         {:arglists (:arglists fn-node)
                                          :output-type (or (:output-type fn-node) at/Dyn)})]
      {:expected-argtypes (:argtypes converted)
       :expected-arglist (an/compat-schemas (:argtypes converted))
       :output-type (or (:output-type converted) at/Dyn)
       :output (an/compat-schema (or (:output-type converted) at/Dyn))
       :fn-type (:type converted)
       :fn-schema (an/compat-schema (:type converted))})
    (default-call-info (count args) (:output-type fn-node))))
