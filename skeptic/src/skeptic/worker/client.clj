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
  "A worker reply carrying `:exception-class` is a failure; re-throw on the host
   with the full Throwable->map serialization (class + message + per-link data +
   trace) inside `:exception-via`. The discriminator is the producer-owned
   `:exception-class` field, NOT `:status` membership: nREPL middleware reshapes
   `:status` (bare keyword vs set, sometimes stripped), but `:exception-class`
   is written ONLY by the worker's `error-reply`."
  [msg reply]
  (when (:exception-class reply)
    (throw (ex-info (str "worker " (:op msg) " threw "
                         (:exception-class reply) ": "
                         (:exception-message reply)
                         "\n--- worker exception ---\n"
                         (:exception-via reply))
                    {:op (:op msg)
                     :exception-class (:exception-class reply)
                     :exception-message (:exception-message reply)
                     :exception-via (:exception-via reply)}))))

(defn ask
  "Send `msg` to the worker and return the merged reply map. nREPL may emit
   multiple messages per request — for example, a session-middleware `:status
   :done` reply BEFORE the user-handler's domain reply lands on the host's
   queue. The earlier `(if (contains? (:status reply) :done) (reduced ...))`
   broke on the first `:done`, which is a race: if nREPL's terminal `:done` got
   ahead of the domain reply, the merge skipped the domain reply entirely and
   the host saw a map carrying only `:status`/`:id`/`:session` — no error
   fields and no success fields. A nil `:ns-ast`/`:result` then surfaced as a
   silent test failure with no underlying exception text. Consume every reply
   `nrepl.core/message` produces for this request id and merge them all; the
   lazy seq terminates when nREPL closes the request, so consumption is finite.

   A reply carrying `:exception-class` (worker handler threw) re-throws on the
   host with that data. Exceptions are NEVER silently dropped into a field that
   callers may forget to inspect."
  [conn msg]
  (if (:skeptic.worker/loopback? conn)
    ((:handler conn) msg)
    (let [reply (reduce merge {} (nrepl.core/message (:client conn) msg))]
      (check-no-error! msg reply)
      reply)))

(defn disconnect!
  [conn]
  (when-not (:skeptic.worker/loopback? conn)
    (.close ^java.io.Closeable (:transport conn))))
