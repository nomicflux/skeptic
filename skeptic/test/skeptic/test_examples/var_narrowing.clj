(ns skeptic.test-examples.var-narrowing
  (:require [schema.core :as s]))

(s/defschema Server {:host s/Str :port s/Int})

(s/def server :- (s/maybe Server) {:host "localhost" :port 8080})

(s/defn use-host :- s/Str
  [h :- s/Str]
  h)

(s/defn server-host-when-present-success :- (s/maybe s/Str)
  []
  (when (some? server)
    (use-host (:host server))))
