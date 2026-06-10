(ns skeptic.worker.transport
  "Length-prefixed Transit+msgpack transport for Skeptic's private nREPL
   worker link. The Transport protocol is implemented in the peer namespace
   `skeptic.worker.transport-impl`, which is required lazily on first
   `transit` call so neither nrepl.transport nor the deftype's Protocol
   symbol resolves at this namespace's load time."
  (:require [cognitect.transit :as transit]
            [skeptic.worker.wire :as wire])
  (:import [java.io BufferedInputStream BufferedOutputStream DataInputStream
            DataOutputStream EOFException ByteArrayInputStream ByteArrayOutputStream]
           [java.net Socket]))

(defn- guarded-print-str
  [o]
  (try
    (pr-str o)
    (catch Throwable _
      (str "<unprintable " (.getName (class o)) ">"))))

(def ^:private opaque-tag "skeptic-opaque")

(def ^:private opaque-write-handler
  "Backstop for any value with no transit handler: instead of the marshaller
   throwing `Not supported: class X` and killing the op, the value crosses as
   its class name plus a guarded print string, and one stderr line names the
   class so a projection gap stays visible without failing the run."
  (transit/write-handler
   (fn [_] opaque-tag)
   (fn [o]
     (let [class-name (.getName (class o))]
       (binding [*out* *err*]
         (println (str "skeptic transit: opaque value crossed wire: " class-name))
         (flush))
       {:class class-name :string (guarded-print-str o)}))))

(def ^:private opaque-read-handler
  (transit/read-handler
   (fn [{:keys [class string]}]
     (wire/opaque-sentinel class string))))

(defn read-message
  [^DataInputStream in]
  (try
    (let [n (.readInt in)]
      (when (neg? n)
        (throw (ex-info "Negative Transit frame length" {:length n})))
      (let [bytes (byte-array n)]
        (.readFully in bytes)
        (let [bais (ByteArrayInputStream. bytes)]
          (transit/read (transit/reader bais :msgpack
                                        {:handlers {opaque-tag opaque-read-handler}})))))
    (catch EOFException _ nil)))

(defn read-message-with-timeout
  "nREPL's recv-with-timeout contract returns nil on timeout, but nil is
   also the EOF signal that ends nREPL's handle loop and closes the
   connection — a slow peer must stay distinguishable from a closed one,
   so the SocketTimeoutException propagates instead."
  [^Socket socket ^DataInputStream in timeout]
  (let [old-timeout (.getSoTimeout socket)]
    (try
      (.setSoTimeout socket (int timeout))
      (read-message in)
      (finally (.setSoTimeout socket old-timeout)))))

(defn write-message
  [^DataOutputStream out msg]
  (let [baos (ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :msgpack
                                   {:default-handler opaque-write-handler})
                   msg)
    (let [bytes (.toByteArray baos)]
      (locking out
        (.writeInt out (alength bytes))
        (.write out bytes)
        (.flush out))))
  msg)

(defonce ^:private transport-impl-loaded
  (delay
    (require 'skeptic.worker.transport-impl)
    (requiring-resolve 'skeptic.worker.transport-impl/->TransitTransport)))

(defn transit
  "nREPL transport-fn using Transit+msgpack payloads framed by a 4-byte
   length prefix. The Transport protocol implementation lives in
   `skeptic.worker.transport-impl`, lazy-required on first call so this
   namespace can be loaded before nrepl.transport is on the classpath."
  [^Socket socket]
  (let [ctor @transport-impl-loaded]
    (ctor socket
          (DataInputStream. (BufferedInputStream. (.getInputStream socket)))
          (DataOutputStream. (BufferedOutputStream. (.getOutputStream socket))))))
