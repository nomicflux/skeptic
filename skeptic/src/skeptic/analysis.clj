(ns skeptic.analysis
  (:require [schema.core :as s]
            [skeptic.schematize :as schematize]
            [schema.spec.core :as spec :include-macros true]
            [schema.spec.leaf :as leaf]
            [plumbing.core :as p]
            [clojure.walk :as walk])
  (:import [schema.core Either Schema]))

;; TODO: infer function types from actually applied args, if none given
;; TODO: representation for cast values instead of just static types (such as dynamic functions cast to actual arg types)
;; TODO: also, infer outputs from what is actually output
;; TODO: gather schema info for internal functions
;; TODO: check that function output matches schema
;; TODO: how to go from macroexpanded version to line in code?
;; TODO: handle `new`

;; TODO: keywords & other values in function position
;; TODO: atoms? refs? reader macros?

;; TODO: def & defn need to add to a global set of symbols
;; TODO: need support for for, doseq, doall

;; TODO: Global vars from def

(s/defn fn-once?
  "(fn* [x] (...) (...) ...)"
  [x]
  (and (seq? x)
       (-> x first :expr (= 'fn*))
       (-> x second :expr vector?)))

(s/defn fn-expr?
  "(fn* ([x] ...) ([x y] ...) ...)"
  [x]
  (and (seq? x)
       (-> x first :expr (= 'fn*))
       (-> x second :expr seq?)
       (-> x second :expr first :expr vector?)))

(s/defn let?
  [x]
  (and (seq? x)
       (or (-> x first :expr (= 'let))
           (-> x first :expr (= 'let*)))))

(s/defn loop?
  [x]
  (and (seq? x)
       (or (-> x first :expr (= 'loop))
           (-> x first :expr (= 'loop*)))))

(s/defn if?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'if))))

(s/defn do?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'do))))

(s/defn try?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'try))))

(s/defn throw?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'throw))))

(s/defn catch?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'catch))))

(s/defn finally?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'finally))))

(s/defn def?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'def))))

(s/defn either?
  [s]
  (instance? Either s))

(s/defn schema-join
  [[t1 & _r :as types]]
  (let [types (cond->> types (not (set? types)) (into #{}))]
    (cond
      (= 1 (count types)) t1

      (contains? types nil)
      (s/maybe (schema-join (disj types nil)))

      (empty? types)
      s/Any

      :else
      (apply s/either types))))

(defn dynamic-fn-schema
  [arity output]
  (s/make-fn-schema (or output s/Any) [(vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))]))

(s/defn arglist->input-schema
  [{:keys [schema name] :as s}]
  (s/one (or schema s s/Any) name))

(s/defn convert-arglists
  [args {:keys [arglists output]}]
  (let [arity (count args)
        direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)]
    (if (or (and count varargs-res)
            direct-res)
      (let [res (if (and count (>= arity count)) varargs-res direct-res)
            schemas (mapv arglist->input-schema
                          (or (:schema res)
                              (vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))))
            arglist (mapv :schema schemas)]
        {:schema (if (and output (seq schemas))
                   (s/make-fn-schema output [schemas])
                   (dynamic-fn-schema arity output))
         :output (or output s/Any)
         :arglist arglist})
      (let [schema (dynamic-fn-schema arity output)]
        {:schema schema
         :output (or output s/Any)}))))

(defn annotate-expr
  [expr]
  (walk/postwalk (let [n (atom 0)]
                   (fn [f]
                     (let [idx (swap! n inc)]
                       (cond
                         (instance? clojure.lang.IMapEntry f)
                         {idx {:expr f :idx idx}}

                         (map? f)
                         {:expr (vec (vals f)) :idx idx :map? true}

                         :else
                         {:expr f :idx idx}))))
                 expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk #(if (and (map? %) (contains? % :expr)) (:expr %) %) expr))

(defn resolve-map-schema
  [schema-fn]
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (let [key-idxs (::key-placeholders v)
                        val-idxs (::val-placeholders v)]
                    (schema-fn (->> key-idxs (map (comp :schema (partial get results))))
                               (->> val-idxs (map (comp :schema (partial get results))))) )))
        (assoc :finished? true))))

(defn resolve-coll-schema
  [schema-fn]
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idxs (::placeholders v)]
                    (schema-fn (->> idxs (map (comp :schema (partial get results)))))
                    v)))
        (assoc :finished? true))))

(def resolve-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (:schema (get results idx))
                    v)))
        (assoc :finished? true))))

(defrecord Variable [schema]
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this #(and (var? %) (nil? (s/check schema (deref %)))) #(list 'var? schema %))))
  (explain [_this] (list "#'" (s/explain schema))))

(defn variable
  [schema]
  (Variable. schema))

(def resolve-def-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (variable (:schema (get results idx)))
                    v)))
        (assoc :finished? true))))

(def resolve-if-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [[t-idx f-idx] (::placeholders v)]
                    (let [t-el (get results t-idx)
                          f-el (get results f-idx)]
                      (schema-join (set [(:schema t-el) (:schema f-el)])))
                    v)))
        (assoc :finished? true))))

(def resolve-application-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (:output (get results idx))
                    v)))
        (update :actual-arglist
                (fn [v]
                  (if-let [idxs (::placeholders v)]
                    (->> idxs
                         (map (partial get results))
                         (map :schema))
                    v)))
        (update :expected-arglist
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (->> idx
                         (get results idx)
                         :arglist)
                    v)))
        (assoc :finished? true))))

