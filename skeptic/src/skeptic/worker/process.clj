(ns skeptic.worker.process
  "Host-side worker process lifecycle: spawn a JVM running skeptic.worker.server,
   read the port handshake off its stdout, and tear it down. The worker carries
   the PROJECT classpath (analysis input) unioned with Skeptic's own runtime
   entries sourced from the invoking JVM, so skeptic.worker.* and nREPL are on the
   worker -cp without the user adding any dependency."
  (:require [schema.core :as s]
            [clojure.string :as str])
  (:import [java.io BufferedReader]
           [java.net URL URLClassLoader]))

(s/defn ^:private loader-entries :- [s/Str]
  [loader :- (s/maybe s/Any)]
  (loop [l loader, acc []]
    (if-not l
      acc
      (let [urls (when (instance? URLClassLoader l) (.getURLs ^URLClassLoader l))
            paths (map #(.getPath ^URL %) urls)]
        (recur (.getParent ^ClassLoader l) (into acc paths))))))

(s/defn self-classpath-entries :- [s/Str]
  "Skeptic's own classpath entries from the invoking JVM: the URLClassLoader chain
   (lein plugin JVM) unioned with java.class.path (deps -T tool JVM)."
  []
  (let [from-loader (loader-entries (.getContextClassLoader (Thread/currentThread)))
        from-sysprop (str/split (System/getProperty "java.class.path")
                                (re-pattern java.io.File/pathSeparator))]
    (vec (distinct (concat from-loader from-sysprop)))))

(s/defn worker-classpath :- s/Str
  [project-cp :- s/Str]
  (->> (concat (str/split project-cp (re-pattern java.io.File/pathSeparator))
               (self-classpath-entries))
       distinct
       (str/join java.io.File/pathSeparator)))

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
  [cp :- s/Str]
  (let [pb (doto (ProcessBuilder. ["java" "-cp" cp "clojure.main" "-m" "skeptic.worker.server"])
             (.redirectErrorStream true))
        proc (.start pb)
        reader (BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
    {:proc proc :port (read-port reader)}))

(s/defn stop! :- s/Any
  [{:keys [proc]} :- {:proc s/Any s/Any s/Any}]
  (.destroy ^Process proc))
