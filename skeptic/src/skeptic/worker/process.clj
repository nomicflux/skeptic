(ns skeptic.worker.process
  "Host-side worker process lifecycle: spawn a JVM running skeptic.worker.server
   on the project classpath, read the port handshake off its stdout, and tear it
   down. The worker carries the PROJECT classpath plus the nREPL jar the harness
   needs; Clojure and spec.alpha already ride along on any real project classpath."
  (:require [schema.core :as s]
            [clojure.string :as str])
  (:import [java.io BufferedReader]))

(def ^:private nrepl-jar-marker "nrepl-1.3.1.jar")

(s/defn ^:private host-nrepl-entry :- (s/maybe s/Str)
  []
  (->> (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
       (filter #(str/includes? % nrepl-jar-marker))
       first))

(s/defn worker-classpath :- s/Str
  [project-cp :- s/Str]
  (if (str/includes? project-cp nrepl-jar-marker)
    project-cp
    (str project-cp java.io.File/pathSeparator (host-nrepl-entry))))

(s/defn ^:private read-port :- s/Int
  [reader :- BufferedReader]
  (loop []
    (let [line (.readLine reader)]
      (cond
        (nil? line) (throw (ex-info "worker exited before port handshake" {}))
        (str/starts-with? line "SKEPTIC-WORKER-PORT ")
        (Integer/parseInt (subs line (count "SKEPTIC-WORKER-PORT ")))
        :else (recur)))))

(s/defn spawn! :- {:proc s/Any :port s/Int}
  [cp :- s/Str]
  (let [pb (doto (ProcessBuilder. ["java" "-cp" cp "clojure.main" "-m" "skeptic.worker.server"])
             (.redirectErrorStream true))
        proc (.start pb)
        reader (BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
    {:proc proc :port (read-port reader)}))

(s/defn stop! :- s/Any
  [{:keys [proc]} :- {:proc s/Any s/Any s/Any}]
  (.destroy ^Process proc))
