(ns skeptic.analysis.bridge.canonicalize
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core One Schema]))

(declare canonicalize-schema)

(defn- raw-schema-domain-value
  [value]
  (let [value (abl/localize-value value)]
    (when (at/semantic-type-value? value)
      (throw (IllegalArgumentException.
              (format "Expected schema value: %s" (pr-str value)))))
    value))

(defn schema?
  [s]
  (let [s (abl/localize-value s)]
    (cond
      (nil? s) true
      (sb/schema-literal? s) true
      (sb/custom-schema? s) true
      (instance? Schema s) true
      (class? s) true
      (s/optional-key? s) (schema? (:k s))
      (instance? One s) (let [m (try (into {} s)
                                     (catch Exception _e nil))]
                          (and (map? m)
                               (schema? (:schema m))))
      (and (map? s)
           (not (record? s))
           (not (at/semantic-type-value? s)))
      (every? (fn [[k v]]
                (and (schema? k)
                     (schema? v)))
              s)
      (vector? s) (every? schema? s)
      (set? s) (and (= 1 (count s))
                    (every? schema? s))
      (seq? s) (every? schema? s)
      :else false)))

(defn schema-explain
  [schema]
  (cond
    (nil? schema)
    nil

    (sb/bottom-schema? schema)
    'Bottom

    (sb/schema-literal? schema)
    schema

    (s/optional-key? schema)
    (list 'optional-key (schema-explain (:k schema)))

    (instance? One schema)
    (let [m (try (into {} schema)
                 (catch Exception _e nil))]
      (if (map? m)
        (list 'one
              (schema-explain (:schema m))
              (:name m))
        schema))

    (sb/join? schema)
    (into #{} (map (fn [member]
                     (if (or (schema? member)
                             (class? member))
                       (schema-explain member)
                       member))
                   (:schemas schema)))

    (sb/valued-schema? schema)
    (str (:value schema) " : " (schema-explain (:schema schema)))

    (sb/variable? schema)
    (list "#'" (schema-explain (:schema schema)))

    (or (schema? schema)
        (class? schema))
    (s/explain schema)

    :else schema))

(defn schema-display-form
  [schema]
  (schema-explain schema))

(defn canonicalize-one
  [one]
  (let [m (try (into {} one)
               (catch Exception _e nil))]
    (if (and (map? m)
             (contains? m :schema))
      (s/one (canonicalize-schema (:schema m))
             (:name m))
      (canonicalize-schema one))))

(defn- canonicalize-fn-schema
  [schema]
  (let [{:keys [input-schemas output-schema]} (into {} schema)]
    (s/make-fn-schema (canonicalize-schema output-schema)
                      (mapv (fn [inputs]
                              (mapv canonicalize-one inputs))
                            input-schemas))))

(defn flatten-join-members
  [types]
  (->> types
       (map canonicalize-schema)
       (mapcat (fn [schema]
                 (if (sb/join? schema)
                   (:schemas schema)
                   [schema])))
       set))

(defn nil-bearing-join
  [types]
  (let [types (flatten-join-members types)
        nil-bearing? (or (contains? types nil)
                         (some sb/maybe? types))
        types (disj types nil)
        {maybe-types true
         plain-types false} (group-by sb/maybe? types)
        maybe-bases (->> maybe-types
                         (map (comp canonicalize-schema sb/de-maybe))
                         set)
        maybe-bases (if (and (contains? maybe-bases s/Any)
                             (seq (concat plain-types
                                          (disj maybe-bases s/Any))))
                      (disj maybe-bases s/Any)
                      maybe-bases)]
    {:nil-bearing? nil-bearing?
     :types (into (set plain-types) maybe-bases)}))

