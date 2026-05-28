(ns skeptic.worker.harness-test
  "Committed two-JVM round-trip: spawn a real worker JVM, connect over the EDN
   transport, send the ping op, assert the reply, tear the worker down. This is
   the permanent form of the deleted PROBE-8 scaffolding."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.worker.process :as proc]
            [skeptic.worker.client :as wc]))

(deftest worker-ping-round-trip
  (let [cp (proc/worker-classpath (System/getProperty "java.class.path"))
        worker (proc/spawn! cp)]
    (try
      (let [conn (wc/connect (:port worker))
            reply (wc/ask conn {:op "ping"})]
        (is (= "ok" (:pong reply))))
      (finally
        (proc/stop! worker)))))
