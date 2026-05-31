(ns skeptic.worker.client
  "Host-side nREPL client over the Nippy transport: connect to a worker port and
   send it ops, returning each reply map. The nREPL client is built once at
   connect time and reused by every ask. Pairs with skeptic.worker.server."
  (:require [schema.core :as s]
            [nrepl.core :as nrepl]
            [skeptic.worker.transport :as worker-transport]))

(s/defn connect :- s/Any
  [port :- s/Int]
  (let [t (nrepl.core/connect :port port :transport-fn worker-transport/nippy)]
    {:transport t :client (nrepl.core/client t 5000)}))

(s/defn ask :- s/Any
  [conn :- s/Any
   msg :- {s/Any s/Any}]
  (first (nrepl.core/message (:client conn) msg)))

(s/defn disconnect! :- s/Any
  [conn :- s/Any]
  (.close ^java.io.Closeable (:transport conn)))
