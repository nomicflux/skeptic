(ns skeptic.analysis.calls
  (:require [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang Util]))

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
  (let [arity (clojure.core/count args)
        direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)
        min-count count
        output-type (or output-type at/Dyn)]
    (if (or (and min-count varargs-res)
            direct-res)
      (let [res (if (and min-count (>= arity min-count)) varargs-res direct-res)
            raw-types (:types res)
            argtypes (if (seq raw-types)
                       (let [entries (mapv #(if (map? %) % {:type %}) raw-types)
                             c (clojure.core/count entries)]
                         (cond
                           (zero? c)
                           (vec (repeat arity at/Dyn))
                           (>= c arity)
                           (mapv arglist-entry-type (subvec entries 0 arity))
                           :else
                           (let [pad-n (- arity c)
                                 last-e (peek entries)]
                             (mapv arglist-entry-type
                                   (vec (concat entries
                                                (clojure.core/repeat pad-n last-e)))))))
                       (vec (repeat arity at/Dyn)))
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

(defn seq-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/seq 'seq} resolved)))

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

(def ^:private type-predicate-sym->pred
  '{clojure.core/nil? :nil?, nil? :nil?
    clojure.core/some? :some?, some? :some?
    clojure.core/string? :string?, string? :string?
    clojure.core/keyword? :keyword?, keyword? :keyword?
    clojure.core/integer? :integer?, integer? :integer?
    clojure.core/int? :integer?, int? :integer?
    clojure.core/number? :number?, number? :number?
    clojure.core/boolean? :boolean?, boolean? :boolean?
    clojure.core/symbol? :symbol?, symbol? :symbol?
    clojure.core/map? :map?, map? :map?
    clojure.core/vector? :vector?, vector? :vector?
    clojure.core/set? :set?, set? :set?
    clojure.core/seq? :seq?, seq? :seq?
    clojure.core/fn? :fn?, fn? :fn?})

(defn- resolved-call-sym
  [fn-node]
  (or (var->sym (:var fn-node))
      (:form fn-node)))

(defn- class-literal-node?
  [node]
  (and (= :const (:op node))
       (class? (:val node))))

(defn type-predicate-assumption-info
  [fn-node args]
  (let [sym (resolved-call-sym fn-node)
        n (count args)]
    (cond
      (contains? #{'clojure.core/instance? 'instance?} sym)
      (when (and (>= n 2)
                 (class-literal-node? (first args)))
        {:pred :instance?
         :class (:val (first args))})

      (contains? type-predicate-sym->pred sym)
      (when (= n 1)
        {:pred (type-predicate-sym->pred sym)})

      :else nil)))

(defn type-predicate-call?
  [fn-node args]
  (boolean (type-predicate-assumption-info fn-node args)))

(defn keyword-invoke-on-local?
  [node]
  (and (= :invoke (:op node))
       (let [fn-node (:fn node)
             a0 (first (:args node))]
         (and (#{:const :quote} (:op fn-node))
              (keyword? (literal-node-value fn-node))
              (= :local (:op a0))))))

(defn assoc-call?
  [fn-node]
  (contains? #{'clojure.core/assoc 'assoc} (resolved-call-sym fn-node)))

(defn dissoc-call?
  [fn-node]
  (contains? #{'clojure.core/dissoc 'dissoc} (resolved-call-sym fn-node)))

(defn update-call?
  [fn-node]
  (contains? #{'clojure.core/update 'update} (resolved-call-sym fn-node)))

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

(defn static-nil?-call?
  [node]
  (and (= Util (:class node))
       (= 'identical (:method node))))

(defn static-nil?-target
  [node]
  (let [[a b] (:args node)]
    (cond
      (and (= :const (:op b)) (nil? (:val b))) a
      (and (= :const (:op a)) (nil? (:val a))) b)))

(defn static-assoc-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/assoc 'assoc} (:method node))))

(defn static-dissoc-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/dissoc 'dissoc} (:method node))))

(defn static-update-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/update 'update} (:method node))))

(defn first-call?
  [fn-node]
  (contains? #{'clojure.core/first 'first} (resolved-call-sym fn-node)))

(defn second-call?
  [fn-node]
  (contains? #{'clojure.core/second 'second} (resolved-call-sym fn-node)))

(defn last-call?
  [fn-node]
  (contains? #{'clojure.core/last 'last} (resolved-call-sym fn-node)))

(defn nth-call?
  [fn-node]
  (contains? #{'clojure.core/nth 'nth} (resolved-call-sym fn-node)))

(defn rest-call?
  [fn-node]
  (contains? #{'clojure.core/rest 'rest} (resolved-call-sym fn-node)))

(defn butlast-call?
  [fn-node]
  (contains? #{'clojure.core/butlast 'butlast} (resolved-call-sym fn-node)))

(defn drop-last-call?
  [fn-node]
  (contains? #{'clojure.core/drop-last 'drop-last} (resolved-call-sym fn-node)))

(defn take-call?
  [fn-node]
  (contains? #{'clojure.core/take 'take} (resolved-call-sym fn-node)))

(defn drop-call?
  [fn-node]
  (contains? #{'clojure.core/drop 'drop} (resolved-call-sym fn-node)))

(defn take-while-call?
  [fn-node]
  (contains? #{'clojure.core/take-while 'take-while} (resolved-call-sym fn-node)))

(defn drop-while-call?
  [fn-node]
  (contains? #{'clojure.core/drop-while 'drop-while} (resolved-call-sym fn-node)))

(defn concat-call?
  [fn-node]
  (contains? #{'clojure.core/concat 'concat} (resolved-call-sym fn-node)))

(defn into-call?
  [fn-node]
  (contains? #{'clojure.core/into 'into} (resolved-call-sym fn-node)))

(defn chunk-first-call?
  [fn-node]
  (contains? #{'clojure.core/chunk-first 'chunk-first} (resolved-call-sym fn-node)))

(defn plus-invoke?
  [fn-node]
  (contains? #{'clojure.core/+ '+} (resolved-call-sym fn-node)))

(defn multiply-invoke?
  [fn-node]
  (contains? #{'clojure.core/* '*} (resolved-call-sym fn-node)))

(defn minus-invoke?
  [fn-node]
  (contains? #{'clojure.core/- '-} (resolved-call-sym fn-node)))

(defn inc-invoke?
  [fn-node]
  (contains? #{'clojure.core/inc 'inc} (resolved-call-sym fn-node)))

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
