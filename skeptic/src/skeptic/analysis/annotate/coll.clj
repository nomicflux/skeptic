(ns skeptic.analysis.annotate.coll
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov])
  (:import [clojure.lang LazySeq]))

(defn const-long-value
  [node]
  (when (= :const (:op node))
    (let [value (:val node)]
      (when (integer? value) value))))

(defn vec-homogeneous-items?
  [items]
  (and (seq items) (apply = items)))

(defn seqish-element-type
  [type]
  (when-some [type (some-> type ato/normalize)]
    (cond
      (and (at/vector-type? type) (:homogeneous? type)) (first (:items type))
      (and (at/seq-type? type) (:homogeneous? type)) (first (:items type))
      (at/vector-type? type) (av/join (ato/derive-prov type) (:items type))
      (at/seq-type? type) (av/join (ato/derive-prov type) (:items type))
      :else nil)))

(defn vector-to-homogeneous-seq-type
  [type]
  (when (at/vector-type? (ato/normalize type))
    (let [type (ato/normalize type)
          elem (if (:homogeneous? type)
                 (first (:items type))
                 (av/join (ato/derive-prov type) (:items type)))]
      (at/->SeqT (prov/with-refs (ato/derive-prov type) [(prov/of elem)]) [(ato/normalize elem)] true))))

(defn vector-slot-type
  [vector-type idx]
  (when (and (at/vector-type? vector-type)
             (integer? idx)
             (<= 0 idx)
             (< idx (count (:items vector-type))))
    (ato/normalize (nth (:items vector-type) idx))))

(defn instance-nth-element-type
  [coll-type idx-node]
  (let [coll-type (ato/normalize coll-type)
        literal (const-long-value idx-node)]
    (cond
      (at/vector-type? coll-type)
      (or (when literal (vector-slot-type coll-type literal))
          (ato/normalize (if (:homogeneous? coll-type)
                           (first (:items coll-type))
                           (av/join (ato/derive-prov coll-type) (:items coll-type)))))

      (at/seq-type? coll-type)
      (when (or (nil? literal) (and (nat-int? literal) (:homogeneous? coll-type)))
        (ato/normalize (if (:homogeneous? coll-type)
                         (first (:items coll-type))
                         (av/join (ato/derive-prov coll-type) (:items coll-type)))))

      :else nil)))

(defn coll-first-type
  [type]
  (when-some [type (some-> type ato/normalize)]
    (cond
      (and (at/vector-type? type) (seq (:items type)))
      (ato/normalize (first (:items type)))

      (and (at/seq-type? type) (seq (:items type)))
      (if (:homogeneous? type)
        (ato/normalize (first (:items type)))
        (av/join (ato/derive-prov type) (:items type)))
      :else nil)))

(defn coll-second-type
  [type]
  (when-some [type (some-> type ato/normalize)]
    (cond
      (and (at/vector-type? type) (>= (count (:items type)) 2))
      (ato/normalize (nth (:items type) 1))

      (and (at/seq-type? type) (:homogeneous? type) (seq (:items type)))
      (ato/normalize (first (:items type)))

      (at/seq-type? type)
      (av/join (ato/derive-prov type) (:items type))
      :else nil)))

(defn coll-last-type
  [type]
  (when-some [type (some-> type ato/normalize)]
    (cond
      (and (at/vector-type? type) (seq (:items type)))
      (ato/normalize (peek (vec (:items type))))

      (and (at/seq-type? type) (seq (:items type)))
      (if (:homogeneous? type)
        (ato/normalize (first (:items type)))
        (av/join (ato/derive-prov type) (:items type)))
      :else nil)))

(defn coll-rest-output-type
  [type]
  (let [type (ato/normalize type)
        prov (ato/derive-prov type)]
    (cond
      (and (at/vector-type? type) (> (count (:items type)) 1))
      (let [tail (mapv ato/normalize (rest (:items type)))]
        (at/->VectorT (prov/with-refs prov (mapv prov/of tail)) tail (vec-homogeneous-items? tail)))

      (and (at/vector-type? type) (seq (:items type)))
      (let [elem (if (:homogeneous? type)
                   (first (:items type))
                   (av/join prov (:items type)))]
        (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [(ato/normalize elem)] true))

      (at/seq-type? type)
      (let [elem (if (:homogeneous? type)
                   (first (:items type))
                   (av/join prov (:items type)))]
        (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [elem] (:homogeneous? type)))
      :else nil)))

