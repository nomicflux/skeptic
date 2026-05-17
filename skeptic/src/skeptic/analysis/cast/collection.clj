(ns skeptic.analysis.cast.collection
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value-check :as avc]))

(defn- prefix-tail-cast-fails-arity?
  "Source is incompatible with target's required arity if:
  - target is closed but source has prefix items plus a tail (statically too long), OR
  - source's known prefix is shorter than target's required prefix, OR
  - both are closed and prefix lengths differ.
  A purely homogeneous source (items=[] tail=T) has unknown runtime count and
  never arity-fails — it is element-checked against the target's slots."
  [source-type target-type]
  (let [n-source (count (at/pattern-prefix (:pattern source-type)))
        n-target (count (at/pattern-prefix (:pattern target-type)))
        s-tail (at/pattern-tail (:pattern source-type))
        t-tail (at/pattern-tail (:pattern target-type))]
    (cond
      (and (zero? n-source) (some? s-tail)) false
      (and (nil? t-tail) (some? s-tail)) true
      (< n-source n-target) true
      (and (nil? t-tail) (not= n-source n-target)) true
      :else false)))

(defn- prefix-tail-children
  [run-child kind source-type target-type opts]
  (let [source-items (at/pattern-prefix (:pattern source-type))
        target-items (at/pattern-prefix (:pattern target-type))
        n-source (count source-items)
        n-target (count target-items)
        s-tail (at/pattern-tail (:pattern source-type))
        t-tail (at/pattern-tail (:pattern target-type))]
    (if (and (zero? n-source) (some? s-tail))
      (let [item-children (mapv
                           (fn [idx target-item]
                             (run-child (ascs/indexed-request kind idx s-tail target-item opts)))
                           (range)
                           target-items)
            tail-children (when t-tail
                            [(run-child {:source-type s-tail
                                         :target-type t-tail
                                         :opts opts})])]
        (into item-children tail-children))
      (let [item-children (mapv
                           (fn [idx source-item]
                             (let [target-item (if (< idx n-target)
                                                 (nth target-items idx)
                                                 t-tail)]
                               (run-child (ascs/indexed-request kind idx source-item target-item opts))))
                           (range)
                           source-items)
            tail-children (when (and s-tail t-tail)
                            [(run-child {:source-type s-tail
                                         :target-type t-tail
                                         :opts opts})])]
        (into item-children tail-children)))))

(defn- prefix-tail-collection-result
  [run-child source-type target-type opts kind rule item-failure arity-failure]
  (if (prefix-tail-cast-fails-arity? source-type target-type)
    (ascs/cast-fail source-type target-type rule (:polarity opts) arity-failure)
    (let [children (prefix-tail-children run-child kind source-type target-type opts)]
      (ascs/aggregate-children source-type target-type rule (:polarity opts) item-failure children))))

(defn- set-member-failure
  [source-member target-members polarity]
  (ascs/with-cast-path
    (ascs/cast-fail source-member
                    (or (first target-members) (ato/dyn source-member))
                    :set-element
                    polarity
                    :element-mismatch)
    {:kind :set-member
     :member source-member}))

(defn- set-member-result
  [run-child source-member target-members opts]
  (let [results (mapv #(run-child {:source-type source-member
                                   :target-type %
                                   :opts opts})
                      target-members)]
    (or (some #(when (:ok? %) %) results)
        (set-member-failure source-member target-members (:polarity opts)))))

(defn- ordered-coll-tags
  [source-kind target-kind]
  (case [source-kind target-kind]
    [:vector :vector]         {:kind :vector-index :rule :vector
                               :item-failure :vector-element-failed
                               :arity-failure :vector-arity-mismatch}
    [:sequential :sequential] {:kind :seq-index :rule :seq
                               :item-failure :seq-element-failed
                               :arity-failure :seq-arity-mismatch}
    [:sequential :vector]     {:kind :vector-index :rule :seq-to-vector
                               :item-failure :seq-to-vector-element-failed
                               :arity-failure :seq-to-vector-arity-mismatch}
    [:vector :sequential]     {:kind :seq-index :rule :vector-to-seq
                               :item-failure :vector-to-seq-element-failed
                               :arity-failure :vector-to-seq-arity-mismatch}))

(s/defn check-ordered-coll-cast :- csch/CastResult
  [run-child :- (s/pred fn?) source-type :- at/SemanticType target-type :- at/SemanticType opts :- s/Any]
  (let [{:keys [kind rule item-failure arity-failure]}
        (ordered-coll-tags (:ordered-coll-kind source-type)
                           (:ordered-coll-kind target-type))]
    (prefix-tail-collection-result run-child source-type target-type opts
                                   kind rule item-failure arity-failure)))

(s/defn check-set-cast :- csch/CastResult
  [run-child :- (s/pred fn?) source-type :- at/SemanticType target-type :- at/SemanticType opts :- s/Any]
  (if (= (count (:members source-type)) (count (:members target-type)))
    (let [children (mapv #(set-member-result run-child % (:members target-type) opts)
                         (:members source-type))]
      (ascs/aggregate-children source-type target-type :set (:polarity opts) :set-element-failed children))
    (ascs/cast-fail source-type target-type :set (:polarity opts) :set-cardinality-mismatch)))

(s/defn check-leaf-cast :- csch/CastResult
  [source-type :- at/SemanticType target-type :- at/SemanticType polarity :- s/Any]
  (cond
    (at/value-type? source-type)
    (if (avc/value-satisfies-type? (:value source-type) target-type)
      (ascs/cast-ok source-type target-type :value-exact)
      (ascs/cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

    (at/value-type? target-type)
    (if (avc/value-satisfies-type? (:value target-type) source-type)
      (ascs/cast-ok source-type target-type :target-value)
      (ascs/cast-fail source-type target-type :target-value polarity :target-value-mismatch))

    (or (at/dyn-type? source-type)
        (at/placeholder-type? source-type)
        (at/inf-cycle-type? source-type))
    (ascs/cast-ok source-type target-type :residual-dynamic)

    (at/numeric-dyn-type? source-type)
    (if (avc/leaf-overlap? source-type target-type)
      (ascs/cast-ok source-type target-type :leaf-overlap)
      (ascs/cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

    (or (at/ground-type? source-type)
        (at/refinement-type? source-type)
        (at/adapter-leaf-type? source-type))
    (if (avc/leaf-overlap? source-type target-type)
      (ascs/cast-ok source-type target-type :leaf-overlap)
      (ascs/cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

    (at/fun-type? source-type)
    (if (and (at/adapter-leaf-type? target-type)
             ((:accepts? target-type) identity))
      (ascs/cast-ok source-type target-type :fun-fn-pred)
      (ascs/cast-fail source-type target-type :fun-fn-pred polarity :mismatch))

    :else
    (ascs/cast-fail source-type target-type :mismatch polarity :mismatch)))
