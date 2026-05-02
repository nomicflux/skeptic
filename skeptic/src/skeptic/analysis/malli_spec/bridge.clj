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

(defn- malli-leaf->type
  "Convert a Malli leaf value to a semantic type."
  [prov leaf]
  (cond
    (= leaf :int) (at/->GroundT prov :int 'Int)
    (= leaf :string) (at/->GroundT prov :str 'Str)
    (= leaf :keyword) (at/->GroundT prov :keyword 'Keyword)
    (= leaf :boolean) (at/->GroundT prov :bool 'Bool)
    (= leaf :any) (at/Dyn prov)
    (symbol? leaf)
    (if-let [qsym (predicates/resolve-predicate-symbol leaf)]
      (predicates/witness-type qsym prov)
      (at/Dyn prov))
    :else (at/Dyn prov)))

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

(defn- enum-shape?
  [form]
  (and (vector? form) (= :enum (first form))))

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
    (enum-shape? form) (ato/union-type prov (mapv #(ato/exact-value-type prov %) (enum-values form)))
    :else (malli-leaf->type prov form)))

(s/defn malli-spec->type :- ats/SemanticType
  "Convert a Malli spec to a semantic type."
  [prov  :- provs/Provenance
   value :- s/Any]
  (form->type prov (admit-malli-spec value)))
