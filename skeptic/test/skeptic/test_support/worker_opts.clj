(ns skeptic.test-support.worker-opts
  "Test support: `check-project` spawns a worker per invocation and reads its
   launch classpath from `(:combined (:worker-classpath opts))`. Production
   entrypoints (lein-skeptic, cli/main) compute this through
   `skeptic.worker.classpath/worker-classpath-entries`; tests funnel through
   `with-worker-cp` which fills the slot from the host test JVM's own
   classpath.")

(def ^:private host-cp-string
  (delay (System/getProperty "java.class.path")))

(defn with-worker-cp
  "Returns `opts` augmented with `:worker-classpath` set to the host JVM's
   classpath as the launch cp. No-op if `:worker-classpath` is already set."
  [opts]
  (if (contains? opts :worker-classpath)
    opts
    (assoc opts :worker-classpath {:combined @host-cp-string})))
