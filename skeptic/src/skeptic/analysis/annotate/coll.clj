(ns skeptic.analysis.annotate.coll
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs])
  (:import [clojure.lang LazySeq]))

(defn const-long-value
  [node]
  (when (and node (aapi/const-node? node))
    (let [value (:val node)]
      (when (integer? value) value))))

(s/defn seqish-element-type :- (s/maybe ats/SemanticType)
  "Joined element type across the entire (vector|seq), prefix items + tail.
  Returns nil for an empty closed coll."
  [type :- ats/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (or (at/vector-type? type) (at/seq-type? type))
      (let [items (vec (:items type))
            tail  (:tail type)
            all   (cond-> items tail (conj tail))]
        (when (seq all)
          (av/join (ato/derive-prov type) all))))))

(s/defn vector-to-homogeneous-seq-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (when (at/vector-type? (ato/normalize type))
    (let [type (ato/normalize type)]
      (when-let [elem (seqish-element-type type)]
        (let [elem (ato/normalize elem)]
          (at/->SeqT (prov/with-refs (ato/derive-prov type) [(prov/of elem)]) [] elem))))))

(s/defn vector-slot-type :- (s/maybe ats/SemanticType)
  [vector-type :- ats/SemanticType, idx :- s/Int]
  (when (and (at/vector-type? vector-type) (integer? idx) (<= 0 idx))
    (let [items (:items vector-type)
          tail  (:tail vector-type)]
      (cond
        (< idx (count items)) (ato/normalize (nth items idx))
        tail                  (ato/normalize tail)
        :else nil))))

(s/defn instance-nth-element-type :- (s/maybe ats/SemanticType)
  [coll-type :- ats/SemanticType, idx-node :- s/Any]
  (let [coll-type (ato/normalize coll-type)
        literal (const-long-value idx-node)]
    (cond
      (at/vector-type? coll-type)
      (or (when literal (vector-slot-type coll-type literal))
          (seqish-element-type coll-type))

      (at/seq-type? coll-type)
      (when (or (nil? literal) (and (nat-int? literal) (some? (:tail coll-type))))
        (seqish-element-type coll-type))

      :else nil)))

(s/defn coll-first-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (or (at/vector-type? type) (at/seq-type? type))
      (or (some-> (first (:items type)) ato/normalize)
          (some-> (:tail type) ato/normalize)))))

(s/defn coll-second-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (or (at/vector-type? type) (at/seq-type? type))
      (or (some-> (second (:items type)) ato/normalize)
          (some-> (:tail type) ato/normalize)))))

(s/defn coll-last-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (or (at/vector-type? type) (at/seq-type? type))
      (let [items (vec (:items type))
            tail  (:tail type)]
        (cond
          (and tail (seq items)) (av/join (ato/derive-prov type) [(peek items) tail])
          tail                   (ato/normalize tail)
          (seq items)            (ato/normalize (peek items))
          :else nil)))))

(s/defn coll-rest-output-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)
        prov (ato/derive-prov type)]
    (when (or (at/vector-type? type) (at/seq-type? type))
      (when-let [elem (seqish-element-type type)]
        (let [elem (ato/normalize elem)]
          (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [] elem))))))

(s/defn coll-butlast-output-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)]
    (when (and (at/vector-type? type)
               (nil? (:tail type))
               (> (count (:items type)) 0))
      (let [items (mapv ato/normalize (pop (vec (:items type))))]
        (at/->VectorT (prov/with-refs (ato/derive-prov type) (mapv prov/of items)) items nil)))))

(s/defn coll-drop-last-output-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (pos-int? n)
               (at/vector-type? type)
               (nil? (:tail type)))
      (let [items (vec (:items type))
            count-items (count items)
            kept (mapv ato/normalize (subvec items 0 (- count-items (min n count-items))))]
        (at/->VectorT (prov/with-refs (ato/derive-prov type) (mapv prov/of kept)) kept nil)))))

