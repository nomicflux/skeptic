(ns skeptic.worker.process
  "Host-side worker process lifecycle: spawn a JVM running skeptic.worker.server,
   read the port handshake off its stdout, and tear it down. The caller passes
   the already assembled Skeptic runtime classpath plus the project classpath
   that the server installs as a separate context loader."
  (:require [schema.core :as s]
            [clojure.string :as str])
  (:import [java.io BufferedReader]))

(s/defn ^:private read-port :- s/Int
  [reader :- BufferedReader]
  (loop [lines []]
    (let [line (.readLine reader)]
      (cond
        (nil? line) (throw (ex-info "worker exited before port handshake"
                                     {:worker-output lines}))
        (str/starts-with? line "SKEPTIC-WORKER-PORT ")
        (Integer/parseInt (subs line (count "SKEPTIC-WORKER-PORT ")))
        :else (recur (conj lines line))))))

(s/defn spawn! :- {:proc s/Any :port s/Int}
  ([runtime-cp :- s/Str]
   (spawn! runtime-cp ""))
  ([runtime-cp :- s/Str project-cp :- s/Str]
   (let [pb (doto (ProcessBuilder. ["java" "-cp" runtime-cp
                                    "clojure.main" "-m" "skeptic.worker.server"
                                    project-cp])
              (.redirectErrorStream true))
         proc (.start pb)
         reader (BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
     {:proc proc :port (read-port reader)})))

(s/defn stop! :- s/Any
  [{:keys [proc]} :- {:proc s/Any s/Any s/Any}]
  (.destroy ^Process proc))