(defn coll-butlast-output-type
  [type]
  (when (and (at/vector-type? (ato/normalize type))
             (> (count (:items (ato/normalize type))) 1))
    (let [items (pop (vec (:items (ato/normalize type))))
          items (mapv ato/normalize items)]
      (at/->VectorT (prov/with-refs (ato/derive-prov type) (mapv prov/of items)) items (vec-homogeneous-items? items)))))

(defn coll-drop-last-output-type
  [type n]
  (when (and (pos-int? n) (at/vector-type? (ato/normalize type)))
    (let [items (vec (:items (ato/normalize type)))
          count-items (count items)
          kept (subvec items 0 (- count-items (min n count-items)))
          kept (mapv ato/normalize kept)]
      (at/->VectorT (prov/with-refs (ato/derive-prov type) (mapv prov/of kept)) kept (vec-homogeneous-items? kept)))))

(defn coll-take-prefix-type
  [type n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize type)))
    (let [items (vec (:items (ato/normalize type)))
          kept (subvec items 0 (min n (count items)))
          kept (mapv ato/normalize kept)]
      (at/->VectorT (prov/with-refs (ato/derive-prov type) (mapv prov/of kept)) kept (vec-homogeneous-items? kept)))))

(defn coll-drop-prefix-type
  [type n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize type)))
    (let [items (vec (:items (ato/normalize type)))
          tail (subvec items (min n (count items)) (count items))
          tail (mapv ato/normalize tail)
          prov (ato/derive-prov type)]
      (if (empty? tail)
        (at/->VectorT (prov/with-refs prov []) [] true)
        (at/->VectorT (prov/with-refs prov (mapv prov/of tail)) tail (vec-homogeneous-items? tail))))))

(defn coll-same-element-seq-type
  [type]
  (when-let [elem (seqish-element-type type)]
    (at/->SeqT (prov/with-refs (ato/derive-prov elem) [(prov/of elem)]) [elem] true)))

(defn concat-output-type
  [anchor-prov args]
  (let [arg-types (map :type args)
        elems (keep seqish-element-type arg-types)]
    (cond
      (empty? args) (at/->SeqT (prov/with-refs anchor-prov []) [(at/Dyn anchor-prov)] true)
      (= (count elems) (count args))
      (let [joined (av/join anchor-prov elems)]
        (at/->SeqT (prov/with-refs anchor-prov [(prov/of joined)]) [joined] true))
      :else nil)))

(defn into-output-type
  [args]
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
          (at/->VectorT (prov/with-refs prov [(prov/of elem)]) [elem] true)
          (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [elem] true))))))

(defn invoke-nth-output-type
  [args]
  (when (>= (count args) 2)
    (instance-nth-element-type (:type (first args)) (second args))))

(defn for-body-element-type
  [body]
  (let [cons-types
        (->> (sac/ast-nodes body)
             (keep (fn [node]
                     (when (= :invoke (:op node))
                       (let [fn-sym (or (ac/var->sym (:var (:fn node)))
                                        (-> node :fn :form))]
                         (when (contains? #{'clojure.core/cons 'cons} fn-sym)
                           (some-> node :args first :type))))))
             vec)]
    (when (seq cons-types)
      (ato/normalize (av/join (ato/derive-prov (:type body)) cons-types)))))

(defn lazy-seq-new-type
  [class-node args]
  (when (and (= :const (:op class-node))
             (= LazySeq (:val class-node)))
    (let [fn-arg (first args)
          prov (ato/derive-prov (:type fn-arg))
          elem
          (if (and (seq args)
                   (= :fn (:op fn-arg))
                   (= 1 (count (:methods fn-arg)))
                   (empty? (:params (first (:methods fn-arg)))))
            (let [body (:body (first (:methods fn-arg)))
                  body-type (-> body :type ato/normalize)
                  body-type (if (at/maybe-type? body-type)
                              (ato/normalize (:inner body-type))
                              body-type)]
              (if (ato/unknown? body-type)
                (or (some-> (for-body-element-type body) ato/normalize)
                    (at/Dyn prov))
                body-type))
            (at/Dyn prov))]
      (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [elem] true))))
