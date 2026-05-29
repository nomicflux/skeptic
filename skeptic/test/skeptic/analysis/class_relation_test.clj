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
          (let [long-h   (oracle/host-handle Long)
                number-h (oracle/host-handle Number)]
            (testing "Long is integral"
              (is (true? (at/class-integral? long-h))))
            (testing "Number is not integral"
              (is (false? (at/class-integral? number-h))))))))))

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
