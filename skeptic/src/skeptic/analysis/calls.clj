(ns skeptic.analysis.calls
  (:require [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.types :as at]))

(defn node-info
  [node]
  (select-keys node [:type :output-type :arglists :arglist :expected-argtypes :actual-argtypes :fn-type
                     :origin]))

(defn literal-map-key?
  [node]
  (contains? #{:const :quote} (:op node)))

(defn literal-node-value
  [node]
  (case (:op node)
    :const (:val node)
    :quote (-> node :form second)
    (:form node)))

(defn map-literal-key-type
  [node]
  (if (literal-map-key? node)
    (av/exact-runtime-value-type (literal-node-value node))
    (or (:type node) at/Dyn)))

(defn get-key-query
  [node]
  (if (literal-map-key? node)
    (amo/exact-key-query nil (literal-node-value node) (:form node))
    (amo/domain-key-query (:type node) (:form node))))

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

(defn- arglist-entry-type
  [{:keys [type] :as entry}]
  (or type entry at/Dyn))

(defn- convert-arglists
  [args {:keys [arglists output-type]}]
  (let [arity (count args)
        direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)
        min-count count
        output-type (or output-type at/Dyn)]
    (if (or (and min-count varargs-res)
            direct-res)
      (let [res (if (and min-count (>= arity min-count)) varargs-res direct-res)
            argtypes (mapv arglist-entry-type
                           (or (:types res)
                               (vec (repeat arity at/Dyn))))
            fn-type (at/->FunT [(at/->FnMethodT argtypes
                                               output-type
                                               (clojure.core/count argtypes)
                                               false)])]
        {:type fn-type
         :fn-type fn-type
         :output-type output-type
         :argtypes argtypes
         :arglist argtypes})
      (let [argtypes (vec (repeat arity at/Dyn))
            fn-type (at/->FunT [(at/->FnMethodT argtypes
                                               output-type
                                               arity
                                               false)])]
        {:type fn-type
         :fn-type fn-type
         :output-type output-type
         :argtypes argtypes
         :arglist argtypes}))))

(defn default-call-info
  [arity output]
  (let [output-type (or output at/Dyn)
        expected-argtypes (vec (repeat arity at/Dyn))
        fn-type (at/->FunT [(at/->FnMethodT expected-argtypes
                                           output-type
                                           arity
                                           false)])]
    {:expected-argtypes expected-argtypes
     :output-type output-type
     :fn-type fn-type}))

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
    (let [converted (convert-arglists args
                                      {:arglists (:arglists fn-node)
                                       :output-type (or (:output-type fn-node) at/Dyn)})]
      {:expected-argtypes (:argtypes converted)
       :output-type (or (:output-type converted) at/Dyn)
       :fn-type (:type converted)})
    (default-call-info (count args) (:output-type fn-node))))
