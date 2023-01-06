(ns skeptic.analysis
  (:require [schema.core :as s]
            [plumbing.core :as p]
            [clojure.walk :as walk])
  (:import [schema.core Either]))

(s/defn either?
  [s]
  (instance? Either s))

(s/defn eitherize
  [[t1 t2 & _r :as types]]
  (cond
    t2
    (if (contains? types nil)
      (s/maybe (eitherize (disj types nil)))
      (apply s/either types))

    t1 t1

    :else s/Any))

(defn annotate-expr
  [expr]
  (walk/postwalk (let [n (atom 0)]
                   (fn [f]
                     (let [idx (swap! n inc)]
                       (cond
                        (instance? clojure.lang.IMapEntry f)
                        {idx {:expr f :idx idx}}

                        (map? f)
                        {:expr (vals f) :idx idx :map? true}

                        :else
                        {:expr f :idx idx}))))
                 expr))

(def resolve-local-vars
  (fn [results el]
    (update el :local-vars
            (partial p/map-vals (fn [v]
                   (if-let [idx (::placeholder v)]
                     (:schema (get results idx))
                     v))))))

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

(def resolve-fn-output-schema
  (fn [results el]
    (-> el
        (update :schema
             (fn [v]
               (if-let [idx (::placeholder v)]
                 (:output (get results idx))
                 v)))
        (assoc :finished? true))))

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
               {:expr clause
                :local-vars local-vars})
                body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::placeholder (-> output-clause :expr :idx)}
                              :dep-callback resolve-schema)]
    (concat let-clauses body-clauses [current-clause])))

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
                                                             {(eitherize key-schemas) (eitherize val-schemas)}))
                         :schema {::key-placeholders (->> expr (map (comp :idx first :expr)))
                                  ::val-placeholders (->> expr (map (comp :idx second :expr)))})])
    (vector? expr) (concat expr
                           [(assoc this
                                   :dep-callback (resolve-coll-schema (fn [schemas] (vector (eitherize schemas))))
                                   :schema {::placeholders (map :idx expr)})])
    (set? expr) (concat expr
                        [(assoc this
                                :dep-callback (resolve-coll-schema (fn [schemas] (into #{} (eitherize schemas))))
                                :schema {::placeholders (map :idx expr)})])
    (or (list? expr) (seq? expr)) (concat expr
                                          [(assoc this
                                                  :dep-callback (resolve-coll-schema (fn [schemas] (vector (eitherize schemas))))
                                                  :schema {::placeholders (map :idx expr)})])))

(s/defn analyze-value :- {(s/optional-key :finished) [AnnotatedExpression]
                          (s/optional-key :enqueue) [AnnotatedExpression]}
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this} :- AnnotatedExpression]
  (cond
    (nil? expr) {:finished [(assoc this
                                   :schema (s/maybe s/Any))]}
    ;; (symbol? expr) (cond
    ;;                  (contains? local-vars expr)
    ;;                  (merge {:schema s/Any}
    ;;                         (get local-vars expr))

    ;;                  :else
    ;;                  (get-from-dict dict arity expr {:schema s/Symbol}))
    (var? expr) {:enqueue [(assoc this
                                  :expr (or @expr (symbol expr)))]}
    (int? expr) {:finished [(assoc this
                         :schema s/Int)]}
    (string? expr) {:finished [(assoc this
                                      :schema s/Str)]}
    (keyword? expr) {:finished [(assoc this
                                       :schema s/Keyword)]}
    (boolean? expr) {:finished [(assoc this
                                       :schema s/Bool)]}
    :else {:finished [(assoc this
                             :schema (class expr))]}))
