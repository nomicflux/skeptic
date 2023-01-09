(ns skeptic.analysis
  (:require [schema.core :as s]
            [clojure.walk :as walk])
  (:import [schema.core Either]))

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
  [arity]
  (s/=> s/Any (vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))))

(s/defn convert-arglists
  [arity {:keys [schema arglists output]}]
  (let [direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)]
    (if (or (and count varargs-res)
            direct-res)
      (let [res (if (and count (>= arity count)) varargs-res direct-res)
            schemas (mapv (fn [{:keys [schema name] :as s}] (s/one (or schema s s/Any) name))
                          (or (:schema res)
                              (vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))))
            arglist (mapv :schema schemas)]
        {:schema (if (and output (seq schemas))
                   (s/make-fn-schema output [schemas])
                   (dynamic-fn-schema arity))
         :output (or output s/Any)
         :arglist arglist})
      (let [schema (or schema (dynamic-fn-schema arity))]
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

(def resolve-fn-output-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (:output (get results idx))
                    v)))
        (assoc :finished? true))))

(def resolve-fn-position
  (fn [results el]
    (if-let [[idx arity] (::placeholder (:schema el))]
      (let [ref (get results idx)
            with-arglists (convert-arglists arity ref)]
        (-> el
            (merge with-arglists)
            (assoc :finished? true)))
      el)))

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int
   (s/optional-key :schema) s/Schema
   (s/optional-key :name) s/Symbol
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :arity) s/Int
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

(defn analyse-do
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (throw (UnsupportedOperationException. "Do blocks not implemented yet")))

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
  (throw (UnsupportedOperationException. "Try blocks not implemented yet")))

(defn analyse-def
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (throw (UnsupportedOperationException. "Def blocks not implemented yet")))

(defn analyse-fn
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (throw (UnsupportedOperationException. "Fn blocks not implemented yet")))

(defn analyse-fn-once
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (throw (UnsupportedOperationException. "Fn-once blocks not implemented yet")))

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
               :arity (count args)
               :fn-position? true)]
    (concat arg-clauses
            [fn-clause
             (assoc this
                    :schema {::placeholder (:idx fn-clause)}
                    :dep-callback resolve-fn-output-schema)])))

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
  [{:keys [fn-position? arity idx] :as this}]
  (assert fn-position? "Must be in function position to analyse as a function")
  (assert arity "A function must have an arity")
  [(dissoc this :fn-position?)
   (assoc this
          :dep-callback resolve-fn-position
          :schema {::placeholder [idx arity]})])

(s/defn analyse-symbol
  [dict
   results
   {:keys [expr local-vars]
    :or {local-vars {}} :as this}]
  (assert (symbol? expr) "Must be a symbol to be looked up")
  (let [lookup (or (local-lookup results local-vars expr)
                   (get dict expr))]
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
          (recur (concat (analyse-do this) rest-stack)
                 results)

          (fn? expr)
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

          (or (loop? expr) (let? expr))
          (recur (concat (analyse-let this) rest-stack)
                 results)

          (seq? expr)
          (recur (concat (analyse-application this) rest-stack)
                 results)

          :else
          (throw (ex-info "Unknown expression type" this)))))))
