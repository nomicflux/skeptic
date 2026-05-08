(ns skeptic.analysis.malli-spec.bridge
  (:require [malli.core :as m]
            [schema.core :as s]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance.schema :as provs]))

(defn- invalid-malli-spec-input
  [value e]
  (throw (IllegalArgumentException.
          (format "Expected Malli spec value: %s" (pr-str value))
          e)))

(defn admit-malli-spec
  "Validate a value as a Malli spec and return its canonical form."
  [value]
  (try
    (m/form (m/schema value))
    (catch Exception e
      (invalid-malli-spec-input value e))))

(defn malli-spec-domain?
  [value]
  (try
    (some? (admit-malli-spec value))
    (catch IllegalArgumentException _e
      false)))

(def ^:private leaf-builders
  {:int (fn [p] (at/->GroundT p :int 'Int))
   :string (fn [p] (at/->GroundT p :str 'Str))
   :keyword (fn [p] (at/->GroundT p :keyword 'Keyword))
   :boolean (fn [p] (at/->GroundT p :bool 'Bool))
   :any (fn [p] (at/Dyn p))
   :nil (fn [p] (ato/exact-value-type p nil))
   :symbol (fn [p] (at/->GroundT p :symbol 'Symbol))
   :double (fn [p] (at/->GroundT p :double 'Double))
   :float (fn [p] (at/->GroundT p :float 'Float))
   :qualified-keyword (fn [p] (at/->GroundT p :keyword 'Keyword))
   :qualified-symbol (fn [p] (at/->GroundT p :symbol 'Symbol))})

(defn- malli-leaf->type
  "Convert a Malli leaf value to a semantic type."
  [prov leaf]
  (if-let [build (get leaf-builders leaf)]
    (build prov)
    (cond
      (symbol? leaf)
      (if-let [qsym (predicates/resolve-predicate-symbol leaf)]
        (predicates/witness-type qsym prov)
        (at/Dyn prov))
      :else (at/Dyn prov))))

(defn- function-shape?
  "Check if canonical form is [:=> [:cat & inputs] output]."
  [form]
  (and (vector? form)
       (= 3 (count form))
       (= :=> (first form))
       (vector? (second form))
       (= :cat (first (second form)))))

(defn- maybe-shape?
  [form]
  (and (vector? form) (= :maybe (first form))))

(defn- or-shape?
  [form]
  (and (vector? form) (= :or (first form))))

(defn- and-shape?
  [form]
  (and (vector? form) (= :and (first form))))

(defn- enum-shape?
  [form]
  (and (vector? form) (= :enum (first form))))

(defn- tuple-shape?
  [form]
  (and (vector? form) (= :tuple (first form))))

(defn- map-shape?
  [form]
  (and (vector? form) (= :map (first form))))

(defn- map-entries-after-props
  [form]
  (let [tail (rest form)]
    (if (map? (first tail)) (rest tail) tail)))

(defn- map-entry->kv
  [run prov entry]
  (let [[k a b] entry
        [props value-form] (if (map? a) [a b] [nil a])
        key-value-type (ato/exact-value-type prov k)
        key-type (if (and (map? props) (true? (:optional props)))
                   (at/->OptionalKeyT prov key-value-type)
                   key-value-type)]
    [key-type (run prov value-form)]))

(defn- enum-values
  [form]
  (if (map? (second form))
    (drop 2 form)
    (rest form)))

(defn- form->type
  [prov form]
  (cond
    (function-shape? form)
    (let [inputs (rest (second form))
          output (nth form 2)
          names (mapv #(symbol (str "arg" %)) (range (count inputs)))]
      (at/->FunT prov
                 [(at/->FnMethodT prov
                                  (mapv #(form->type prov %) inputs)
                                  (form->type prov output)
                                  (count inputs)
                                  false
                                  names)]))
    (maybe-shape? form) (at/->MaybeT prov (form->type prov (second form)))
    (or-shape? form) (ato/union-type prov (mapv #(form->type prov %) (rest form)))
    (and-shape? form) (ato/intersection-type prov (mapv #(form->type prov %) (rest form)))
    (tuple-shape? form) (at/->VectorT prov (mapv #(form->type prov %) (rest form)) nil)
    (map-shape? form) (at/->MapT prov (into {} (map #(map-entry->kv form->type prov %)) (map-entries-after-props form)))
    (enum-shape? form) (ato/union-type prov (mapv #(ato/exact-value-type prov %) (enum-values form)))
    :else (malli-leaf->type prov form)))

(s/defn malli-spec->type :- ats/SemanticType
  "Convert a Malli spec to a semantic type."
  [prov  :- provs/Provenance
   value :- s/Any]
  (form->type prov (admit-malli-spec value)))