(defn schema-join
  [types]
  (let [{:keys [nil-bearing? types]}
        (nil-bearing-join (cond->> types (not (set? types)) (into #{})))]
    (cond
      (empty? types)
      (if nil-bearing?
        (s/maybe s/Any)
        s/Any)

      (= 1 (count types))
      (let [schema (first types)]
        (if nil-bearing?
          (s/maybe schema)
          schema))

      :else
      (let [schema (apply sb/join types)]
        (if nil-bearing?
          (s/maybe schema)
          schema)))))

(defn- recur-localized
  [recur-fn schema opts]
  (recur-fn (abl/localize-value schema) opts))

(defn- canonicalize-maybe
  [recur-fn schema opts]
  (s/maybe (recur-localized recur-fn (:schema schema) opts)))

(defn- canonicalize-constrained
  [recur-fn schema {:keys [constrained->base?] :as opts}]
  (if constrained->base?
    (recur-localized recur-fn (sb/de-constrained schema) opts)
    (s/constrained (recur-localized recur-fn (sb/de-constrained schema) opts)
                   (:postcondition schema)
                   (:post-name schema))))

(defn- canonicalize-either
  [recur-fn schema opts]
  (apply s/either (map #(recur-localized recur-fn % opts) (:schemas schema))))

(defn- canonicalize-conditional
  [recur-fn schema opts]
  (let [branches (mapcat (fn [[pred branch]]
                           [pred (recur-localized recur-fn branch opts)])
                         (:preds-and-schemas schema))
        args (cond-> (vec branches)
               (:error-symbol schema) (conj (:error-symbol schema)))]
    (apply s/conditional args)))

(defn- canonicalize-cond-pre
  [recur-fn schema opts]
  (apply s/cond-pre (map #(recur-localized recur-fn % opts) (:schemas schema))))

(defn- canonicalize-both
  [recur-fn schema opts]
  (apply s/both (map #(recur-localized recur-fn % opts) (:schemas schema))))

(defn- canonicalize-map-entry
  [recur-fn opts [k v]]
  [(if (s/optional-key? k)
     (s/optional-key (recur-localized recur-fn (:k k) opts))
     (recur-fn k opts))
   (recur-fn v opts)])

(defn- canonicalize-map
  [recur-fn schema opts]
  (into {} (map #(canonicalize-map-entry recur-fn opts %)) schema))

(defn canonicalize-schema*
  [schema opts]
  (cond
    (nil? schema) nil
    (sb/placeholder-schema? schema) schema
    (sb/bottom-schema? schema) sb/Bottom
    (sb/fn-schema? schema) (canonicalize-fn-schema schema)
    (instance? One schema) (canonicalize-one schema)
    (sb/maybe? schema) (canonicalize-maybe canonicalize-schema* schema opts)
    (sb/constrained? schema) (canonicalize-constrained canonicalize-schema* schema opts)
    (sb/either? schema) (canonicalize-either canonicalize-schema* schema opts)
    (sb/conditional-schema? schema) (canonicalize-conditional canonicalize-schema* schema opts)
    (sb/cond-pre? schema) (canonicalize-cond-pre canonicalize-schema* schema opts)
    (sb/both? schema) (canonicalize-both canonicalize-schema* schema opts)
    (sb/join? schema) (schema-join (set (map #(canonicalize-schema* % opts) (:schemas schema))))
    (sb/valued-schema? schema) (sb/valued-schema (canonicalize-schema* (:schema schema) opts)
                                                 (:value schema))
    (sb/variable? schema) (sb/variable (canonicalize-schema* (:schema schema) opts))
    (contains? #{s/Int s/Str s/Keyword s/Symbol s/Bool}
               (sb/canonical-scalar-schema schema))
    (sb/canonical-scalar-schema schema)
    (record? schema) schema
    (map? schema) (canonicalize-map canonicalize-schema* schema opts)
    (vector? schema) (mapv #(canonicalize-schema* % opts) schema)
    (set? schema) (into #{} (map #(canonicalize-schema* % opts)) schema)
    (seq? schema) (doall (map #(canonicalize-schema* % opts) schema))
    :else (sb/canonical-scalar-schema schema)))

(defn canonicalize-schema
  [schema]
  (let [v (raw-schema-domain-value schema)]
    (canonicalize-schema* (cond-> v (sb/named? v) sb/de-named)
                          {:constrained->base? false})))

(defn maybe-schema
  [schema]
  (if (sb/maybe? schema)
    schema
    (s/maybe schema)))

(defn semantic-value-schema
  [schema]
  (cond
    (sb/placeholder-schema? schema) schema
    (sb/valued-schema? schema) (semantic-value-schema (:schema schema))
    (sb/maybe? schema) (s/maybe (semantic-value-schema (:schema schema)))
    (sb/join? schema) (schema-join (set (map semantic-value-schema (:schemas schema))))
    (sb/variable? schema) (sb/variable (semantic-value-schema (:schema schema)))
    (sb/plain-map-schema? schema) (into {}
                                   (map (fn [[k v]]
                                          [(semantic-value-schema k)
                                           (semantic-value-schema v)]))
                                   schema)
    (vector? schema) (mapv semantic-value-schema schema)
    (set? schema) (into #{} (map semantic-value-schema) schema)
    (seq? schema) (doall (map semantic-value-schema schema))
    :else schema))

(defn union-like-branches
  [schema]
  (cond
    (sb/join? schema) (set (:schemas schema))
    (sb/either? schema) (set (:schemas schema))
    (sb/cond-pre? schema) (set (:schemas schema))
    (sb/conditional-schema? schema) (->> (:preds-and-schemas schema)
                                         (map second)
                                         set)
    :else nil))
