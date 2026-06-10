(ns skeptic.worker.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.transport :as nrepl-transport]
            [skeptic.worker.transport :as transport])
  (:import [java.io BufferedOutputStream DataOutputStream]
           [java.net ServerSocket Socket]
           [java.util Arrays]))

(defn- socket-pair
  []
  (let [server (ServerSocket. 0)
        client (Socket. "127.0.0.1" (.getLocalPort server))
        accepted (.accept server)]
    (.close server)
    [client accepted]))

(defn- with-transport-pair
  [f]
  (let [[a-socket b-socket] (socket-pair)
        a (transport/transit a-socket)
        b (transport/transit b-socket)]
    (try
      (f a b)
      (finally
        (.close ^java.io.Closeable a)
        (.close ^java.io.Closeable b)))))

(deftest sequential-messages-do-not-desynchronize
  (with-transport-pair
    (fn [a b]
      (let [first-msg {:op "first" :payload [1 2 3]}
            second-msg {:op "second" :payload {:nested #{:a :b}}}]
        (nrepl-transport/send a first-msg)
        (nrepl-transport/send a second-msg)
        (is (= first-msg (nrepl-transport/recv b 1000)))
        (is (= second-msg (nrepl-transport/recv b 1000)))))))

(deftest rich-payload-round-trips
  (with-transport-pair
    (fn [a b]
      (let [bytes (byte-array (map unchecked-byte (range 256)))
            msg {:keyword :alpha
                 :symbol 'skeptic.worker.transport-test/value
                 :set #{:a 'b 42}
                 :nested [{:x [1 2 {:y #{:z}}]}]
                 :large-string (apply str (repeat 65536 "x"))
                 :bytes bytes}]
        (nrepl-transport/send a msg)
        (let [reply (nrepl-transport/recv b 1000)]
          (testing "Clojure data survives the binary frame"
            (is (= (dissoc msg :bytes) (dissoc reply :bytes))))
          (testing "byte payload survives without text encoding"
            (is (Arrays/equals ^bytes bytes ^bytes (:bytes reply)))))))))

(deftest clean-close-returns-nil
  (let [[a-socket b-socket] (socket-pair)
        a (transport/transit a-socket)
        b (transport/transit b-socket)]
    (try
      (.close ^java.io.Closeable a)
      (is (nil? (nrepl-transport/recv b 1000)))
      (finally
        (.close ^java.io.Closeable b)))))

(deftest timeout-throws-instead-of-masquerading-as-eof
  (with-transport-pair
    (fn [_a b]
      (is (thrown? java.net.SocketTimeoutException
                   (nrepl-transport/recv b 50))))))

(deftest negative-frame-length-is-rejected
  (let [[writer-socket reader-socket] (socket-pair)
        out (DataOutputStream.
             (BufferedOutputStream. (.getOutputStream writer-socket)))
        reader (transport/transit reader-socket)]
    (try
      (.writeInt out -1)
      (.flush out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Negative Transit frame length"
                            (nrepl-transport/recv reader 1000)))
      (finally
        (.close writer-socket)
        (.close ^java.io.Closeable reader)))))
