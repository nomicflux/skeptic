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

(defn- import-result
  ([type] (import-result type #{}))
  ([type closed-refs]
   {:type type :closed-refs closed-refs}))

(defn- merge-closed-refs
  [results]
  (into #{} (mapcat :closed-refs) results))

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

(defn- eq-shape?
  [form]
  (and (vector? form) (= 2 (count form)) (= := (first form))))

(defn- tuple-shape?
  [form]
  (and (vector? form) (= :tuple (first form))))

(defn- map-shape?
  [form]
  (and (vector? form) (= :map (first form))))

(defn- multi-shape?
  [form]
  (and (vector? form) (= :multi (first form))))

(defn- schema-shape?
  [form]
  (and (vector? form) (= :schema (first form))))

(defn- ref-shape?
  [form]
  (and (vector? form) (= :ref (first form))))

(defn- map-entries-after-props
  [form]
  (let [tail (rest form)]
    (if (map? (first tail)) (rest tail) tail)))

(defn- map-entry->kv
  [run ctx entry]
  (let [prov (:prov ctx)
        [k a b] entry
        [props value-form] (if (map? a) [a b] [nil a])
        key-value-type (ato/exact-value-type prov k)
        key-type (if (and (map? props) (true? (:optional props)))
                   (at/->OptionalKeyT prov key-value-type)
                   key-value-type)
        value-result (run ctx value-form)]
    {:key key-type
     :type (:type value-result)
     :closed-refs (:closed-refs value-result)}))

(def ^:private multi-default-tag :malli.core/default)

(defn- multi-branch-descriptor
  [dispatch tag]
  (when (and (keyword? dispatch) (not= multi-default-tag tag))
    {:path [dispatch] :values [tag]}))

(defn- multi-branch->triple
  [run ctx dispatch entry]
  (let [[tag schema] entry
        r (run ctx schema)]
    {:triple [tag (:type r) (multi-branch-descriptor dispatch tag)]
     :closed-refs (:closed-refs r)}))

(defn- enum-values
  [form]
  (if (map? (second form))
    (drop 2 form)
    (rest form)))

(defn- schema-props
  [form]
  (when (map? (second form)) (second form)))

(defn- schema-body
  [form]
  (if (map? (second form)) (nth form 2) (second form)))

(defn- merge-local-registry
  [ctx props]
  (if-let [local (:registry props)]
    (update ctx :registry merge local)
    ctx))

(declare form->type)

(defn- resolve-ref
  [ctx ref-name]
  (let [prov (:prov ctx)
        active (or (:active-refs ctx) #{})
        registry (or (:registry ctx) {})]
    (cond
      (contains? active ref-name)
      (import-result (at/->InfCycleT prov ref-name) #{ref-name})

      (contains? registry ref-name)
      (let [target (get registry ref-name)
            ctx' (update ctx :active-refs (fnil conj #{}) ref-name)
            r (form->type ctx' target)]
        (import-result (:type r) (conj (:closed-refs r) ref-name)))

      :else
      (import-result (at/->PlaceholderT prov ref-name)))))

(defn- function-result
  [ctx form]
  (let [prov (:prov ctx)
        inputs (rest (second form))
        output (nth form 2)
        names (mapv #(symbol (str "arg" %)) (range (count inputs)))
        input-results (mapv #(form->type ctx %) inputs)
        output-result (form->type ctx output)]
    (import-result
     (at/->FunT prov
                [(at/->FnMethodT prov
                                 (mapv :type input-results)
                                 (:type output-result)
                                 (count inputs)
                                 false
                                 names)])
     (merge-closed-refs (conj input-results output-result)))))

(defn- form->type
  [ctx form]
  (let [prov (:prov ctx)]
    (cond
      (function-shape? form)
      (function-result ctx form)

      (maybe-shape? form)
      (let [r (form->type ctx (second form))]
        (import-result (at/->MaybeT prov (:type r)) (:closed-refs r)))

      (or-shape? form)
      (let [rs (mapv #(form->type ctx %) (rest form))]
        (import-result (ato/union-type prov (mapv :type rs))
                       (merge-closed-refs rs)))

      (and-shape? form)
      (let [rs (mapv #(form->type ctx %) (rest form))]
        (import-result (ato/intersection-type prov (mapv :type rs))
                       (merge-closed-refs rs)))

      (tuple-shape? form)
      (let [rs (mapv #(form->type ctx %) (rest form))]
        (import-result (at/->VectorT prov (mapv :type rs) nil)
                       (merge-closed-refs rs)))

      (map-shape? form)
      (let [entry-results (mapv #(map-entry->kv form->type ctx %)
                                (map-entries-after-props form))]
        (import-result
         (at/->MapT prov (into {} (map (fn [e] [(:key e) (:type e)])) entry-results))
         (merge-closed-refs entry-results)))

      (multi-shape? form)
      (let [dispatch (get-in form [1 :dispatch])
            entries (drop 2 form)
            triples (mapv #(multi-branch->triple form->type ctx dispatch %) entries)]
        (import-result (at/->ConditionalT prov (mapv :triple triples))
                       (merge-closed-refs triples)))

      (enum-shape? form)
      (import-result (ato/union-type prov (mapv #(ato/exact-value-type prov %)
                                                (enum-values form))))

      (eq-shape? form)
      (import-result (ato/exact-value-type prov (second form)))

      (schema-shape? form)
      (form->type (merge-local-registry ctx (schema-props form))
                  (schema-body form))

      (ref-shape? form)
      (resolve-ref ctx (second form))

      :else
      (import-result (malli-leaf->type prov form)))))

(s/defn malli-spec->type :- ats/SemanticType
  "Convert a Malli spec to a semantic type."
  [prov  :- provs/Provenance
   value :- s/Any]
  (:type (form->type {:prov prov :registry {} :active-refs #{}}
                     (admit-malli-spec value))))
