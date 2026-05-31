(ns skeptic.worker.client
  "Host-side nREPL client over the EDN transport: connect to a worker port and
   send it ops, returning each reply map. The nREPL client is built once at
   connect time and reused by every ask. Pairs with skeptic.worker.server."
  (:require [schema.core :as s]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(s/defn connect :- s/Any
  [port :- s/Int]
  (let [t (nrepl.core/connect :port port :transport-fn transport/edn)]
    {:transport t :client (nrepl.core/client t 5000)}))

(s/defn ask :- s/Any
  [conn :- s/Any
   msg :- {s/Any s/Any}]
  (first (nrepl.core/message (:client conn) msg)))
