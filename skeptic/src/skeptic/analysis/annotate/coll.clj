(ns skeptic.analysis.annotate.coll
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs])
  (:import [clojure.lang LazySeq]))

(s/defschema ^:private TypedNode
  (-> aas/AnnotatedNode
      (dissoc (s/optional-key :type))
      (assoc :type at/SemanticType)))

(defn const-long-value
  [node]
  (when (and node (aapi/const-node? node))
    (let [value (:val node)]
      (when (integer? value) value))))

(s/defn seqish-element-type :- (s/maybe at/SemanticType)
  "Joined element type across the entire (vector|seq), prefix items + tail.
  Returns nil for an empty closed coll."
  [type :- at/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (at/seq-type? type)
      (let [all (mapv :type (:pattern type))]
        (when (seq all)
          (av/join (ato/derive-prov type) all))))))

(s/defn vector-to-homogeneous-seq-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (let [type (ato/normalize type)]
    (when (and (at/seq-type? type) (= :vector (:ordered-coll-kind type)))
      (when-let [elem (seqish-element-type type)]
        (let [elem (ato/normalize elem)]
          (at/->SeqT (prov/with-refs (ato/derive-prov type) [(prov/of elem)])
                     (at/pattern-from-prefix-tail [] elem)
                     :sequential))))))

(s/defn vector-slot-type :- (s/maybe at/SemanticType)
  [vector-type :- at/SemanticType, idx :- s/Int]
  (when (and (at/seq-type? vector-type)
             (= :vector (:ordered-coll-kind vector-type))
             (integer? idx) (<= 0 idx))
    (let [items (at/pattern-prefix (:pattern vector-type))
          tail  (at/pattern-tail (:pattern vector-type))]
      (cond
        (< idx (count items)) (ato/normalize (nth items idx))
        tail                  (ato/normalize tail)
        :else nil))))

(s/defn instance-nth-element-type :- (s/maybe at/SemanticType)
  [coll-type :- at/SemanticType, idx-node :- aas/AnnotatedNode]
  (let [coll-type (ato/normalize coll-type)
        literal (const-long-value idx-node)]
    (when (at/seq-type? coll-type)
      (case (:ordered-coll-kind coll-type)
        :vector
        (or (when literal (vector-slot-type coll-type literal))
            (seqish-element-type coll-type))
        :sequential
        (when (or (nil? literal) (and (nat-int? literal) (some? (at/pattern-tail (:pattern coll-type)))))
          (seqish-element-type coll-type))))))

(s/defn coll-first-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (at/seq-type? type)
      (let [items (at/pattern-prefix (:pattern type))]
        (or (some-> (first items) ato/normalize)
            (some-> (at/pattern-tail (:pattern type)) ato/normalize))))))

(s/defn coll-second-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (at/seq-type? type)
      (let [items (at/pattern-prefix (:pattern type))]
        (or (some-> (second items) ato/normalize)
            (some-> (at/pattern-tail (:pattern type)) ato/normalize))))))

(s/defn coll-last-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (when-some [type (some-> type ato/normalize)]
    (when (at/seq-type? type)
      (let [items (at/pattern-prefix (:pattern type))
            tail  (at/pattern-tail (:pattern type))]
        (cond
          (and tail (seq items)) (av/join (ato/derive-prov type) [(peek items) tail])
          tail                   (ato/normalize tail)
          (seq items)            (ato/normalize (peek items))
          :else nil)))))

(s/defn coll-rest-output-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (let [type (ato/normalize type)
        prov (ato/derive-prov type)]
    (when (at/seq-type? type)
      (when-let [elem (seqish-element-type type)]
        (let [elem (ato/normalize elem)]
          (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                     (at/pattern-from-prefix-tail [] elem)
                     :sequential))))))

(s/defn coll-butlast-output-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (let [type (ato/normalize type)]
    (when (and (at/seq-type? type)
               (= :vector (:ordered-coll-kind type))
               (nil? (at/pattern-tail (:pattern type)))
               (> (count (at/pattern-prefix (:pattern type))) 0))
      (let [items (mapv ato/normalize (pop (at/pattern-prefix (:pattern type))))]
        (at/->SeqT (prov/with-refs (ato/derive-prov type) (mapv prov/of items))
                   (at/pattern-from-prefix-tail items nil)
                   :vector)))))

