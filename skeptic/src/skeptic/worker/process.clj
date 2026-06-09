(ns skeptic.worker.process
  "Host-side worker process lifecycle: spawn a JVM running skeptic.worker.server,
   read the port handshake off its stdout, and tear it down. The caller passes
   a single launch classpath assembled by `skeptic.worker.classpath` —
   project-cp first, worker jars second, Skeptic's own worker source tail."
  (:require [schema.core :as s]
            [clojure.string :as str])
  (:import [java.io BufferedReader]))

(s/defn ^:private read-port :- s/Int
  [reader :- BufferedReader verbose? :- s/Bool]
  (loop [lines []]
    (let [line (.readLine reader)]
      (cond
        (nil? line) (throw (ex-info "worker exited before port handshake"
                                     {:worker-output lines}))
        (str/starts-with? line "SKEPTIC-WORKER-PORT ")
        (Integer/parseInt (subs line (count "SKEPTIC-WORKER-PORT ")))
        :else (do
                (when verbose?
                  (binding [*out* *err*]
                    (println (str "[skeptic worker stdout] " line))
                    (flush)))
                (recur (conj lines line)))))))

(defn- start-stdout-drain!
  "After the handshake, start a daemon thread that reads every remaining line
   from the worker's stdout (stderr is merged via redirectErrorStream) and
   echoes it to host stderr under `verbose?`. Without this, any worker
   `println` after the handshake — progress markers, deprecation warnings,
   library log lines — is buffered in the pipe and invisible. The host's
   `read-port` only consumed pre-handshake lines, so the post-handshake
   pipe is otherwise unread; a full pipe buffer would eventually block the
   worker's println call."
  [^BufferedReader reader verbose?]
  (let [t (Thread.
            ^Runnable
            (fn []
              (try
                (loop []
                  (when-let [line (.readLine reader)]
                    (when verbose?
                      (binding [*out* *err*]
                        (println (str "[skeptic worker stdout] " line))
                        (flush)))
                    (recur)))
                (catch Throwable _))))]
    (.setDaemon t true)
    (.setName t "skeptic-worker-stdout-drain")
    (.start t)
    t))

(s/defn spawn! :- {:proc s/Any :port s/Int}
  [combined-cp :- s/Str verbose? :- s/Bool]
  (let [pb (doto (ProcessBuilder. ["java" "-cp" combined-cp
                                   "clojure.main" "-m" "skeptic.worker.server"])
             (.redirectErrorStream true))
        _ (when verbose?
            (binding [*out* *err*]
              (println "[skeptic startup] worker JVM ProcessBuilder.start (waiting for handshake)")
              (flush)))
        proc (.start pb)
        reader (BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))
        port (read-port reader verbose?)]
    (start-stdout-drain! reader verbose?)
    {:proc proc :port port}))

(s/defn stop! :- s/Any
  [{:keys [proc]} :- {:proc s/Any s/Any s/Any}]
  (.destroy ^Process proc))
