(ns skeptic.inconsistence.display
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

(defn public-ref-form
  [ref]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))

(defn literal-form
  [value]
  (cond
    (or (nil? value)
        (keyword? value)
        (string? value)
        (integer? value)
        (boolean? value)
        (symbol? value))
    value

    (vector? value)
    (mapv literal-form value)

    (set? value)
    (into #{} (map literal-form) value)

    (and (map? value)
         (not (record? value))
         (not (contains? value at/semantic-type-tag-key)))
    (into {}
          (map (fn [[k v]]
                 [(literal-form k)
                  (literal-form v)]))
          value)

    :else nil))

(defn exact-key-form
  [key]
  (cond
    (and (map? key) (contains? key :cleaned-key))
    (exact-key-form (:cleaned-key key))

    (s/optional-key? key)
    (exact-key-form (:k key))

    (at/optional-key-type? key)
    (exact-key-form (:inner key))

    (at/value-type? key)
    (literal-form (:value key))

    :else
    (literal-form key)))

(defn format-user-form
  [form]
  (when (some? form)
    (pr-str form)))

(def ^:private pretty-type-threshold 80)

(defn pretty-user-form
  [form]
  (when (some? form)
    (str/trimr
     (binding [pprint/*print-right-margin* 80
               pprint/*print-miser-width* 40]
       (with-out-str (pprint/pprint form))))))

(defn block-user-form
  [form]
  (when (some? form)
    (let [inline (format-user-form form)]
      (if (and inline
               (> (count inline) pretty-type-threshold))
        (pretty-user-form form)
        inline))))

(defn user-type-form
  [type]
  (or (some-> type abr/render-type-form)
      'Unknown))

(defn user-schema-form
  [schema]
  (or (some-> schema abc/schema-display-form)
      'Unknown))

(defn user-raw-form
  [value]
  (or (literal-form value)
      value
      'Unknown))

(defn user-fn-input-form
  [method]
  (let [inputs (mapv user-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn describe-type
  [type]
  (format-user-form (user-type-form type)))

(defn describe-type-block
  [type]
  (block-user-form (user-type-form type)))

(defn describe-schema
  [schema]
  (format-user-form (user-schema-form schema)))

(defn describe-raw
  [value]
  (format-user-form (user-raw-form value)))

(defn describe-item
  [x]
  (if (at/semantic-type-value? x)
    (describe-type x)
    (describe-raw x)))
