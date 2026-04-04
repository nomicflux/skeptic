(ns skeptic.analysis.bridge.canonicalize
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EnumSchema EqSchema FnSchema Maybe NamedSchema One Schema]))

(declare canonicalize-schema)

(defn schema?
  [s]
  (let [s (abl/localize-schema-value s)]
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
  (let [schema (canonicalize-schema schema)]
    (when-not (schema? schema)
      (throw (IllegalArgumentException.
              (format "Not a valid Schema-domain value: %s" (pr-str schema)))))
    (schema-explain schema)))

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

(defn- canonicalize-entry-fn-schema
  [entry]
  (if (and (contains? entry :schema)
           (sb/fn-schema? (:schema entry)))
    (assoc entry :schema (canonicalize-fn-schema (:schema entry)))
    entry))

(defn canonicalize-entry
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (cond-> entry
      (contains? entry :schema) (update :schema canonicalize-schema)
      (contains? entry :output) (update :output canonicalize-schema)
      (contains? entry :expected-arglist) (update :expected-arglist #(mapv canonicalize-schema %))
      (contains? entry :actual-arglist) (update :actual-arglist #(mapv canonicalize-schema %))
      (contains? entry :locals) (update :locals (fn [locals]
                                                  (into {}
                                                        (map (fn [[k v]]
                                                               [k (canonicalize-entry v)]))
                                                        locals)))
      (contains? entry :arglists) (update :arglists (fn [arglists]
                                                      (into {}
                                                            (map (fn [[k v]]
                                                                   [k (canonicalize-entry v)]))
                                                            arglists)))
      (contains? entry :schema) canonicalize-entry-fn-schema)))

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
  [[t1 & _r :as types]]
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

(defn canonicalize-schema*
  [schema {:keys [constrained->base?]}]
  (let [schema (abl/localize-schema-value schema)]
    (cond
    (nil? schema) nil
    (sb/named? schema) (canonicalize-schema* (sb/de-named schema)
                                          {:constrained->base? constrained->base?})
    (sb/placeholder-schema? schema) schema
    (sb/bottom-schema? schema) sb/Bottom
    (at/semantic-type-value? schema) schema
    (sb/fn-schema? schema) (canonicalize-fn-schema schema)
    (instance? One schema) (canonicalize-one schema)
    (sb/maybe? schema) (s/maybe (canonicalize-schema* (:schema schema)
                                                   {:constrained->base? constrained->base?}))
    (sb/constrained? schema) (if constrained->base?
                           (canonicalize-schema* (sb/de-constrained schema)
                                                 {:constrained->base? true})
                           (s/constrained (canonicalize-schema* (sb/de-constrained schema)
                                                                {:constrained->base? false})
                                          (:postcondition schema)
                                          (:post-name schema)))
    (sb/either? schema) (apply s/either
                            (map #(canonicalize-schema* %
                                                        {:constrained->base? constrained->base?})
                                 (:schemas schema)))
    (sb/conditional-schema? schema) (let [branches (mapcat (fn [[pred branch]]
                                                          [pred (canonicalize-schema* branch
                                                                                     {:constrained->base? constrained->base?})])
                                                        (:preds-and-schemas schema))
                                       args (cond-> (vec branches)
                                              (:error-symbol schema) (conj (:error-symbol schema)))]
                                   (apply s/conditional args))
    (sb/cond-pre? schema) (apply s/cond-pre
                              (map #(canonicalize-schema* %
                                                          {:constrained->base? constrained->base?})
                                   (:schemas schema)))
    (sb/both? schema) (apply s/both
                          (map #(canonicalize-schema* %
                                                      {:constrained->base? constrained->base?})
                               (:schemas schema)))
    (sb/join? schema) (schema-join
                       (set (map #(canonicalize-schema* %
                                                        {:constrained->base? constrained->base?})
                                 (:schemas schema))))
    (sb/valued-schema? schema) (sb/valued-schema (canonicalize-schema* (:schema schema)
                                                                 {:constrained->base? constrained->base?})
                                           (:value schema))
    (sb/variable? schema) (sb/variable (canonicalize-schema* (:schema schema)
                                                       {:constrained->base? constrained->base?}))
    (contains? #{s/Int s/Str s/Keyword s/Symbol s/Bool}
               (sb/canonical-scalar-schema schema))
    (sb/canonical-scalar-schema schema)
    (record? schema) schema
    (map? schema) (into {}
                       (map (fn [[k v]]
                              [(if (s/optional-key? k)
                                 (s/optional-key (canonicalize-schema* (:k k)
                                                                       {:constrained->base? constrained->base?}))
                                 (canonicalize-schema* k {:constrained->base? constrained->base?}))
                               (canonicalize-schema* v {:constrained->base? constrained->base?})]))
                       schema)
    (vector? schema) (mapv #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema)
    (set? schema) (into #{} (map #(canonicalize-schema* % {:constrained->base? constrained->base?})) schema)
    (seq? schema) (doall (map #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema))
    :else (sb/canonical-scalar-schema schema))))

(defn canonicalize-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? false}))

(defn canonicalize-output-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? false}))

(defn maybe-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
    (if (sb/maybe? schema)
      schema
      (s/maybe schema))))

(defn semantic-value-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
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
      :else schema)))

(defn union-like-branches
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (sb/join? schema) (set (:schemas schema))
      (sb/either? schema) (set (:schemas schema))
      (sb/cond-pre? schema) (set (:schemas schema))
      (sb/conditional-schema? schema) (->> (:preds-and-schemas schema)
                                        (map second)
                                        set)
      :else nil)))

(defn union-like-join
  [schema]
  (when-let [branches (union-like-branches schema)]
    (schema-join branches)))

(defn both-components
  [schema]
  (when-let [schema (and (sb/both? schema)
                         (canonicalize-schema schema))]
    (set (:schemas schema))))
