(ns skeptic.worker.transport
  "Length-prefixed Transit+msgpack transport for Skeptic's private nREPL
   worker link. The Transport protocol is implemented in the peer namespace
   `skeptic.worker.transport-impl`, which is required lazily on first
   `transit` call so neither nrepl.transport nor the deftype's Protocol
   symbol resolves at this namespace's load time."
  (:require [cognitect.transit :as transit])
  (:import [java.io BufferedInputStream BufferedOutputStream DataInputStream
            DataOutputStream EOFException ByteArrayInputStream ByteArrayOutputStream]
           [java.net Socket SocketTimeoutException]))

(defn read-message
  [^DataInputStream in]
  (try
    (let [n (.readInt in)]
      (when (neg? n)
        (throw (ex-info "Negative Transit frame length" {:length n})))
      (let [bytes (byte-array n)]
        (.readFully in bytes)
        (let [bais (ByteArrayInputStream. bytes)]
          (transit/read (transit/reader bais :msgpack)))))
    (catch EOFException _ nil)))

(defn read-message-with-timeout
  [^Socket socket ^DataInputStream in timeout]
  (let [old-timeout (.getSoTimeout socket)]
    (try
      (.setSoTimeout socket (int timeout))
      (read-message in)
      (catch SocketTimeoutException _ nil)
      (finally (.setSoTimeout socket old-timeout)))))

(defn write-message
  [^DataOutputStream out msg]
  (let [baos (ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :msgpack) msg)
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
