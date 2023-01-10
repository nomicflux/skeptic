(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec :include-macros true]
            [schema.spec.leaf :as leaf])
  (:import [schema.core Either Schema]))

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

(defrecord Variable [schema]
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this #(and (var? %) (nil? (s/check schema (deref %)))) #(list 'var? schema %))))
  (explain [_this] (list "#'" (s/explain schema))))

(defn variable
  [schema]
  (Variable. schema))

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int

   (s/optional-key :schema) s/Schema
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> (s/recursive #'AnnotatedExpression)
                                        {s/Int (s/recursive #'AnnotatedExpression)} (s/recursive #'AnnotatedExpression))
   (s/optional-key :finished?) s/Bool})
