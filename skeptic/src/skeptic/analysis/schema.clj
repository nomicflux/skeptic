(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec :include-macros true]
            [schema.spec.leaf :as leaf])
  (:import [schema.core Maybe Schema]))

(defn maybe?
  [s]
  (instance? Maybe s))

(defn de-maybe
  [s]
  (cond-> s
    (maybe? s)
    :schema))

(defrecord BottomSchema [_]
  ;; This is copied from Any, but in terms of calculating joins of types it works in practically the opposite fashion
  ;; i.e. Any && x = x, Any || x = Any
  ;;      Bottom && x = Bottom, Bottom || x = x
  Schema
  (spec [this] (leaf/leaf-spec spec/+no-precondition+))
  (explain [this] 'Bottom))

(def Bottom
  "Any value, including nil. But often exceptions."
  (BottomSchema. nil))

(defrecord Join [schemas]
  ;; This is basically either, except that it isn't deprecated and doesn't care about order
  ;; It is intended for use in analysis rather than directly in programs, to represent an unresolved Join of the included
  ;; schemas (which often will be simply x || y || z || ... for distinct schemas x, y, z, ..., but may be able to be restricted
  ;; in the case of maps with overlapping keys, numeric types, etc.)
  ;; Nils treated as an automatic `maybe`; this isn't strictly necessary, as `maybe x` is just `nil || x`, but `nil` analysis is
  ;; important enough that they are treated as a separate case
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this set? #(list 'set? schemas %))))
  (explain [_this] (into #{} (map s/explain schemas))))

(defn join
  [& schemas]
  (Join. (into #{} schemas)))

(defn schema-join
  [[t1 & _r :as types]]
  (let [types (cond->> types (not (set? types)) (into #{}))]
    (cond
      (= 1 (count types)) t1

      (contains? types nil)
      (s/maybe (schema-join (disj types nil)))

      (empty? types)
      s/Any

      :else
      (apply join types))))

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

(s/defschema WithPlaceholder
  {s/Keyword s/Any})

(s/defschema ArgCount
  (s/cond-pre s/Int (s/eq :varargs)))

;; TODO: make these all ns-specific so there are no collisions

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int

   (s/optional-key :schema) s/Any
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> (s/recursive #'AnnotatedExpression)
                                        {s/Int (s/recursive #'AnnotatedExpression)} (s/recursive #'AnnotatedExpression))
   (s/optional-key :expected-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :actual-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :output) s/Any
   (s/optional-key :arglists) {ArgCount s/Any}
   (s/optional-key :arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :map?) s/Bool
   (s/optional-key :finished?) s/Bool})
