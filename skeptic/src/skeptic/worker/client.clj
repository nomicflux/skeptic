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

(defn- check-no-error!
  "A worker reply carrying `:exception-class` carries the worker exception's
   class, full cause-chain messages, and a printable rendering of the deepest
   `ex-data`; re-throw on the host so the failure is loud, not a silent nil in
   some downstream field the caller forgot to inspect.

   The discriminator is `:exception-class`, NOT `:status` membership: nREPL's
   middleware may ship `:status` as a bare keyword, a set, or split across
   messages, and `contains?` on a bare keyword throws — none of which is robust.
   A `:exception-class` field on the wire is produced ONLY by the worker's
   `error-reply`, so its presence is an unambiguous error signal."
  [msg reply]
  (when (:exception-class reply)
    (throw (ex-info (str "worker " (:op msg) " threw "
                         (:exception-class reply) ": "
                         (:exception-message reply)
                         (when-let [d (:exception-data reply)]
                           (str " data=" d)))
                    {:op (:op msg)
                     :exception-class (:exception-class reply)
                     :exception-message (:exception-message reply)
                     :exception-data (:exception-data reply)}))))

(defn ask
  "Send `msg` to the worker and return the merged reply map. nREPL may emit
   multiple messages per request (an interim `:out`/`:err` flush, then the
   domain reply carrying `:status #{:done}`); taking `(first ...)` was a race
   on which message landed first. Merging every reply through `:done` gives a
   strict construction: the result deterministically contains every field any
   reply for this request carried.

   A reply carrying `:status :error` (worker handler threw) re-throws on the
   host with the exception class and message. Exceptions are NEVER silently
   dropped into a field that callers may forget to inspect."
  [conn msg]
  (if (:skeptic.worker/loopback? conn)
    ((:handler conn) msg)
    (let [reply (reduce (fn [acc reply]
                          (let [acc' (merge acc reply)]
                            (if (contains? (:status reply) :done)
                              (reduced acc')
                              acc')))
                        {}
                        (nrepl.core/message (:client conn) msg))]
      (check-no-error! msg reply)
      reply)))

(defn disconnect!
  [conn]
  (when-not (:skeptic.worker/loopback? conn)
    (.close ^java.io.Closeable (:transport conn))))
