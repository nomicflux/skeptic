(ns skeptic.schema.wire
  "Host-side decoder for worker-encoded Plumatic schema values."
  (:require [clojure.string :as str]
            [schema.core :as s]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.worker.client :as wc]))

(declare decode-schema)

(defn- decode-map-schema
  [entries]
  (into {}
        (map (fn [[k v]] [(decode-schema k) (decode-schema v)]))
        entries))

(defn- bootstrap-class
  [display-name]
  (when (and (string? display-name)
             (or (str/starts-with? display-name "java.")
                 (str/starts-with? display-name "javax.")
                 (str/starts-with? display-name "clojure.")))
    (Class/forName display-name)))

(defn- decode-fn
  [encoded]
  (let [{:keys [sym handle unsupported]} (if (and (map? encoded)
                                                  (= :literal (:tag encoded)))
                                           {:sym (:value encoded)}
                                           encoded)
        core-sym (when (and (symbol? sym) (nil? (namespace sym)))
                   (symbol "clojure.core" (name sym)))]
    (or (when sym
          (if (namespace sym)
            (requiring-resolve sym)
            (requiring-resolve core-sym)))
        (when handle
          (fn [x]
            (oracle/cached-predicate
             handle x
             (fn []
               (let [{:keys [result exception-message]} (wc/ask oracle/*worker-conn*
                                                                {:op "apply-predicate"
                                                                 :handle handle
                                                                 :arg x})]
                 (when exception-message
                   (throw (ex-info exception-message {:predicate-handle handle})))
                 result)))))
        (throw (ex-info "Unsupported function in encoded Plumatic schema"
                        {:symbol sym :unsupported unsupported})))))

(defn- decoded-fn-symbol
  [encoded]
  (let [sym (cond
              (and (map? encoded) (= :literal (:tag encoded))) (:value encoded)
              (map? encoded) (:sym encoded)
              :else nil)]
    (when (symbol? sym) sym)))

(def ^:private predicate-symbol->schema
  {'integer? s/Int
   'clojure.core/integer? s/Int
   'int? s/Int
   'clojure.core/int? s/Int
   'string? s/Str
   'clojure.core/string? s/Str
   'keyword? s/Keyword
   'clojure.core/keyword? s/Keyword
   'symbol? s/Symbol
   'clojure.core/symbol? s/Symbol
   'boolean? s/Bool
   'clojure.core/boolean? s/Bool
   'number? s/Num
   'clojure.core/number? s/Num})

(defn- decode-one
  [{:keys [schema name optional?]}]
  (if optional?
    (s/optional schema name)
    (s/one schema name)))

(defn- demunged-pred-name
  "schema.utils/fn-name derives predicate names from fn class names; under
   direct linking those carry the compiler's __NNNN counter, so (s/pred map?)
   names itself map?--4367. Strip the counter so the name reads as written."
  [pred-name]
  (if (symbol? pred-name)
    (predicates/demunged-predicate-symbol pred-name)
    pred-name))

(defn- decode-record-fields
  [fields]
  (into {}
        (map (fn [[k v]] [k (decode-schema v)]))
        fields))

(defn- decode-record
  [{:keys [class fields]}]
  (let [decoded-fields (decode-record-fields fields)]
    (case class
    "schema.core.FnSchema"
    (s/make-fn-schema (:output-schema decoded-fields)
                      (mapv vec (:input-schemas decoded-fields)))

    "schema.core.AnythingSchema"
    s/Any

    "schema.core.One"
    (decode-one decoded-fields)

    "schema.core.Maybe"
    (s/maybe (:schema decoded-fields))

    "schema.core.NamedSchema"
    (s/named (:schema decoded-fields) (:name decoded-fields))

    "schema.core.Constrained"
    (s/constrained (:schema decoded-fields)
                   (:postcondition decoded-fields)
                   (:post-name decoded-fields))

    "schema.core.Either"
    (apply s/either (:schemas decoded-fields))

    "schema.core.ConditionalSchema"
    (let [pairs (mapcat (fn [[pred schema]]
                          [pred schema])
                        (:preds-and-schemas decoded-fields))]
      (apply s/conditional
             (cond-> (vec pairs)
               (:error-symbol decoded-fields) (conj (:error-symbol decoded-fields)))))

    "schema.core.CondPre"
    (apply s/cond-pre (:schemas decoded-fields))

    "schema.core.Both"
    (apply s/both (:schemas decoded-fields))

    "schema.core.EnumSchema"
    (apply s/enum (:vs decoded-fields))

    "schema.core.EqSchema"
    (s/eq (:v decoded-fields))

    "schema.core.Predicate"
    (let [pred-sym (decoded-fn-symbol (:p? fields))]
      (or (when pred-sym (get predicate-symbol->schema pred-sym))
          (if (contains? decoded-fields :pred-name)
            (s/pred (:p? decoded-fields) (demunged-pred-name (:pred-name decoded-fields)))
            (s/pred (:p? decoded-fields)))))

    "schema.core.Recursive"
    (:derefable decoded-fields)

    "schema.core.OptionalKey"
    (s/optional-key (:k decoded-fields))

    "schema.utils.OptionalKey"
    (s/optional-key (:k decoded-fields))

    "schema.core.RequiredKey"
    (s/required-key (:k decoded-fields))

    "schema.utils.RequiredKey"
    (s/required-key (:k decoded-fields))

    ;; Total decode: a record outside the vocabulary (s/protocol, any
    ;; project-defined Schema implementation) proves nothing about call sites,
    ;; so it admits as Any carrying the record's class name for display.
    (s/named s/Any (symbol class)))))

(defn decode-schema
  [encoded]
  (if-not (map? encoded)
    encoded
    (case (:tag encoded)
      :literal (:value encoded)
      :nil nil
      :class (or (bootstrap-class (:display-name encoded))
                 (sb/class-handle-schema (:handle encoded) (:display-name encoded)))
      :regex (java.util.regex.Pattern/compile (:pattern encoded) (:flags encoded))
      :var-ref (sb/ref-schema (:qualified-sym encoded) (some-> encoded :schema decode-schema))
      :record (decode-record encoded)
      :map (decode-map-schema (:entries encoded))
      :vector (mapv decode-schema (:items encoded))
      :set (into #{} (map decode-schema) (:items encoded))
      :seq (doall (map decode-schema (:items encoded)))
      :fn (decode-fn encoded)
      encoded)))
