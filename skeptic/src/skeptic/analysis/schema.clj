(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec :include-macros true]
            [schema.spec.leaf :as leaf])
  (:import [schema.core EqSchema Maybe Schema]))

(defn any-schema?
  [s]
  (= s s/Any))

(defn schema?
  [s]
  (or (instance? Schema s)
      (try (s/check s nil)
           true
           (catch Exception _e
             false))))

(defn schema-match-value?
  [s x]
  (try
    (nil? (s/check s x))
    (catch Exception _e
      nil)))

(defn schema-match?
  [s x]
  (or (schema-match-value? s x)
      (try (schema-match-value? s (-> x resolve deref))
           (catch Exception _e
             nil))))

(defn check-if-schema
  [s x]
  (case (schema-match? s x)
    true ::schema-valid
    false ::schema-invalid
    nil ::value))

(defn maybe?
  [s]
  (instance? Maybe s))

(defn eq?
  [s]
  (instance? EqSchema s))

(defn de-maybe
  [s]
  (cond-> s
    (maybe? s)
    :schema))

(defn de-eq
  [s]
  (cond-> s
    (eq? s)
    :v))

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
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this set? #(list 'set? schemas %))))
  (explain [_this] (into #{} (map s/explain schemas))))

(defn join
  [& schemas]
  (Join. (into #{} schemas)))

(defn join?
  [s]
  (instance? Join s))

(defn join->set
  [s]
  (if (join? s)
    (:schemas s)
    #{s}))

(defn schema-join
  ;; Nils treated as an automatic `maybe`; this isn't strictly necessary, as `maybe x` is just `nil || x`, but `nil` analysis is
  ;; important enough that they are treated as a separate case
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

(defrecord ValuedSchema [schema value]
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this #(instance? Schema (:schema %)) (fn [s] s))))
  (explain [_this] (str value " : " (s/explain schema))))

(defn valued-schema
  [schema value]
  (ValuedSchema. schema value))

(defn valued-schema?
  [s]
  (instance? ValuedSchema s))

(defn cartesian
  [coll1 coll2]
  (for [x coll1
        y coll2]
    [x y]))

(defn all-pairs
  [[coll1 & rst]]
  (cond
    (nil? coll1) []
    (empty? rst)  coll1
    :else (mapv flatten (reduce cartesian coll1 (or rst [])))))

;; TODO: This should either be pushed into the constructor or handled in original analysis
(defn flatten-valued-schema-map
  [m]
  (loop [m m]
    (if (and (map? m)
             (valued-schema? m)
             (map? (:schema m))
             (or (some valued-schema? (keys (:schema m)))
                 (some valued-schema? (vals (:schema m)))))
      (recur (:schema m))
      m)))

(defn schema-values
  [s]
  (cond
    (valued-schema? s) [(:schema s) (:value s)]
    (and (map? s)
         (not (s/optional-key? s)))
    (let [{valued-schemas true base-schemas false} (->> s keys (group-by valued-schema?))

          complex-keys (->> valued-schemas
                            (map (fn [k] (let [v (get s k)]
                                      (map (fn [k2]
                                             {k2 (if (and (schema? k2) (valued-schema? v))
                                                   (:schema v)
                                                   v)})
                                           (schema-values k)))))
                            all-pairs
                            (map (partial into {})))

          complex-values (->> base-schemas
                              (map (fn [k]
                                     (let [v (get s k)]
                                       (map (fn [v2] {k v2})
                                            (if (valued-schema? v) (schema-values v) [v])))))
                              all-pairs
                              (map (partial into {})))

          split-keys (mapcat (fn [vs] (mapv #(merge vs %) complex-keys)) complex-values)]
      split-keys)
    :else [s]))

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

   (s/optional-key :resolution-path) [s/Any]
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
