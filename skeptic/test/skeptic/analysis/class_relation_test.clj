(ns skeptic.analysis.class-relation-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.types :as at]
            [skeptic.worker.harness-test :refer [with-worker]]))

(deftest class-equals?-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [number-h (oracle/host-handle Number)
                long-h   (oracle/host-handle Long)]
            (testing "same handle equals itself"
              (is (true? (at/class-equals? number-h number-h))))
            (testing "different class handles are not equal"
              (is (false? (at/class-equals? number-h long-h))))))))))

(deftest class-assignable?-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [number-h (oracle/host-handle Number)
                long-h   (oracle/host-handle Long)]
            (testing "Number assignable-from Long is true"
              (is (true? (at/class-assignable? number-h long-h))))
            (testing "Long assignable-from Number is false"
              (is (false? (at/class-assignable? long-h number-h))))))))))

(deftest class-instance?-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [number-h (oracle/host-handle Number)]
            (testing "5 is instance of Number"
              (is (true? (at/class-instance? number-h 5))))
            (testing "\"s\" is not instance of Number"
              (is (false? (at/class-instance? number-h "s"))))))))))

(deftest class-integral?-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [long-h    (oracle/host-handle Long)
                integer-h (oracle/host-handle Integer)
                byte-h    (oracle/host-handle Byte)
                number-h  (oracle/host-handle Number)
                string-h  (oracle/host-handle String)]
            (testing "Long, Integer, Byte are integral (batched path, multiple inputs)"
              (is (true? (at/class-integral? long-h)))
              (is (true? (at/class-integral? integer-h)))
              (is (true? (at/class-integral? byte-h))))
            (testing "Number and String are not integral"
              (is (false? (at/class-integral? number-h)))
              (is (false? (at/class-integral? string-h))))))))))

(deftest class-name-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (testing "the worker returns the canonical name behind a handle"
            (is (= "java.lang.Number" (oracle/class-name (oracle/host-handle Number)))))
          (testing "nil handle yields nil"
            (is (nil? (oracle/class-name nil)))))))))

(deftest class-rel-cache-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m
                  oracle/*class-rel-cache* (atom {})]
          (let [number-h (oracle/host-handle Number)
                long-h   (oracle/host-handle Long)
                first-call (at/class-equals? number-h long-h)
                second-call (at/class-equals? number-h long-h)]
            (testing "a repeated pure rel returns the same answer and is cached"
              (is (= first-call second-call))
              (is (contains? @oracle/*class-rel-cache* [:equals number-h long-h])))
            (testing ":instance? is never cached"
              (let [before @oracle/*class-rel-cache*]
                (at/class-instance? number-h 5)
                (is (= before @oracle/*class-rel-cache*))))))))))

(deftest class-rel-batch-test
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m
                  oracle/*class-rel-cache* (atom {})]
          (let [number-h (oracle/host-handle Number)
                long-h   (oracle/host-handle Long)
                triples [{:rel :equals :a long-h :b long-h}
                         {:rel :assignable-from :a number-h :b long-h}
                         {:rel :assignable-from :a long-h :b number-h}]]
            (testing "batched results equal the per-call class-rel answers"
              (is (= (mapv #(oracle/class-rel (:rel %) (:a %) (:b %)) triples)
                     (oracle/class-rel-batch triples))))))))))