(def resolve-fn-outputs
  (fn [results el]
    (let [output-schemas (->> el :output ::placeholders
                              (map #(get results %))
                              (map :schema)
                              schema-join)]
      (-> el
          (assoc :output output-schemas)
          (update :schema
                  (fn [v]
                    (if-let [arglists (::arglists v)]
                      (s/make-fn-schema output-schemas (->> arglists
                                                            vals
                                                            (mapv :schema)
                                                            (mapv (partial mapv arglist->input-schema))))
                      v)))
          (assoc :finished? true)))))

(def resolve-fn-once-outputs
  (fn [results el]
    (let [output-schema (->> el :output ::placeholder (get results) :schema)]
      (-> el
          (assoc :output output-schema)
          (update :schema
                  (fn [v]
                    (if-let [arglists (::arglists v)]
                      (s/make-fn-schema output-schema (->> arglists
                                                           vals
                                                           (mapv :schema)
                                                           (mapv (partial mapv arglist->input-schema))))
                      v)))
          (assoc :finished? true)))))

(def resolve-fn-position
  (fn [results el]
    (if-let [[idx args] (::placeholder (:schema el))]
      (let [ref (get results idx)
            with-arglists (convert-arglists (->> args (map (partial get results))) ref)]
        (-> el
            (merge with-arglists)
            (assoc :arglist (->> with-arglists :schema :input-schemas first (map :schema)))
            (assoc :finished? true)))
      el)))

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int
   (s/optional-key :schema) s/Schema
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> AnnotatedExpression {s/Int AnnotatedExpression} AnnotatedExpression)
   (s/optional-key :finished?) s/Bool})

(defn analyse-let
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[letblock & body] (->> expr (drop 1))
        letpairs (partition 2 (:expr letblock))

        {:keys [let-clauses local-vars]}
        (reduce (fn [{:keys [let-clauses local-vars]}
                    [newvar varbody]]
                  (let [clause (assoc varbody
                                      :local-vars local-vars
                                      :name (:expr newvar))]
                    {:local-vars (assoc local-vars
                                        (:expr newvar)
                                        {::placeholder (:idx varbody)})
                     :let-clauses (conj let-clauses clause)}))
                {:let-clauses []
                 :local-vars local-vars}
                letpairs)

        body-clauses
        (map (fn [clause]
               (assoc clause
                      :local-vars local-vars))
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::placeholder (:idx output-clause)}
                              :dep-callback resolve-schema)]
    (concat let-clauses body-clauses [current-clause])))

(defn analyse-def
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[name & body] (->> expr (drop 1))

        body-clauses
        (map (fn [clause]
               (assoc clause
                      :local-vars local-vars))
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :name (:expr name)
                              :schema {::placeholder (:idx output-clause)}
                              :dep-callback resolve-def-schema)]
    (concat body-clauses [current-clause])))

(defn analyse-if
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[_ if-clause t-clause f-clause] expr

        body-clauses
        (map (fn [clause]
               (assoc clause
                      :local-vars local-vars))
             [if-clause t-clause f-clause])

        current-clause (assoc this
                              :schema {::placeholders [(:idx t-clause) (:idx f-clause)]}
                              :dep-callback resolve-if-schema)]
    (concat body-clauses [current-clause])))

