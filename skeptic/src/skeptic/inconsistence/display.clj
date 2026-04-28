(ns skeptic.inconsistence.display
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(s/defn ppr-str :- s/Str
  [x :- s/Any]
  (with-out-str (pprint/pprint x)))

(s/defn public-ref-form :- s/Symbol
  [ref :- s/Any]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))

(s/defn literal-form :- s/Any
  [value :- s/Any]
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

(s/defn exact-key-form :- s/Any
  [key :- s/Any]
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

(s/defn format-user-form :- (s/maybe s/Str)
  [form :- s/Any]
  (when (some? form)
    (pr-str form)))

(def ^:private pretty-type-threshold 80)

(s/defn pretty-user-form :- (s/maybe s/Str)
  [form :- s/Any]
  (when (some? form)
    (str/trimr
     (binding [pprint/*print-right-margin* 80
               pprint/*print-miser-width* 40]
       (with-out-str (pprint/pprint form))))))

(s/defn block-user-form :- (s/maybe s/Str)
  [form :- s/Any]
  (when (some? form)
    (let [inline (format-user-form form)]
      (if (and inline
               (> (count inline) pretty-type-threshold))
        (pretty-user-form form)
        inline))))

(s/defn user-type-form :- s/Any
  ([type :- ats/SemanticType]
   (user-type-form type {}))
  ([type :- ats/SemanticType
    opts :- s/Any]
   (if (nil? type)
     (throw (ex-info "Missing semantic type for display"
                     {:missing-field :type
                      :value nil}))
     (abr/render-type-form* type opts))))

(s/defn user-schema-form :- s/Any
  [schema :- s/Any]
  (or (some-> schema abc/schema-display-form)
      'Unknown))

(s/defn user-raw-form :- s/Any
  [value :- s/Any]
  (or (literal-form value)
      value
      'Unknown))

(s/defn user-fn-input-form :- s/Any
  ([method :- s/Any]
   (user-fn-input-form method {}))
  ([method :- s/Any
    opts :- s/Any]
   (let [inputs (mapv #(user-type-form % opts) (:inputs method))]
     (if (:variadic? method)
       (concat (take (:min-arity method) inputs)
               ['& (drop (:min-arity method) inputs)])
       inputs))))

(s/defn describe-type :- (s/maybe s/Str)
  ([type :- ats/SemanticType]
   (describe-type type {}))
  ([type :- ats/SemanticType
    opts :- s/Any]
   (format-user-form (user-type-form type opts))))

(s/defn describe-type-block :- (s/maybe s/Str)
  ([type :- ats/SemanticType]
   (describe-type-block type {}))
  ([type :- ats/SemanticType
    opts :- s/Any]
   (block-user-form (user-type-form type opts))))

(s/defn describe-schema :- (s/maybe s/Str)
  [schema :- s/Any]
  (format-user-form (user-schema-form schema)))

(s/defn describe-raw :- (s/maybe s/Str)
  [value :- s/Any]
  (format-user-form (user-raw-form value)))

(s/defn describe-item :- (s/maybe s/Str)
  ([x :- s/Any]
   (describe-item x {}))
  ([x :- s/Any
    opts :- s/Any]
   (if (at/semantic-type-value? x)
     (describe-type x opts)
     (describe-raw x))))
