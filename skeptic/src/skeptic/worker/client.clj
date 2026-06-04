(ns skeptic.worker.client
  "Host-side nREPL client over the Nippy transport: connect to a worker port and
   send it ops, returning each reply map. The nREPL client is built once at
   connect time and reused by every ask. Pairs with skeptic.worker.server."
  (:require [nrepl.core :as nrepl]
            [skeptic.worker.transport :as worker-transport]))

(defn connect
  [port]
  (let [t (nrepl.core/connect :port port :transport-fn worker-transport/nippy)]
    {:transport t :client (nrepl.core/client t 5000)}))

(defn loopback-conn
  [handler]
  {:skeptic.worker/loopback? true
   :handler handler})

(defn ask
  "Send `msg` to the worker and return the merged reply map. nREPL may emit
   multiple messages per request (an interim `:out`/`:err` flush, then the
   domain reply carrying `:status #{:done}`); taking `(first ...)` was a race
   on which message landed first. Merging every reply through `:done` gives a
   strict construction: the result deterministically contains every field any
   reply for this request carried."
  [conn msg]
  (if (:skeptic.worker/loopback? conn)
    ((:handler conn) msg)
    (reduce (fn [acc reply]
              (let [acc' (merge acc reply)]
                (if (contains? (:status reply) :done)
                  (reduced acc')
                  acc')))
            {}
            (nrepl.core/message (:client conn) msg))))

(defn disconnect!
  [conn]
  (when-not (:skeptic.worker/loopback? conn)
    (.close ^java.io.Closeable (:transport conn))))
