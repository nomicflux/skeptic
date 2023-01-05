(ns skeptic.analysis
  (:require [schema.core :as s]
            [plumbing.core :as p]
            [clojure.walk :as walk]))

(defn annotate-expr
  [expr]
  (walk/postwalk (let [n (atom 0)] (fn [f] {:expr f :idx (swap! n inc)}))
                 expr))

(def resolve-local-vars
  (fn [results el]
    (update el :local-vars
            (partial p/map-vals (fn [v]
                   (if-let [idx (::placeholder v)]
                     (:schema (get results idx))
                     v))))))

(def resolve-output-schema
  (fn [results el]
    (-> el
        (update :schema
             (fn [v]
               (if-let [idx (::placeholder v)]
                 (:schema (get results idx))
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
                                      :name (:expr newvar)
                                      :dep-callback resolve-local-vars)]
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
                :local-vars local-vars
                :dep-callback resolve-local-vars})
                body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::placeholder (:idx output-clause)}
                              :dep-callback resolve-output-schema)]
    (concat let-clauses body-clauses [current-clause])))