(defn analyse-try
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[_ & body] expr
        [try-body after-body] (split-with (fn [{:keys [expr]}] (not (or (catch? expr) (finally? expr)))) body)
        [catch-body finally-body] (split-with (fn [{:keys [expr]}] (not (finally? expr))) after-body)

        try-clauses (mapv #(assoc % :local-vars local-vars) try-body)

        catch-clauses (->> catch-body first :expr (drop 3) (mapv #(assoc % :local-vars local-vars)))
        finally-clauses (->> finally-body first :expr (drop 1) (mapv #(assoc % :local-vars local-vars)))
        output-clause (or (last finally-clauses) (last try-clauses))
        current-clause (assoc this
                              :schema {::placeholder (:idx output-clause)}
                              :dep-callback resolve-schema)]
    (concat try-clauses catch-clauses finally-clauses [current-clause])))

(defn analyse-throw
  [this]
  ;; TODO: this isn't quite an any....
  (assoc this
         :schema s/Any))

(defn analyse-do
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [body (->> expr (drop 1))

        body-clauses
        (map (fn [clause]
               (assoc clause
                      :local-vars local-vars))
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::placeholder (:idx output-clause)}
                              :dep-callback resolve-schema)]
    (concat body-clauses [current-clause])))

(defn analyse-fn
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [body-clauses
        (->> expr
             (drop 1)
             (map (fn [fn-expr]
                    (let [[vars & body] (:expr fn-expr)
                          {:keys [count args with-varargs varargs]} (->> vars :expr (map :expr) schematize/arg-list)
                          fn-vars (p/map-from-keys (fn [k] {:expr k :name k :schema s/Any}) args)
                          fn-vars (cond-> fn-vars with-varargs (assoc varargs {:expr varargs
                                                                               :name (str varargs)
                                                                               :schema [s/Any]}))
                          clauses (map (fn [clause]
                                         (assoc clause
                                                :local-vars (merge local-vars fn-vars)))
                                       body)]
                      {:clauses clauses
                       :expr fn-expr
                       :output-placeholder (-> clauses last :idx)
                       :arglists (if with-varargs
                                   {:varargs {:arglist (conj args varargs)
                                              :count count
                                              :schema (conj (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)
                                                            s/Any)}}
                                   {count {:arglist args
                                           :count count
                                           :schema (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)}})}))))

        all-body-clauses (mapcat :clauses body-clauses)
        full-arglist (reduce merge {} (map :arglists body-clauses))
        full-output (map :output-placeholder body-clauses)
        current-clause (assoc this
                              :dep-callback resolve-fn-outputs
                              :output {::placeholders full-output}
                              :schema {::arglists full-arglist}
                              :arglists full-arglist)]
    (concat all-body-clauses
            [current-clause])))

(defn analyse-fn-once
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[_ vars & body] expr
        {:keys [count args with-varargs varargs]} (->> vars :expr (map :expr) schematize/arg-list)
        fn-vars (p/map-from-keys (fn [k] {:expr k :name k :schema s/Any}) args)
        fn-vars (cond-> fn-vars with-varargs (assoc varargs {:expr varargs
                                                             :name varargs
                                                             :schema [s/Any]}))
        clauses (map (fn [clause]
                       (assoc clause
                              :local-vars (merge local-vars fn-vars)))
                     body)

        arglist (if with-varargs
                  {:varargs {:arglist (conj args varargs)
                             :count count
                             :schema (conj (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)
                                           s/Any)}}
                  {count {:arglist args
                          :count count
                          :schema (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)}})

        current-clause (assoc this
                              :dep-callback resolve-fn-once-outputs
                              :output {::placeholder (-> clauses last :idx)}
                              :schema {::arglist arglist}
                              :arglists arglist)]
    (concat clauses
            [current-clause])))

(defn analyse-application
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (let [[f & args] expr

        arg-clauses
        (map (fn [clause]
               (assoc clause
                      :local-vars local-vars))
             args)

        fn-clause
        (assoc f
               :args (map :idx args)
               :local-vars local-vars
               :fn-position? true)]
    (concat arg-clauses
            [fn-clause
             (assoc this
                    :actual-arglist {::placeholders (map :idx arg-clauses)}
                    :expected-arglist {::placeholder (:idx fn-clause)}
                    :schema {::placeholder (:idx fn-clause)}
                    :dep-callback resolve-application-schema)])))

