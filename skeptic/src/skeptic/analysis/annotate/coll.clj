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
    (let [value (:val node)]
      (when (integer? value) value))))

(defn vec-homogeneous-items?
  [items]
  (and (seq items) (apply = items)))

(defn seqish-element-type
  [type]
  (when-some [type (some-> type ato/normalize-type)]
    (cond
      (and (at/vector-type? type) (:homogeneous? type)) (first (:items type))
      (and (at/seq-type? type) (:homogeneous? type)) (first (:items type))
      (at/vector-type? type) (av/type-join* (:items type))
      (at/seq-type? type) (av/type-join* (:items type))
      :else nil)))

(defn vector-to-homogeneous-seq-type
  [type]
  (when (at/vector-type? (ato/normalize-type type))
    (let [type (ato/normalize-type type)
          elem (if (:homogeneous? type)
                 (first (:items type))
                 (av/type-join* (:items type)))]
      (at/->SeqT [(ato/normalize-type elem)] true))))

(defn vector-slot-type
  [vector-type idx]
  (when (and (at/vector-type? vector-type)
             (integer? idx)
             (<= 0 idx)
             (< idx (count (:items vector-type))))
    (ato/normalize-type (nth (:items vector-type) idx))))

(defn instance-nth-element-type
  [coll-type idx-node]
  (let [coll-type (ato/normalize-type coll-type)
        literal (const-long-value idx-node)]
    (cond
      (at/vector-type? coll-type)
      (or (when literal (vector-slot-type coll-type literal))
          (ato/normalize-type (if (:homogeneous? coll-type)
                                (first (:items coll-type))
                                (av/type-join* (:items coll-type)))))

      (at/seq-type? coll-type)
      (when (or (nil? literal) (and (nat-int? literal) (:homogeneous? coll-type)))
        (ato/normalize-type (if (:homogeneous? coll-type)
                              (first (:items coll-type))
                              (av/type-join* (:items coll-type)))))

      :else nil)))

(defn coll-first-type
  [type]
  (when-some [type (some-> type ato/normalize-type)]
    (cond
      (and (at/vector-type? type) (seq (:items type)))
      (ato/normalize-type (first (:items type)))

      (and (at/seq-type? type) (seq (:items type)))
      (if (:homogeneous? type)
        (ato/normalize-type (first (:items type)))
        (av/type-join* (:items type)))
      :else nil)))

(defn coll-second-type
  [type]
  (when-some [type (some-> type ato/normalize-type)]
    (cond
      (and (at/vector-type? type) (>= (count (:items type)) 2))
      (ato/normalize-type (nth (:items type) 1))

      (and (at/seq-type? type) (:homogeneous? type) (seq (:items type)))
      (ato/normalize-type (first (:items type)))

      (at/seq-type? type)
      (av/type-join* (:items type))
      :else nil)))

(defn coll-last-type
  [type]
  (when-some [type (some-> type ato/normalize-type)]
    (cond
      (and (at/vector-type? type) (seq (:items type)))
      (ato/normalize-type (peek (vec (:items type))))

      (and (at/seq-type? type) (seq (:items type)))
      (if (:homogeneous? type)
        (ato/normalize-type (first (:items type)))
        (av/type-join* (:items type)))
      :else nil)))

(defn coll-rest-output-type
  [type]
  (let [type (ato/normalize-type type)]
    (cond
      (and (at/vector-type? type) (> (count (:items type)) 1))
      (let [tail (mapv ato/normalize-type (rest (:items type)))]
        (at/->VectorT tail (vec-homogeneous-items? tail)))

      (and (at/vector-type? type) (seq (:items type)))
      (let [elem (if (:homogeneous? type)
                   (first (:items type))
                   (av/type-join* (:items type)))]
        (at/->SeqT [(ato/normalize-type elem)] true))

      (at/seq-type? type)
      (let [elem (if (:homogeneous? type)
                   (first (:items type))
                   (av/type-join* (:items type)))]
        (at/->SeqT [elem] (:homogeneous? type)))
      :else nil)))

(defn coll-butlast-output-type
  [type]
  (when (and (at/vector-type? (ato/normalize-type type))
             (> (count (:items (ato/normalize-type type))) 1))
    (let [items (pop (vec (:items (ato/normalize-type type))))
          items (mapv ato/normalize-type items)]
      (at/->VectorT items (vec-homogeneous-items? items)))))

(defn coll-drop-last-output-type
  [type n]
  (when (and (pos-int? n) (at/vector-type? (ato/normalize-type type)))
    (let [items (vec (:items (ato/normalize-type type)))
          count-items (count items)
          kept (subvec items 0 (- count-items (min n count-items)))
          kept (mapv ato/normalize-type kept)]
      (at/->VectorT kept (vec-homogeneous-items? kept)))))

(defn coll-take-prefix-type
  [type n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize-type type)))
    (let [items (vec (:items (ato/normalize-type type)))
          kept (subvec items 0 (min n (count items)))
          kept (mapv ato/normalize-type kept)]
      (at/->VectorT kept (vec-homogeneous-items? kept)))))

(defn coll-drop-prefix-type
  [type n]
  (when (and (nat-int? n) (at/vector-type? (ato/normalize-type type)))
    (let [items (vec (:items (ato/normalize-type type)))
          tail (subvec items (min n (count items)) (count items))
          tail (mapv ato/normalize-type tail)]
      (if (empty? tail)
        (at/->VectorT [] true)
        (at/->VectorT tail (vec-homogeneous-items? tail))))))

(defn coll-same-element-seq-type
  [type]
  (when-let [elem (seqish-element-type type)]
    (at/->SeqT [elem] true)))

(defn concat-output-type
  [args]
  (let [elems (keep (comp seqish-element-type :type) args)]
    (cond
      (empty? args) (at/->SeqT [at/Dyn] true)
      (= (count elems) (count args)) (at/->SeqT [(av/type-join* elems)] true)
      :else nil)))

(defn into-output-type
  [args]
  (when (>= (count args) 2)
    (let [to-type (ato/normalize-type (:type (first args)))
          from-type (ato/normalize-type (:type (second args)))
          to-elem (seqish-element-type to-type)
          from-elem (seqish-element-type from-type)
          elem (cond
                 (and to-elem from-elem) (av/type-join* [to-elem from-elem])
                 from-elem from-elem
                 to-elem to-elem
                 :else nil)]
      (when elem
        (if (at/vector-type? to-type)
          (at/->VectorT [elem] true)
          (at/->SeqT [elem] true))))))

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
      (ato/normalize-type (av/type-join* cons-types)))))

(defn lazy-seq-new-type
  [class-node args]
  (when (and (= :const (:op class-node))
             (= LazySeq (:val class-node)))
    (let [elem
          (if (and (seq args)
                   (= :fn (:op (first args)))
                   (= 1 (count (:methods (first args))))
                   (empty? (:params (first (:methods (first args))))))
            (let [body (:body (first (:methods (first args))))
                  body-type (-> body :type ato/normalize-type)
                  body-type (if (at/maybe-type? body-type)
                              (ato/normalize-type (:inner body-type))
                              body-type)]
              (if (ato/unknown-type? body-type)
                (or (some-> (for-body-element-type body) ato/normalize-type)
                    at/Dyn)
                body-type))
            at/Dyn)]
      (at/->SeqT [elem] true))))
