(ns skeptic.analysis.annotate.data-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.data :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [tp]]))

(defn- make-ctx []
  (prov/set-ctx {} tp))

(defn- make-node-with-items [op items]
  {:op op
   :items items
   :form (if (= op :vector) (vec items) (set items))})

(defn- make-node-with-keys-vals [keys vals]
  {:op :map
   :keys keys
   :vals vals
   :form (into {} (map vector keys vals))})

(deftest annotate-vector-threads-refs-test
  (testing "vector with two items threads their provs into result's refs"
    (let [ctx (make-ctx)
          int-node {:op :const :type (at/->GroundT tp :int 'Int) :form 1}
          str-node {:op :const :type (at/->GroundT tp :str 'Str) :form "x"}
          node (make-node-with-items :vector [int-node str-node])
          ctx-with-recurse (assoc ctx :recurse (fn [_ n] n))
          result (sut/annotate-vector ctx-with-recurse node)
          result-type (:type result)
          refs (:refs (:prov result-type))]
      (is (= 2 (count refs)))
      (is (= tp (first refs)))
      (is (= tp (second refs))))))

(deftest annotate-set-threads-refs-test
  (testing "set with one item threads its prov into result's refs"
    (let [ctx (make-ctx)
          int-node {:op :const :type (at/->GroundT tp :int 'Int) :form 1}
          node (make-node-with-items :set [int-node])
          ctx-with-recurse (assoc ctx :recurse (fn [_ n] n))
          result (sut/annotate-set ctx-with-recurse node)
          result-type (:type result)
          refs (:refs (:prov result-type))]
      (is (= 1 (count refs))))))

(deftest annotate-map-threads-refs-test
  (testing "map with one key/val pair threads both provs into result's refs"
    (let [ctx (make-ctx)
          key-node {:op :const :type (at/->GroundT tp :keyword 'Keyword) :form :a}
          val-node {:op :const :type (at/->GroundT tp :int 'Int) :form 1}
          node (make-node-with-keys-vals [key-node] [val-node])
          ctx-with-recurse (assoc ctx :recurse (fn [_ n] n))
          result (sut/annotate-map ctx-with-recurse node)
          result-type (:type result)
          refs (:refs (:prov result-type))]
      (is (= 2 (count refs)))
      (is (= tp (first refs)))
      (is (= tp (second refs))))))
