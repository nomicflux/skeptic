(ns skeptic.analysis.calls
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov])
  (:import [clojure.lang Util]))

(s/defn node-info :- s/Any
  [node :- s/Any]
  (aapi/node-info node))

(s/defn literal-map-key? :- s/Bool
  [node :- s/Any]
  (contains? #{:const :quote} (aapi/node-op node)))

(s/defn literal-node-value :- s/Any
  [node :- s/Any]
  (case (aapi/node-op node)
    :const (aapi/node-value node)
    :quote (-> node aapi/node-form second)
    (aapi/node-form node)))

(s/defn map-literal-key-type :- at/SemanticType
  [ctx :- s/Any
   node :- s/Any]
  (if (literal-map-key? node)
    (aapi/exact-value-type ctx (literal-node-value node))
    (or (aapi/node-type node) (aapi/dyn ctx))))

(s/defn get-key-query :- s/Any
  [ctx :- s/Any
   node :- s/Any]
  (if (literal-map-key? node)
    (amo/exact-key-query (prov/with-ctx ctx) (literal-node-value node) (aapi/node-form node))
    (amo/domain-key-query (or (aapi/node-type node) (aapi/dyn ctx)) (aapi/node-form node))))

(s/defn var->sym :- (s/maybe s/Symbol)
  [var :- s/Any]
  (when (instance? clojure.lang.Var var)
    (let [m (meta var)]
      (symbol (str (ns-name (:ns m)) "/" (:name m))))))

(defn- cljs-var-node-sym
  [node]
  (when (map? node)
    (or (aapi/node-info-name node)
        (aapi/node-name node))))

(defn- node-var-sym
  [node]
  (or (var->sym (aapi/node-var node))
      (cljs-var-node-sym (aapi/node-var node))))

(s/defn qualify-symbol :- s/Any
  [ns-sym :- s/Any
   sym :- s/Any]
  (cond
    (nil? sym) nil
    (not (symbol? sym)) sym
    (namespace sym) sym
    ns-sym (symbol (str ns-sym "/" sym))
    :else sym))

(s/defn lookup-type :- s/Any
  [dict :- s/Any
   ns-sym :- (s/maybe s/Symbol)
   node :- s/Any]
  (let [candidates (remove nil?
                           [(aapi/node-form node)
                            (qualify-symbol ns-sym (aapi/node-form node))
                            (aapi/node-name node)
                            (qualify-symbol ns-sym (aapi/node-name node))
                            (node-var-sym node)
                            (aapi/node-info-name node)])]
    (some dict candidates)))

(s/defn lookup-summary :- s/Any
  [accessor-summaries :- s/Any
   ns-sym :- (s/maybe s/Symbol)
   node :- s/Any]
  (let [candidates (remove nil?
                           [(aapi/node-form node)
                            (qualify-symbol ns-sym (aapi/node-form node))
                            (node-var-sym node)
                            (aapi/node-info-name node)])]
    (some accessor-summaries candidates)))

(s/defschema CallOptsArglistEntry
  {:types                    [at/SemanticType]
   (s/optional-key :count)   s/Int})

(s/defschema CallOpts
  {:arglists    {(s/cond-pre s/Int (s/eq :varargs)) CallOptsArglistEntry}
   :output-type at/SemanticType})

(s/defn fun-type->call-opts :- CallOpts
  [fun-type :- at/SemanticType]
  (let [{:keys [methods]} fun-type
        arglists (into {}
                       (map (fn [{:keys [inputs min-arity variadic?]}]
                              (if variadic?
                                [:varargs {:count min-arity :types inputs}]
                                [min-arity {:types inputs}])))
                       methods)
        output-type (some :output methods)]
    {:arglists arglists
     :output-type (or output-type (at/Dyn (prov/of fun-type)))}))

(s/defn default-call-info :- s/Any
  [ctx :- s/Any
   arity :- s/Int
   output :- (s/maybe at/SemanticType)]
  (let [output-type (or output (aapi/dyn ctx))
        prov (ato/derive-prov output-type)
        expected-argtypes (vec (repeat arity (at/Dyn prov)))
        fn-type (at/->FunT prov
                           [(at/->FnMethodT prov
                                            expected-argtypes
                                            output-type
                                            arity
                                            false
                                            (mapv #(symbol (str "arg" %)) (range arity)))])]
    {:expected-argtypes expected-argtypes
     :output-type output-type
     :fn-type fn-type}))


(s/defn typed-callable? :- s/Bool
  [fn-node :- s/Any]
  (boolean (aapi/node-fun-type fn-node)))

(def seq-call-syms '#{clojure.core/seq seq})
(def merge-call-syms '#{clojure.core/merge merge})
(def contains-call-syms '#{clojure.core/contains? contains? contains})
(def get-call-syms '#{clojure.core/get get})
(def assoc-call-syms '#{clojure.core/assoc assoc})
(def dissoc-call-syms '#{clojure.core/dissoc dissoc})
(def update-call-syms '#{clojure.core/update update})
(def first-call-syms '#{clojure.core/first first})
(def second-call-syms '#{clojure.core/second second})
(def last-call-syms '#{clojure.core/last last})
(def nth-call-syms '#{clojure.core/nth nth})
(def rest-call-syms '#{clojure.core/rest rest})
(def butlast-call-syms '#{clojure.core/butlast butlast})
(def drop-last-call-syms '#{clojure.core/drop-last drop-last})
(def take-call-syms '#{clojure.core/take take})
(def drop-call-syms '#{clojure.core/drop drop})
(def take-while-call-syms '#{clojure.core/take-while take-while})
(def drop-while-call-syms '#{clojure.core/drop-while drop-while})
(def concat-call-syms '#{clojure.core/concat concat})
(def into-call-syms '#{clojure.core/into into})
(def chunk-first-call-syms '#{clojure.core/chunk-first chunk-first})
(def plus-invoke-syms '#{clojure.core/+ +})
(def multiply-invoke-syms '#{clojure.core/* *})
(def minus-invoke-syms '#{clojure.core/- -})
(def inc-invoke-syms '#{clojure.core/inc inc})
(def not-call-syms '#{clojure.core/not not})
(def equality-call-syms '#{clojure.core/= =})
(def blank-call-syms '#{clojure.string/blank? blank?})
(def instance-call-syms '#{clojure.core/instance? instance?})

(declare resolved-call-sym)

(s/defn blank-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? blank-call-syms (resolved-call-sym fn-node)))

(def type-predicate-sym->pred
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

(s/defn resolved-call-sym :- (s/maybe s/Symbol)
  [fn-node :- s/Any]
  (or (node-var-sym fn-node)
      (some-> (aapi/binding-init fn-node) resolved-call-sym)
      (let [form (aapi/node-form fn-node)]
        (when (symbol? form) form))))

(s/defn seq-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? seq-call-syms (resolved-call-sym fn-node)))

(s/defn merge-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? merge-call-syms (resolved-call-sym fn-node)))

(s/defn contains-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? contains-call-syms (resolved-call-sym fn-node)))

(s/defn get-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? get-call-syms (resolved-call-sym fn-node)))

(s/defn not-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? not-call-syms (resolved-call-sym fn-node)))

(s/defn equality-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? equality-call-syms (resolved-call-sym fn-node)))

(s/defn static-equality-call? :- s/Bool
  [node :- s/Any]
  (and (= Util (aapi/node-class node))
       (= 'equiv (aapi/node-method node))))

(defn- class-literal-node?
  [node]
  (and (= :const (aapi/node-op node))
       (class? (aapi/node-value node))))

(s/defn type-predicate-assumption-info-for-sym :- (s/maybe aos/PredInfo)
  [sym :- (s/maybe s/Symbol)
   args :- [s/Any]]
  (let [n (count args)]
    (cond
      (contains? instance-call-syms sym)
      (when (and (>= n 2)
                 (class-literal-node? (first args)))
        {:pred :instance?
         :class (aapi/node-value (first args))})

      (contains? type-predicate-sym->pred sym)
      (when (= n 1)
        {:pred (type-predicate-sym->pred sym)})

      :else nil)))

(def ^:dynamic *user-fn-path-predicate-summaries*
  "Project-scoped registry `{qualified-sym → {:pred kw [:class C] :path [kw ...]}}`
   recognising user-defined fns whose body reduces to a built-in path-type-predicate
   on a destructured key of their first parameter. Bound by
   `skeptic.checking.pipeline/check-ns` from project-state; read by
   `skeptic.analysis.origin/test->assumption` so calls like `(b-params? params)`
   propagate as `(vector?, [:k], _)` narrowing on `params`."
  {})

(s/defn user-fn-path-predicate-info :- (s/maybe {s/Keyword s/Any})
  "Lookup helper for `*user-fn-path-predicate-summaries*`."
  [sym :- (s/maybe s/Symbol)]
  (when sym
    (get *user-fn-path-predicate-summaries* sym)))

(defn- s-defn-head?
  [sym]
  (contains? '#{s/defn defn schema.core/defn} sym))

(defn- single-body-after-params
  [form]
  (when (and (seq? form) (s-defn-head? (first form)))
    (loop [items (rest form)]
      (when (seq items)
        (let [head (first items)
              tail (rest items)]
          (if (vector? head)
            (when (= 1 (count tail))
              [head (first tail)])
            (recur tail)))))))

(defn- destructured-keys-from-params
  [params]
  (let [spec (first params)]
    (when (and (map? spec)
               (vector? (:keys spec))
               (every? simple-symbol? (:keys spec)))
      (set (:keys spec)))))

(defn- builtin-pred-kw-for-source-sym
  [pred-sym]
  (when (symbol? pred-sym)
    (or (get type-predicate-sym->pred pred-sym)
        (some-> pred-sym resolve symbol type-predicate-sym->pred))))

(defn path-predicate-summary-from-form
  "Returns `{:pred kw :path [kw]}` when `form` is
   `(s/defn name [doc?] [attr-map?] :- s/Bool [{:keys [...]} :- _] (PRED k))`
   where PRED is a recognised built-in single-arg type predicate and `k` is one
   of the destructured `:keys`. Returns nil otherwise.
   Only the single-arity, single-body shape is recognised; multi-arity, attr
   maps with metadata other than the schema marker, and predicates not in the
   built-in registry fall through."
  [form]
  (when-let [[params body] (single-body-after-params form)]
    (when-let [keys-set (destructured-keys-from-params params)]
      (when (and (seq? body)
                 (= 2 (count body))
                 (symbol? (second body))
                 (contains? keys-set (second body)))
        (when-let [pred-kw (builtin-pred-kw-for-source-sym (first body))]
          {:pred pred-kw :path [(keyword (second body))]})))))

(s/defn type-predicate-assumption-info :- (s/maybe aos/PredInfo)
  [fn-node :- s/Any
   args :- [s/Any]]
  (type-predicate-assumption-info-for-sym (resolved-call-sym fn-node) args))

(s/defn type-predicate-call? :- s/Bool
  [fn-node :- s/Any
   args :- [s/Any]]
  (boolean (type-predicate-assumption-info fn-node args)))

(s/defn keyword-invoke-on-local? :- s/Bool
  "True for `(:k x)` as either JVM analyzer `:invoke` or `:keyword-invoke`."
  [node :- s/Any]
  (or (and (= :invoke (aapi/node-op node))
           (let [fn-node (aapi/call-fn-node node)
                 a0 (first (aapi/call-args node))]
             (and (#{:const :quote} (aapi/node-op fn-node))
                  (keyword? (literal-node-value fn-node))
                  (= :local (aapi/node-op a0)))))
      (and (= :keyword-invoke (aapi/node-op node))
           (= :local (aapi/node-op (aapi/node-target node))))))

(s/defn keyword-invoke-kw-and-target :- (s/maybe [(s/one s/Keyword "kw") (s/one s/Any "target")])
  "When `keyword-invoke-on-local?`, returns `[kw-keyword target-node]`."
  [node :- s/Any]
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

(s/defn assoc-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? assoc-call-syms (resolved-call-sym fn-node)))

(s/defn dissoc-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? dissoc-call-syms (resolved-call-sym fn-node)))

(s/defn update-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? update-call-syms (resolved-call-sym fn-node)))

(s/defn static-get-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/get 'get} (aapi/node-method node))))

(s/defn static-merge-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/merge 'merge} (aapi/node-method node))))

(s/defn static-contains-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/contains? 'contains? 'contains} (aapi/node-method node))))

(s/defn static-nil?-call? :- s/Bool
  [node :- s/Any]
  (and (= Util (aapi/node-class node))
       (= 'identical (aapi/node-method node))))

(s/defn static-nil?-target :- (s/maybe s/Any)
  [node :- s/Any]
  (let [[a b] (aapi/call-args node)]
    (cond
      (and (= :const (aapi/node-op b)) (nil? (aapi/node-value b))) a
      (and (= :const (aapi/node-op a)) (nil? (aapi/node-value a))) b)))

(s/defn static-assoc-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/assoc 'assoc} (aapi/node-method node))))

(s/defn static-dissoc-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/dissoc 'dissoc} (aapi/node-method node))))

(s/defn static-update-call? :- s/Bool
  [node :- s/Any]
  (and (= clojure.lang.RT (aapi/node-class node))
       (contains? #{'clojure.core/update 'update} (aapi/node-method node))))

(s/defn first-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? first-call-syms (resolved-call-sym fn-node)))

(s/defn second-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? second-call-syms (resolved-call-sym fn-node)))

(s/defn last-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? last-call-syms (resolved-call-sym fn-node)))

(s/defn nth-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? nth-call-syms (resolved-call-sym fn-node)))

(s/defn rest-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? rest-call-syms (resolved-call-sym fn-node)))

(s/defn butlast-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? butlast-call-syms (resolved-call-sym fn-node)))

(s/defn drop-last-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-last-call-syms (resolved-call-sym fn-node)))

(s/defn take-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? take-call-syms (resolved-call-sym fn-node)))

(s/defn drop-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-call-syms (resolved-call-sym fn-node)))

(s/defn take-while-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? take-while-call-syms (resolved-call-sym fn-node)))

(s/defn drop-while-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-while-call-syms (resolved-call-sym fn-node)))

(s/defn concat-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? concat-call-syms (resolved-call-sym fn-node)))

(s/defn into-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? into-call-syms (resolved-call-sym fn-node)))

(s/defn chunk-first-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? chunk-first-call-syms (resolved-call-sym fn-node)))

(s/defn plus-invoke? :- s/Bool
  [fn-node :- s/Any]
  (contains? plus-invoke-syms (resolved-call-sym fn-node)))

(s/defn multiply-invoke? :- s/Bool
  [fn-node :- s/Any]
  (contains? multiply-invoke-syms (resolved-call-sym fn-node)))

(s/defn minus-invoke? :- s/Bool
  [fn-node :- s/Any]
  (contains? minus-invoke-syms (resolved-call-sym fn-node)))

(s/defn inc-invoke? :- s/Bool
  [fn-node :- s/Any]
  (contains? inc-invoke-syms (resolved-call-sym fn-node)))

(defn- fun-type-call-info
  [ft arity]
  (if-let [method (at/select-method (at/fun-methods ft) arity)]
    (let [inputs (at/fn-method-inputs method)
          output (or (:output method) (ato/dyn ft))
          c (count inputs)
          argtypes (cond
                     (zero? c) (vec (repeat arity (ato/dyn ft)))
                     (>= c arity) (subvec (vec inputs) 0 arity)
                     :else (vec (concat inputs (repeat (- arity c) (peek inputs)))))]
      {:expected-argtypes argtypes
       :output-type output
       :fn-type ft})
    {:expected-argtypes (vec (repeat arity (ato/dyn ft)))
     :output-type (ato/dyn ft)
     :fn-type ft}))

(s/defn call-info :- s/Any
  [ctx :- s/Any
   fn-node :- s/Any
   args :- s/Any]
  (if-let [ft (aapi/node-fun-type fn-node)]
    (fun-type-call-info ft (count args))
    (default-call-info ctx (count args) (aapi/node-output-type fn-node))))