(s/defn coll-drop-last-output-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (pos-int? n)
               (at/seq-type? type)
               (= :vector (:ordered-coll-kind type))
               (nil? (at/pattern-tail (:pattern type))))
      (let [items (at/pattern-prefix (:pattern type))
            count-items (count items)
            kept (mapv ato/normalize (subvec items 0 (- count-items (min n count-items))))]
        (at/->SeqT (prov/with-refs (ato/derive-prov type) (mapv prov/of kept))
                   (at/pattern-from-prefix-tail kept nil)
                   :vector)))))

(s/defn coll-take-prefix-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (nat-int? n) (at/seq-type? type) (= :vector (:ordered-coll-kind type)))
      (let [items (at/pattern-prefix (:pattern type))
            tail  (at/pattern-tail (:pattern type))
            n-items (count items)
            kept (mapv ato/normalize (subvec items 0 (min n n-items)))
            tail' (when (and tail (> n n-items)) tail)
            refs (cond-> (mapv prov/of kept) tail' (conj (prov/of tail')))]
        (at/->SeqT (prov/with-refs (ato/derive-prov type) refs)
                   (at/pattern-from-prefix-tail kept tail')
                   :vector)))))

(s/defn coll-drop-prefix-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType, n :- s/Int]
  (let [type (ato/normalize type)]
    (when (and (nat-int? n) (at/seq-type? type) (= :vector (:ordered-coll-kind type)))
      (let [items (at/pattern-prefix (:pattern type))
            tail' (at/pattern-tail (:pattern type))
            kept (mapv ato/normalize (subvec items (min n (count items)) (count items)))
            refs (cond-> (mapv prov/of kept) tail' (conj (prov/of tail')))
            prov (ato/derive-prov type)]
        (at/->SeqT (prov/with-refs prov refs)
                   (at/pattern-from-prefix-tail kept tail')
                   :vector)))))

(s/defn coll-same-element-seq-type :- (s/maybe at/SemanticType)
  [type :- at/SemanticType]
  (when-let [elem (seqish-element-type type)]
    (at/->SeqT (prov/with-refs (ato/derive-prov elem) [(prov/of elem)])
               (at/pattern-from-prefix-tail [] elem)
               :sequential)))

(s/defn concat-output-type :- (s/maybe at/SemanticType)
  [anchor-prov :- provs/Provenance, args :- [TypedNode]]
  (let [arg-types (map :type args)
        elems (keep seqish-element-type arg-types)]
    (cond
      (empty? args) (at/->SeqT (prov/with-refs anchor-prov [])
                               (at/pattern-from-prefix-tail [] (at/Dyn anchor-prov))
                               :sequential)
      (= (count elems) (count args))
      (let [joined (av/join anchor-prov elems)]
        (at/->SeqT (prov/with-refs anchor-prov [(prov/of joined)])
                   (at/pattern-from-prefix-tail [] joined)
                   :sequential))
      :else nil)))

(s/defn into-output-type :- (s/maybe at/SemanticType)
  [args :- [TypedNode]]
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
        (if (and (at/seq-type? to-type) (= :vector (:ordered-coll-kind to-type)))
          (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                     (at/pattern-from-prefix-tail [] elem)
                     :vector)
          (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                     (at/pattern-from-prefix-tail [] elem)
                     :sequential))))))

(s/defn invoke-nth-output-type :- (s/maybe at/SemanticType)
  [args :- [TypedNode]]
  (when (>= (count args) 2)
    (instance-nth-element-type (:type (first args)) (second args))))

(s/defn for-body-element-type :- (s/maybe at/SemanticType)
  [body :- TypedNode]
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

(s/defn lazy-seq-new-type :- (s/maybe at/SemanticType)
  [class-node :- aas/AnnotatedNode, args :- [TypedNode]]
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
      (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                 (at/pattern-from-prefix-tail [] elem)
                 :sequential))))
