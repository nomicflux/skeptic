(ns skeptic.worker.transport
  "Length-prefixed Nippy transport for Skeptic's private nREPL worker link."
  (:require [nrepl.transport :as transport]
            [taoensso.nippy :as nippy])
  (:import [java.io BufferedInputStream BufferedOutputStream DataInputStream
            DataOutputStream EOFException]
           [java.net Socket SocketTimeoutException]))

(defn- read-message
  [^DataInputStream in]
  (try
    (let [n (.readInt in)]
      (when (neg? n)
        (throw (ex-info "Negative Nippy frame length" {:length n})))
      (let [bytes (byte-array n)]
        (.readFully in bytes)
        (nippy/thaw bytes)))
    (catch EOFException _
      nil)))

(defn- read-message-with-timeout
  [^Socket socket ^DataInputStream in timeout]
  (let [old-timeout (.getSoTimeout socket)]
    (try
      (.setSoTimeout socket (int timeout))
      (read-message in)
      (catch SocketTimeoutException _
        nil)
      (finally
        (.setSoTimeout socket old-timeout)))))

(defn- write-message
  [^DataOutputStream out msg]
  (let [bytes (nippy/freeze msg)]
    (locking out
      (.writeInt out (alength bytes))
      (.write out bytes)
      (.flush out)))
  msg)

(deftype NippyTransport [^Socket socket
                         ^DataInputStream in
                         ^DataOutputStream out]
  transport/Transport
  (recv [_]
    (locking in
      (read-message in)))
  (recv [_ timeout]
    (locking in
      (read-message-with-timeout socket in timeout)))
  (send [_ msg]
    (write-message out msg))

  java.io.Closeable
  (close [_]
    (.close socket)))

(defn nippy
  "nREPL transport-fn using Nippy payloads framed by a 4-byte length prefix."
  [^Socket socket]
  (->NippyTransport socket
                    (DataInputStream.
                     (BufferedInputStream. (.getInputStream socket)))
                    (DataOutputStream.
                     (BufferedOutputStream. (.getOutputStream socket)))))