(s/defn analyse-coll
  [{:keys [expr map?] :as this}]
  (cond
    ;; As the postwalk rewrites things into maps, maps themselves need special annotation processing
    map? (concat (mapcat :expr expr)
                 [(assoc this
                         :dep-callback (resolve-map-schema (fn [key-schemas val-schemas]
                                                             {(schema-join key-schemas) (schema-join val-schemas)}))
                         :schema {::key-placeholders (->> expr (map (comp :idx first :expr)))
                                  ::val-placeholders (->> expr (map (comp :idx second :expr)))})])
    (vector? expr) (concat expr
                           [(assoc this
                                   :dep-callback (resolve-coll-schema (fn [schemas] (vector (schema-join schemas))))
                                   :schema {::placeholders (map :idx expr)})])
    (set? expr) (concat expr
                        [(assoc this
                                :dep-callback (resolve-coll-schema (fn [schemas] #{(schema-join schemas)}))
                                :schema {::placeholders (map :idx expr)})])
    (or (list? expr) (seq? expr)) (concat expr
                                          [(assoc this
                                                  :dep-callback (resolve-coll-schema (fn [schemas] (vector (schema-join schemas))))
                                                  :schema {::placeholders (map :idx expr)})])))

(s/defn local-lookup
  [results
   local-vars
   sym]
  (let [lookup (get local-vars sym)
        placeholder (::placeholder lookup)]
    (if placeholder
      (get results placeholder)
      lookup)))

(s/defn analyse-function
  [{:keys [fn-position? args idx] :as this}]
  (assert fn-position? "Must be in function position to analyse as a function")
  (assert (not (nil? args)) "A function must have a list of args")
  [(dissoc this :fn-position?)
   (assoc this
          :dep-callback resolve-fn-position
          :arglist {::placeholder [idx args]}
          :schema {::placeholder [idx args]})])

(s/defn analyse-symbol
  [dict
   results
   {:keys [expr local-vars fn-position? args]
    :or {local-vars {}} :as this}]
  (let [default-schema (if fn-position? (dynamic-fn-schema (count args) s/Any) s/Any)
        lookup (or (local-lookup results local-vars expr)
                   (get dict expr)
                   {:schema default-schema})]
    (merge this
           (select-keys lookup [:schema :output :arglists]))))

(s/defschema ValueResult {(s/optional-key :finished) AnnotatedExpression
                          (s/optional-key :enqueue) [AnnotatedExpression]})

(s/defn analyse-value ;:- ValueResult
  [{:keys [expr fn-position?] :as this} ;:- AnnotatedExpression
   ]
  (assert (not fn-position?) "Functions should be analysed with analyse-function")
  (assert (not (symbol? expr)) "Symbols should be looked up with analyse-symbol")
  (if (var? expr)
    {:enqueue [(assoc this
                      :expr (or @expr (symbol expr)))]}
    {:finished (assoc this
                      :schema (cond
                                (nil? expr) (s/maybe s/Any)
                                (int? expr) s/Int
                                (string? expr) s/Str
                                (keyword? expr) s/Keyword
                                (boolean? expr) s/Bool
                                :else (class expr)))}))

(defn attach-schema-info-loop
  [dict
   expr]
  (loop [expr-stack [(annotate-expr expr)]
         results {}]
    (if (empty? expr-stack)
      results

      (let [{:keys [dep-callback] :as next} (first expr-stack)
            rest-stack (rest expr-stack)
            {:keys [expr idx fn-position? finished?] :as this} (if dep-callback (dep-callback results (dissoc next :dep-callback)) next)]
        (cond
          finished?
          (recur (rest expr-stack) (assoc results idx this))

          fn-position?
          (let [res (analyse-function this)]
            (recur (concat res rest-stack) results))

          (not (seq? expr))
          (cond
            (coll? expr)
            (recur (concat (analyse-coll this) rest-stack) results)

            (symbol? expr)
            (recur rest-stack (assoc results idx (analyse-symbol dict results this)))

            :else
            (let [{:keys [finished enqueue]} (analyse-value this)]
              (recur (concat enqueue (or rest-stack []))
                     (if finished
                       (assoc results idx finished)
                       results))))

          (def? expr)
          (recur (concat (analyse-def this) rest-stack)
                 results)

          (do? expr)
          (recur (concat (analyse-do this) rest-stack)
                 results)

          (fn-expr? expr)
          (recur (concat (analyse-fn this) rest-stack)
                 results)

          (fn-once? expr)
          (recur (concat (analyse-fn-once this) rest-stack)
                 results)

          (if? expr)
          (recur (concat (analyse-if this) rest-stack)
                 results)

          (try? expr)
          (recur (concat (analyse-try this) rest-stack)
                 results)

          (throw? expr)
          (recur rest-stack
                 (assoc results idx (analyse-throw this)))

          (or (loop? expr) (let? expr))
          (recur (concat (analyse-let this) rest-stack)
                 results)

          (seq? expr)
          (recur (concat (analyse-application this) rest-stack)
                 results)

          :else
          (throw (ex-info "Unknown expression type" this)))))))
