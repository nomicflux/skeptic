(ns skeptic.test-support.worker-opts
  "Phase 6 test support: `check-project` now spawns a worker per invocation
   and reads its classpath from `(:worker-classpath opts)`. Production
   entrypoints (lein-skeptic, cli/main) compute this from the project
   classpath; tests funnel through `with-worker-cp` which fills the slot
   from the host test JVM's own classpath. `:worker-classpath` is a vector
   of strings per the opts schema; the join into a single classpath string
   happens at the spawn site in `core.clj`."
  (:require [clojure.string :as str]))

(def ^:private host-cp-entries
  (delay (vec (str/split (System/getProperty "java.class.path")
                         (re-pattern java.io.File/pathSeparator)))))

(defn with-worker-cp
  "Returns `opts` augmented with `:worker-classpath` set to the host JVM's
   classpath (vector of strings). No-op if `:worker-classpath` is already set."
  [opts]
  (if (contains? opts :worker-classpath)
    opts
    (assoc opts :worker-classpath @host-cp-entries)))
