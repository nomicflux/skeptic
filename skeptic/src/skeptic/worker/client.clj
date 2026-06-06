(ns skeptic.worker.client
  "Host-side nREPL client: connect to a worker port, send ops, return replies.
   Uses the Transport protocol directly (`send` + `recv`) for synchronous
   request-response semantics. Does NOT use nrepl.core/client or
   nrepl.core/message — those layer lazy seqs and timeouts designed for
   interactive REPLs, not RPC.

   nrepl.* namespaces are NOT required at namespace-load time. The worker
   loads this namespace via server.clj's :require to get `loopback-conn` and
   the loopback branch of `ask` — neither of which touches nrepl. Host-facing
   functions below defer their nrepl loads via `requiring-resolve`, so the
   project's pinned nrepl wins via the launch classpath's project-first
   ordering."
  (:require [skeptic.worker.transport :as worker-transport]))

(defn connect
  [port]
  (let [nrepl-connect (requiring-resolve 'nrepl.core/connect)
        transport (nrepl-connect :port port :transport-fn worker-transport/transit)]
    {:transport transport}))

(defn loopback-conn
  [handler]
  {:skeptic.worker/loopback? true
   :handler handler})

(defn- done-status?
  [status]
  (or (contains? status :done)
      (contains? status "done")))

(defn- recv-reply
  "Block on `recv` until we receive a reply with :status containing :done
   for our request :id. Merge all intermediate replies. If recv returns nil
   the transport is dead — throw naming the op."
  [t-recv transport op id]
  (loop [merged {}]
    (let [msg (t-recv transport)]
      (when-not msg
        (throw (ex-info (str "Worker transport closed during op \"" op
                             "\" (request id " id "): no reply received. "
                             "The worker JVM may have crashed.")
                        {:op op :id id :partial-reply merged})))
      (if (= id (:id msg))
        (let [merged' (merge merged msg)]
          (if (done-status? (:status msg))
            merged'
            (recur merged')))
        (recur merged)))))

(defn- check-no-error!
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
  [conn msg]
  (if (:skeptic.worker/loopback? conn)
    ((:handler conn) msg)
    (let [uuid (requiring-resolve 'nrepl.misc/uuid)
          t-send (requiring-resolve 'nrepl.transport/send)
          t-recv (requiring-resolve 'nrepl.transport/recv)
          id (or (:id msg) (uuid))
          msg (assoc msg :id id)
          transport (:transport conn)]
      (t-send transport msg)
      (let [reply (recv-reply t-recv transport (:op msg) id)]
        (check-no-error! msg reply)
        reply))))

(defn disconnect!
  [conn]
  (when-not (:skeptic.worker/loopback? conn)
    (.close ^java.io.Closeable (:transport conn))))
