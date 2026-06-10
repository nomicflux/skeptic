(ns skeptic.worker.transport-impl
  "Holds the deftype that implements `nrepl.transport/Transport` over the
   Transit+msgpack framing helpers in `skeptic.worker.transport`. This
   namespace is required lazily by `skeptic.worker.transport/transit` so
   nrepl.transport's Transport protocol resolves at the project's pinned
   version, not Skeptic's runtime-cp version at worker boot."
  (:require [nrepl.transport :as nrepl-transport]
            [skeptic.worker.transport :as worker-transport])
  (:import [java.io DataInputStream DataOutputStream]
           [java.net Socket]))

(deftype TransitTransport [^Socket socket
                           ^DataInputStream in
                           ^DataOutputStream out]
  nrepl-transport/Transport
  (recv [_]
    (locking in (worker-transport/read-message in)))
  (recv [_ timeout]
    (locking in (worker-transport/read-message-with-timeout socket in timeout)))
  (send [_ msg]
    (worker-transport/write-message out msg))

  java.io.Closeable
  (close [_]
    (when-not (.isClosed socket)
      (binding [*out* *err*]
        (println (str "skeptic TransitTransport closed: " socket))
        (flush)))
    (.close socket)))