(s/defn coll-take-prefix-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (nat-int? n) (at/vector-type? type))
      (let [items (vec (:items type))
            n-items (count items)
            kept (mapv ato/normalize (subvec items 0 (min n n-items)))
            tail' (when (and (:tail type) (> n n-items)) (:tail type))
            refs (cond-> (mapv prov/of kept) tail' (conj (prov/of tail')))]
        (at/->VectorT (prov/with-refs (ato/derive-prov type) refs) kept tail')))))

(s/defn coll-drop-prefix-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (nat-int? n) (at/vector-type? type))
      (let [items (vec (:items type))
            tail' (:tail type)
            kept (mapv ato/normalize (subvec items (min n (count items)) (count items)))
            refs (cond-> (mapv prov/of kept) tail' (conj (prov/of tail')))
            prov (ato/derive-prov type)]
        (at/->VectorT (prov/with-refs prov refs) kept tail')))))

(s/defn coll-same-element-seq-type :- (s/maybe ats/SemanticType)
  [type :- ats/SemanticType]
  (when-let [elem (seqish-element-type type)]
    (at/->SeqT (prov/with-refs (ato/derive-prov elem) [(prov/of elem)]) [] elem)))

(s/defn concat-output-type :- (s/maybe ats/SemanticType)
  [anchor-prov :- provs/Provenance, args :- [s/Any]]
  (let [arg-types (map :type args)
        elems (keep seqish-element-type arg-types)]
    (cond
      (empty? args) (at/->SeqT (prov/with-refs anchor-prov []) [] (at/Dyn anchor-prov))
      (= (count elems) (count args))
      (let [joined (av/join anchor-prov elems)]
        (at/->SeqT (prov/with-refs anchor-prov [(prov/of joined)]) [] joined))
      :else nil)))

(s/defn into-output-type :- (s/maybe ats/SemanticType)
  [args :- [s/Any]]
  (when (>= (count args) 2)
    (let [to-type (ato/normalize (:type (first args)))
          from-type (ato/normalize (:type (second args)))
          to-elem (seqish-element-type to-type)
          from-elem (seqish-element-type from-type)
          prov (ato/derive-prov to-type from-type)
          elem (cond
                 (and to-elem from-elem) (av/join prov [to-elem from-elem])
                 from-elem from-elem
                 to-elem to-elem
                 :else nil)]
      (when elem
        (if (at/vector-type? to-type)
          (at/->VectorT (prov/with-refs prov [(prov/of elem)]) [] elem)
          (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [] elem))))))

(s/defn invoke-nth-output-type :- (s/maybe ats/SemanticType)
  [args :- [s/Any]]
  (when (>= (count args) 2)
    (instance-nth-element-type (:type (first args)) (second args))))

(s/defn for-body-element-type :- (s/maybe ats/SemanticType)
  [body :- s/Any]
  (let [cons-types
        (->> (sac/ast-nodes body)
             (keep (fn [node]
                     (when (aapi/invoke-node? node)
                       (let [fn-sym (or (ac/var->sym (:var (:fn node)))
                                        (-> node :fn :form))]
                         (when (contains? #{'clojure.core/cons 'cons} fn-sym)
                           (some-> node :args first :type))))))
             vec)]
    (when (seq cons-types)
      (ato/normalize (av/join (ato/derive-prov (:type body)) cons-types)))))

(s/defn lazy-seq-new-type :- (s/maybe ats/SemanticType)
  [class-node :- s/Any, args :- [s/Any]]
  (when (and (aapi/const-node? class-node)
             (= LazySeq (:val class-node)))
    (let [fn-arg (first args)
          prov (ato/derive-prov (:type fn-arg))
          elem
          (if (and (seq args)
                   (aapi/fn-node? fn-arg)
                   (= 1 (count (:methods fn-arg)))
                   (empty? (:params (first (:methods fn-arg)))))
            (let [body (:body (first (:methods fn-arg)))
                  body-type (-> body :type ato/normalize)
                  body-type (if (at/maybe-type? body-type)
                              (ato/normalize (:inner body-type))
                              body-type)]
              (if (ato/uninformative-for-narrowing? body-type)
                (or (some-> (for-body-element-type body) ato/normalize)
                    (at/Dyn prov))
                body-type))
            (at/Dyn prov))]
      (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [] elem))))
