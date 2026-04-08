(ns skeptic.analysis.annotate.coll
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av])
  (:import [clojure.lang LazySeq]))

(defn const-long-value
  [node]
  (when (= :const (:op node))
    (let [v (:val node)]
      (when (integer? v) v))))

(defn vec-homogeneous-items?
  [items]
  (and (seq items) (apply = items)))

(defn seqish-element-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (cond
      (and (at/vector-type? t) (:homogeneous? t)) (first (:items t))
      (and (at/seq-type? t) (:homogeneous? t)) (first (:items t))
      (at/vector-type? t) (av/type-join* (:items t))
      (at/seq-type? t) (av/type-join* (:items t))
      :else nil)))

(defn vector-to-homogeneous-seq-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (when (at/vector-type? t)
      (let [elem (if (:homogeneous? t)
                   (first (:items t))
                   (av/type-join* (:items t)))]
        (at/->SeqT [(ato/normalize-type elem)] true)))))

(defn vector-slot-type
  [vtype idx]
  (when (and (at/vector-type? vtype)
             (integer? idx)
             (<= 0 idx)
             (< idx (count (:items vtype))))
    (ato/normalize-type (nth (:items vtype) idx))))

(defn instance-nth-element-type
  [coll-type idx-node]
  (let [it (ato/normalize-type coll-type)
        lit (const-long-value idx-node)]
    (cond
      (at/vector-type? it)
      (or (when lit (vector-slot-type it lit))
          (ato/normalize-type (if (:homogeneous? it)
                                (first (:items it))
                                (av/type-join* (:items it)))))

      (at/seq-type? it)
      (when (or (nil? lit) (and (nat-int? lit) (:homogeneous? it)))
        (ato/normalize-type (if (:homogeneous? it)
                              (first (:items it))
                              (av/type-join* (:items it)))))

      :else nil)))

(defn coll-first-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (cond
      (and (at/vector-type? t) (seq (:items t)))
      (ato/normalize-type (first (:items t)))
      (and (at/seq-type? t) (seq (:items t)))
      (if (:homogeneous? t)
        (ato/normalize-type (first (:items t)))
        (av/type-join* (:items t)))
      :else nil)))

(defn coll-second-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (cond
      (and (at/vector-type? t) (>= (count (:items t)) 2))
      (ato/normalize-type (nth (:items t) 1))
      (and (at/seq-type? t) (:homogeneous? t) (seq (:items t)))
      (ato/normalize-type (first (:items t)))
      (at/seq-type? t)
      (av/type-join* (:items t))
      :else nil)))

(defn coll-last-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (cond
      (and (at/vector-type? t) (seq (:items t)))
      (ato/normalize-type (peek (vec (:items t))))
      (and (at/seq-type? t) (seq (:items t)))
      (if (:homogeneous? t)
        (ato/normalize-type (first (:items t)))
        (av/type-join* (:items t)))
      :else nil)))

(defn coll-rest-output-type
  [t]
  (let [t (ato/normalize-type t)]
    (cond
      (and (at/vector-type? t) (> (count (:items t)) 1))
      (let [tail (mapv ato/normalize-type (rest (:items t)))]
        (at/->VectorT tail (vec-homogeneous-items? tail)))
      (and (at/vector-type? t) (seq (:items t)))
      (let [e (if (:homogeneous? t)
                (first (:items t))
                (av/type-join* (:items t)))]
        (at/->SeqT [(ato/normalize-type e)] true))
      (at/seq-type? t)
      (let [e (if (:homogeneous? t)
                (first (:items t))
                (av/type-join* (:items t)))]
        (at/->SeqT [e] (:homogeneous? t)))
      :else nil)))

(defn coll-butlast-output-type
  [t]
  (let [t (ato/normalize-type t)]
    (when (and (at/vector-type? t) (> (count (:items t)) 1))
      (let [but (mapv ato/normalize-type (pop (vec (:items t))))]
        (at/->VectorT but (vec-homogeneous-items? but))))))

(defn coll-drop-last-output-type
  [t n]
  (when (and (pos-int? n) (at/vector-type? (ato/normalize-type t)))
    (let [t (ato/normalize-type t)
          items (:items t)
          c (count items)
          k (min n c)
          but (mapv ato/normalize-type (subvec (vec items) 0 (- c k)))]
      (at/->VectorT but (vec-homogeneous-items? but)))))

(defn coll-take-prefix-type
  [t n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize-type t)))
    (let [t (ato/normalize-type t)
          items (:items t)
          c (count items)
          take-n (min n c)
          pref (mapv ato/normalize-type (subvec (vec items) 0 take-n))]
      (at/->VectorT pref (vec-homogeneous-items? pref)))))

(defn coll-drop-prefix-type
  [t n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize-type t)))
    (let [t (ato/normalize-type t)
          items (:items t)
          c (count items)
          skip (min n c)
          tail (mapv ato/normalize-type (subvec (vec items) skip c))]
      (if (empty? tail)
        (at/->VectorT [] true)
        (at/->VectorT tail (vec-homogeneous-items? tail))))))

(defn coll-same-element-seq-type
  [t]
  (when-let [e (seqish-element-type t)]
    (at/->SeqT [e] true)))

(defn concat-output-type
  [args]
  (let [ts (map (comp ato/normalize-type :type) args)
        elems (keep seqish-element-type ts)]
    (if (empty? args)
      (at/->SeqT [at/Dyn] true)
      (when (and (seq elems) (= (count elems) (count args)))
        (at/->SeqT [(av/type-join* elems)] true)))))

(defn into-output-type
  [args]
  (when (>= (count args) 2)
    (let [to (ato/normalize-type (:type (first args)))
          from (ato/normalize-type (:type (second args)))
          e-to (seqish-element-type to)
          e-from (seqish-element-type from)
          e (cond
              (and e-to e-from) (av/type-join* [e-to e-from])
              e-from e-from
              e-to e-to
              :else nil)]
      (when e
        (if (at/vector-type? to)
          (at/->VectorT [e] true)
          (at/->SeqT [e] true))))))

(defn invoke-nth-output-type
  [args]
  (when (>= (count args) 2)
    (instance-nth-element-type (:type (first args)) (second args))))

(defn for-body-element-type
  [body]
  (let [cars (->> (sac/ast-nodes body)
                  (keep (fn [node]
                          (when (= :invoke (:op node))
                            (let [fn-sym (or (ac/var->sym (:var (:fn node)))
                                             (-> node :fn :form))]
                              (when (contains? #{'clojure.core/cons 'cons} fn-sym)
                                (some-> node :args first :type))))))
                  vec)]
    (when (seq cars)
      (ato/normalize-type (av/type-join* cars)))))

(defn lazy-seq-new-type
  [class-node args]
  (when (and (= :const (:op class-node))
             (= LazySeq (:val class-node)))
    (let [elem (if (and (seq args)
                        (= :fn (:op (first args)))
                        (= 1 (count (:methods (first args))))
                        (empty? (:params (first (:methods (first args))))))
                 (let [body (:body (first (:methods (first args))))
                       t (-> body :type ato/normalize-type)
                       t (if (at/maybe-type? t)
                           (ato/normalize-type (:inner t))
                           t)]
                   (if (ato/unknown-type? t)
                     (or (some-> (for-body-element-type body) ato/normalize-type)
                         at/Dyn)
                     t))
                 at/Dyn)]
      (at/->SeqT [elem] true))))
