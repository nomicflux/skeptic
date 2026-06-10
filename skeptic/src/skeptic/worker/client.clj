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

(defn- check-expected-id!
  "The connection is a synchronous RPC link: exactly one request is in
   flight, so every reply must carry its id. A reply for any other id is
   protocol corruption — throw with the stray message so the source is
   visible immediately."
  [op id msg context]
  (when-not (= id (:id msg))
    (throw (ex-info (str "Worker reply for foreign request id during op \"" op
                         "\" (request id " id ", reply id " (:id msg) ")")
                    (merge {:op op :id id :stray-message msg} context)))))

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
      (check-expected-id! op id msg {:partial-reply merged})
      (let [merged' (merge merged msg)]
        (if (done-status? (:status msg))
          merged'
          (recur merged'))))))

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

(defn- recv-streaming
  "Block on `recv` until `:done`. Each non-done message for our `:id` is
   passed to `on-reply`. Throws if the transport closes mid-stream or a
   reply arrives for any other id — the stream must end at `:done` or an
   exception, never silently."
  [t-recv transport op id on-reply]
  (loop []
    (let [msg (t-recv transport)]
      (when-not msg
        (throw (ex-info (str "Worker transport closed during streaming op \"" op
                             "\" (request id " id ")")
                        {:op op :id id})))
      (check-expected-id! op id msg nil)
      (check-no-error! {:op op} msg)
      (when-not (done-status? (:status msg))
        (on-reply msg)
        (recur)))))

(defn ask-streaming
  "Send `msg` and call `on-reply` for each intermediate reply. Returns nil
   when the final `:done` message arrives. Loopback: the handler returns a
   single map; `on-reply` is called once with it."
  [conn msg on-reply]
  (if (:skeptic.worker/loopback? conn)
    (let [reply ((:handler conn) msg)]
      (if (sequential? reply)
        (run! on-reply reply)
        (on-reply reply)))
    (let [uuid (requiring-resolve 'nrepl.misc/uuid)
          t-send (requiring-resolve 'nrepl.transport/send)
          t-recv (requiring-resolve 'nrepl.transport/recv)
          id (or (:id msg) (uuid))
          msg (assoc msg :id id)
          transport (:transport conn)]
      (t-send transport msg)
      (recv-streaming t-recv transport (:op msg) id on-reply))))

(defn disconnect!
  [conn]
  (when-not (:skeptic.worker/loopback? conn)
    (.close ^java.io.Closeable (:transport conn))))
