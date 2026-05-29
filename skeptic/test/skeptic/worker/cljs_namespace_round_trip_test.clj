(ns skeptic.worker.cljs-namespace-round-trip-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [skeptic.worker.harness-test :refer [with-worker]]
            [skeptic.worker.client :as wc]
            [skeptic.analysis.class-oracle :as oracle]))

(defn- leaf-class-slots
  [ast]
  (->> (tree-seq coll? seq ast)
       (filter #(and (map? %) (contains? % :class)))
       (map :class)
       (remove coll?)))

(deftest analyze-cljs-namespace-round-trip
  (with-worker
    (fn [conn]
      (oracle/intern-host-classes! conn)
      (let [reply (wc/ask conn {:op "analyze-cljs-namespace"
                                :source-file "dev-resources/cljs-fixtures/p1.cljs"})]
        (testing "reply has :ns-ast and :entries"
          (is (map? (:ns-ast reply)))
          (is (vector? (:entries reply))))
        (testing "ns-ast round-trips through EDN reader"
          (let [ns-ast (:ns-ast reply)]
            (is (= ns-ast (edn/read-string (pr-str ns-ast))))))
        (testing "every entry round-trips through EDN reader"
          (is (every? #(= % (edn/read-string (pr-str %))) (:entries reply))))
        (testing "every leaf :class in entries is a handle"
          (let [classes (mapcat (comp leaf-class-slots :ast) (filter :ast (:entries reply)))]
            (is (seq classes))
            (is (every? oracle/handle? classes))))))))
