(ns skeptic.worker.server
  "Worker-side EDN nREPL server. Runs in the spawned JVM on the project's
   classpath and answers host requests on demand. Phase 1 commits a single
   `ping` op proving transport + lifecycle; the class-relation oracle is Plan 2."
  (:require [schema.core :as s]
            [nrepl.server :as srv]
            [nrepl.transport :as t]
            [nrepl.middleware :as mw]
            [nrepl.misc :refer [response-for]]))

(s/defn wrap-ping :- (s/=> s/Any s/Any)
  [h :- (s/=> s/Any s/Any)]
  (fn [{:keys [op transport] :as msg}]
    (if (= op "ping")
      (t/send transport (response-for msg :pong "ok" :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-ping {:requires #{} :expects #{} :handles {"ping" {}}})

(s/defn start! :- s/Any
  []
  (srv/start-server :port 0
                    :transport-fn t/edn
                    :handler (srv/default-handler #'wrap-ping)))

(s/defn -main :- s/Any
  [& _args]
  (let [server (start!)]
    (println (str "SKEPTIC-WORKER-PORT " (:port server)))
    (flush)
    @(promise)))
