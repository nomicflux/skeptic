(ns skeptic.worker.transport-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.transport :as nrepl-transport]
            [skeptic.worker.transport :as transport]
            [skeptic.worker.wire :as wire])
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
        a (transport/transit false a-socket)
        b (transport/transit false b-socket)]
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
        a (transport/transit false a-socket)
        b (transport/transit false b-socket)]
    (try
      (.close ^java.io.Closeable a)
      (is (nil? (nrepl-transport/recv b 1000)))
      (finally
        (.close ^java.io.Closeable b)))))

(deftest close-message-printed-only-when-verbose
  (testing "verbose? false closes silently"
    (let [[a-socket b-socket] (socket-pair)
          a (transport/transit false a-socket)
          err (java.io.StringWriter.)]
      (binding [*err* err]
        (.close ^java.io.Closeable a))
      (.close b-socket)
      (is (= "" (str err)))))
  (testing "verbose? true announces the close on stderr"
    (let [[a-socket b-socket] (socket-pair)
          a (transport/transit true a-socket)
          err (java.io.StringWriter.)]
      (binding [*err* err]
        (.close ^java.io.Closeable a))
      (.close b-socket)
      (is (str/includes? (str err) "skeptic TransitTransport closed")))))

(deftest unknown-object-crosses-as-opaque-sentinel
  (with-transport-pair
    (fn [a b]
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (nrepl-transport/send a {:v (java.time.Instant/parse "2026-06-10T00:00:00Z")
                                   :tag :probe}))
        (let [reply (nrepl-transport/recv b 1000)
              v (:v reply)]
          (testing "the rest of the message survives"
            (is (= :probe (:tag reply))))
          (testing "the unsupported value arrives as a backstop sentinel"
            (is (wire/nonedn? v))
            (is (= "java.time.Instant" (wire/opaque-class-name v)))
            (is (string? (wire/opaque-string v))))
          (testing "the opaque write announced the class on stderr"
            (is (str/includes? (str err)
                               "skeptic transit: opaque value crossed wire: java.time.Instant"))))))))

(deftest transit-carried-leaves-round-trip-with-class-intact
  (with-transport-pair
    (fn [a b]
      (let [msg {:char \a
                 :date (java.util.Date. 0)
                 :uuid (java.util.UUID/fromString "0d12a84f-c80e-4046-b4ab-dfc5795a05e1")}]
        (nrepl-transport/send a msg)
        (let [reply (nrepl-transport/recv b 1000)]
          (is (= msg reply))
          (is (= java.util.Date (class (:date reply))))
          (is (= java.lang.Character (class (:char reply)))))))))

(deftest timeout-throws-instead-of-masquerading-as-eof
  (with-transport-pair
    (fn [_a b]
      (is (thrown? java.net.SocketTimeoutException
                   (nrepl-transport/recv b 50))))))

(deftest negative-frame-length-is-rejected
  (let [[writer-socket reader-socket] (socket-pair)
        out (DataOutputStream.
             (BufferedOutputStream. (.getOutputStream writer-socket)))
        reader (transport/transit false reader-socket)]
    (try
      (.writeInt out -1)
      (.flush out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Negative Transit frame length"
                            (nrepl-transport/recv reader 1000)))
      (finally
        (.close writer-socket)
        (.close ^java.io.Closeable reader)))))
