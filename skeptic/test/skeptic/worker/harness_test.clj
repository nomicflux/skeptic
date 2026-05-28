(ns skeptic.worker.harness-test
  "Committed two-JVM round-trips: spawn a real worker JVM, connect over the EDN
   transport, exercise each handle-shaped op, tear the worker down. Plan 2
   Phase 1.5 extends the original ping round-trip with the handle-table API."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.worker.process :as proc]
            [skeptic.worker.client :as wc]
            [skeptic.analysis.class-oracle :as oracle]))

(defn- with-worker
  "Spawns a worker, runs `f` with a connected client, tears down."
  [f]
  (let [cp (proc/worker-classpath (System/getProperty "java.class.path"))
        worker (proc/spawn! cp)]
    (try
      (f (wc/connect (:port worker)))
      (finally
        (proc/stop! worker)))))

(deftest worker-ping-round-trip
  (with-worker
    (fn [conn]
      (is (= "ok" (:pong (wc/ask conn {:op "ping"})))))))

(deftest intern-host-classes-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (testing "Number has a handle"
          (is (some? (get m Number))))
        (testing "every handle is an integer"
          (is (every? integer? (vals m))))
        (testing "every key is a Class"
          (is (every? class? (keys m))))
        (testing "host-handle returns the same handle as the map"
          (binding [oracle/*host-class-handles* m]
            (is (= (get m Number) (oracle/host-handle Number)))))))))

(deftest class-rel-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)
            number-h (get m Number)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [long-h (oracle/resolve-class-sym 'clojure.core 'Long)]
            (testing "Long resolves to a UUID handle"
              (is (string? long-h))
              (is (oracle/handle? long-h)))
            (testing ":assignable-from Number<-Long is true"
              (is (true? (oracle/class-rel :assignable-from number-h long-h))))
            (testing ":assignable-from Long<-Number is false"
              (is (false? (oracle/class-rel :assignable-from long-h number-h))))
            (testing ":equals Long Long is true"
              (is (true? (oracle/class-rel :equals long-h long-h))))))))))

(deftest resolve-class-sym-round-trip
  (with-worker
    (fn [conn]
      (binding [oracle/*worker-conn* conn]
        (testing "String resolves to a non-nil UUID handle"
          (let [h (oracle/resolve-class-sym 'clojure.core 'String)]
            (is (some? h))
            (is (oracle/handle? h))))
        (testing "NoSuchClassZZZ resolves to nil"
          (is (nil? (oracle/resolve-class-sym 'clojure.core 'NoSuchClassZZZ))))))))
