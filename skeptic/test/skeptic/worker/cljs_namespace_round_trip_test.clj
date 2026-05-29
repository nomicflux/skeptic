(ns skeptic.worker.cljs-namespace-round-trip-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [skeptic.worker.harness-test :refer [with-worker]]
            [skeptic.worker.client :as wc]))

(deftest analyze-cljs-namespace-round-trip
  (with-worker
    (fn [conn]
      ;; cljs ASTs carry no leaf Class identities: the cljs analyzer emits :class
      ;; only as a child NODE on :new, never a reflected Class leaf the way
      ;; tools.analyzer.jvm does on the clj path. So the cljs wire contract is the
      ;; entry/ns-ast shape plus EDN round-trip, with no leaf-:class handle check.
      (let [reply (wc/ask conn {:op "analyze-cljs-namespace"
                                :source-file "dev-resources/cljs-fixtures/p1.cljs"})]
        (testing "reply has :ns-ast and :entries"
          (is (map? (:ns-ast reply)))
          (is (vector? (:entries reply))))
        (testing "ns-ast round-trips through EDN reader"
          (let [ns-ast (:ns-ast reply)]
            (is (= ns-ast (edn/read-string (pr-str ns-ast))))))
        (testing "every entry round-trips through EDN reader"
          (is (every? #(= % (edn/read-string (pr-str %))) (:entries reply))))))))
