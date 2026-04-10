(ns skeptic.analysis.calls
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang Util]))

(defn node-info
  [node]
  (aapi/node-info node))

(defn literal-map-key?
  [node]
  (contains? #{:const :quote} (aapi/node-op node)))

(defn literal-node-value
  [node]
  (case (aapi/node-op node)
    :const (aapi/node-value node)
    :quote (-> node aapi/node-form second)
    (aapi/node-form node)))

(defn map-literal-key-type
  [node]
  (if (literal-map-key? node)
    (av/exact-runtime-value-type (literal-node-value node))
    (or (aapi/node-type node) at/Dyn)))

(defn get-key-query
  [node]
  (if (literal-map-key? node)
    (amo/exact-key-query nil (literal-node-value node) (aapi/node-form node))
    (amo/domain-key-query (aapi/node-type node) (aapi/node-form node))))

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
                           [(aapi/node-form node)
                            (qualify-symbol ns-sym (aapi/node-form node))
                            (var->sym (aapi/node-var node))])]
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

(defn fun-type->call-opts
  [{:keys [methods]}]
  (let [arglists (into {}
                       (map (fn [{:keys [inputs output min-arity variadic?]}]
                              (if variadic?
                                [:varargs {:count min-arity :types inputs}]
                                [min-arity {:types inputs}])))
                       methods)
        output-type (some :output methods)]
    {:arglists arglists
     :output-type (or output-type at/Dyn)}))

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
  (and (aapi/node-arglists fn-node)
       (some typed-arglist-entry?
             (vals (aapi/node-arglists fn-node)))))

(defn seq-call?
  [fn-node]
  (let [resolved (or (var->sym (aapi/node-var fn-node))
                     (aapi/node-form fn-node))]
    (contains? #{'clojure.core/seq 'seq} resolved)))

(defn merge-call?
  [fn-node]
  (let [resolved (or (var->sym (aapi/node-var fn-node))
                     (aapi/node-form fn-node))]
    (contains? #{'clojure.core/merge 'merge} resolved)))

(defn contains-call?
  [fn-node]
  (let [resolved (or (var->sym (aapi/node-var fn-node))
                     (aapi/node-form fn-node))]
    (contains? #{'clojure.core/contains? 'contains? 'contains} resolved)))

(defn get-call?
  [fn-node]
  (let [resolved (or (var->sym (aapi/node-var fn-node))
                     (aapi/node-form fn-node))]
    (contains? #{'clojure.core/get 'get} resolved)))

(defn blank-call?
  [fn-node]
  (let [resolved (or (var->sym (aapi/node-var fn-node))
                     (aapi/node-form fn-node))]
    (contains? #{'clojure.string/blank? 'blank?} resolved)))

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
  (or (var->sym (aapi/node-var fn-node))
      (aapi/node-form fn-node)))

(defn- class-literal-node?
  [node]
  (and (= :const (aapi/node-op node))
       (class? (aapi/node-value node))))

(defn type-predicate-assumption-info
  [fn-node args]
  (let [sym (resolved-call-sym fn-node)
        n (count args)]
    (cond
      (contains? #{'clojure.core/instance? 'instance?} sym)
      (when (and (>= n 2)
                 (class-literal-node? (first args)))
        {:pred :instance?
         :class (aapi/node-value (first args))})

      (contains? type-predicate-sym->pred sym)
      (when (= n 1)
        {:pred (type-predicate-sym->pred sym)})

      :else nil)))

(defn type-predicate-call?
  [fn-node args]
  (boolean (type-predicate-assumption-info fn-node args)))

(defn keyword-invoke-on-local?
  "True for `(:k x)` as either JVM analyzer `:invoke` or `:keyword-invoke`."
  [node]
  (or (and (= :invoke (aapi/node-op node))
           (let [fn-node (aapi/call-fn-node node)
                 a0 (first (aapi/call-args node))]
             (and (#{:const :quote} (aapi/node-op fn-node))
                  (keyword? (literal-node-value fn-node))
                  (= :local (aapi/node-op a0)))))
      (and (= :keyword-invoke (aapi/node-op node))
           (= :local (aapi/node-op (aapi/node-target node))))))

(defn keyword-invoke-kw-and-target
  "When `keyword-invoke-on-local?`, returns `[kw-keyword target-node]`."
  [node]
  (cond
    (= :keyword-invoke (aapi/node-op node))
    (when (= :local (aapi/node-op (aapi/node-target node)))
      [(literal-node-value (aapi/node-keyword node)) (aapi/node-target node)])

    (and (= :invoke (aapi/node-op node))
         (let [fn-node (aapi/call-fn-node node)
               a0 (first (aapi/call-args node))]
           (and (#{:const :quote} (aapi/node-op fn-node))
                (keyword? (literal-node-value fn-node))
                (= :local (aapi/node-op a0)))))
    [(literal-node-value (aapi/call-fn-node node)) (first (aapi/call-args node))]

    :else
    nil))

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
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/get 'get} (aapi/node-method node))))

(defn static-merge-call?
  [node]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/merge 'merge} (aapi/node-method node))))

(defn static-contains-call?
  [node]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/contains? 'contains? 'contains} (aapi/node-method node))))

(defn static-nil?-call?
  [node]
  (and (= Util (aapi/node-class node))
       (= 'identical (aapi/node-method node))))

(defn static-nil?-target
  [node]
  (let [[a b] (aapi/call-args node)]
    (cond
      (and (= :const (aapi/node-op b)) (nil? (aapi/node-value b))) a
      (and (= :const (aapi/node-op a)) (nil? (aapi/node-value a))) b)))

(defn static-assoc-call?
  [node]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/assoc 'assoc} (aapi/node-method node))))

(defn static-dissoc-call?
  [node]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/dissoc 'dissoc} (aapi/node-method node))))

(defn static-update-call?
  [node]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/update 'update} (aapi/node-method node))))

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
                                      {:arglists (aapi/node-arglists fn-node)
                                       :output-type (or (aapi/node-output-type fn-node) at/Dyn)})]
      {:expected-argtypes (:argtypes converted)
       :output-type (or (:output-type converted) at/Dyn)
       :fn-type (:type converted)})
    (default-call-info (count args) (aapi/node-output-type fn-node))))
