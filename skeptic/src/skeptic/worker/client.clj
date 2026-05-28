(ns skeptic.worker.client
  "Host-side nREPL client over the EDN transport: connect to a worker port and
   send it a single op, returning the reply map. Pairs with skeptic.worker.server."
  (:require [schema.core :as s]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(s/defn connect :- s/Any
  [port :- s/Int]
  (nrepl.core/connect :port port :transport-fn transport/edn))

(s/defn ask :- s/Any
  [conn :- s/Any
   msg :- {s/Any s/Any}]
  (first (nrepl.core/message (nrepl.core/client conn 5000) msg)))
