(ns skeptic.analysis.sum-types
  (:require [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(declare sum-alternatives)

(defn- open-type?
  [type]
  (or (at/dyn-type? type)
      (at/placeholder-type? type)
      (at/inf-cycle-type? type)))

(defn- bool-alternatives
  [type]
  (when (and (at/ground-type? type) (= :bool (:ground type)))
    (let [prov (ato/derive-prov type)]
      [(ato/exact-value-type prov true)
       (ato/exact-value-type prov false)])))

(defn- maybe-alternatives
  [type]
  (when-let [inner (seq (sum-alternatives (:inner type)))]
    (conj (vec inner) (ato/exact-value-type (ato/derive-prov type) nil))))

(defn- union-alternatives
  [type]
  (let [members (mapv ato/normalize (:members type))]
    (when-not (some open-type? members)
      members)))

(defn- cover-alternatives
  [type]
  (or (sum-alternatives type)
      (let [type (ato/normalize type)]
        (when-not (open-type? type)
          [type]))))

(defn sum-alternatives
  [type]
  (let [type (ato/normalize type)]
    (cond
      (open-type? type) nil
      (at/bottom-type? type) []
      (at/value-type? type) [type]
      (at/maybe-type? type) (maybe-alternatives type)
      (at/union-type? type) (union-alternatives type)
      (at/conditional-type? type) (union-alternatives (assoc type :members (mapv second (:branches type))))
      :else (bool-alternatives type))))

(defn sum-type?
  [type]
  (boolean (seq (sum-alternatives type))))

(defn- covered?
  [covered alternative]
  (some #(at/type-equal? alternative %) covered))

(defn exhausted-by-types?
  [sum-type covered-types]
  (let [alternatives (seq (sum-alternatives sum-type))
        covered (mapcat cover-alternatives covered-types)]
    (boolean
     (and alternatives
          (every? #(covered? covered %) alternatives)))))

(defn exhausted-by-values?
  [sum-type values]
  (let [prov (ato/derive-prov sum-type)
        covered (map #(ato/exact-value-type prov %) values)]
    (exhausted-by-types? sum-type covered)))

(defn- formula-atoms
  [formula]
  (case (:kind formula)
    :atom [(:expr formula)]
    (:conjunction :disjunction) (mapcat formula-atoms (:parts formula))))

(defn- formula-satisfied-by?
  [formula valuation]
  (case (:kind formula)
    :atom (let [v (boolean (get valuation (:expr formula)))]
            (if (:polarity formula) v (not v)))
    :conjunction (every? #(formula-satisfied-by? % valuation) (:parts formula))
    :disjunction (boolean (some #(formula-satisfied-by? % valuation) (:parts formula)))))

(defn- all-valuations
  [atoms]
  (let [n (count atoms)]
    (for [i (range (bit-shift-left 1 n))]
      (into {} (map-indexed (fn [j a] [a (bit-test i j)]) atoms)))))

(defn formulas-cover?
  [formulas]
  (let [atoms (vec (distinct (mapcat formula-atoms formulas)))]
    (if (> (count atoms) 12)
      false
      (every? (fn [val] (some #(formula-satisfied-by? % val) formulas))
              (all-valuations atoms)))))
