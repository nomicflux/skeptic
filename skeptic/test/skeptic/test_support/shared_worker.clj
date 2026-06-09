(ns skeptic.test-support.shared-worker
  "Suite-wide singleton worker for tests that run the real analysis path or build
   a ground :class handle. Test runtime = production runtime: a real worker JVM,
   no host-local escape hatch.

   One worker is spawned lazily on first use and shared across every test
   namespace, then torn down via a JVM shutdown hook. A single worker is required
   because project-class identity is an opaque per-worker UUID handle: cached
   project-state built under one worker cannot have its handles resolved by a
   different worker. The singleton keeps every cached handle and every live
   class-rel RPC pinned to the same JVM."
  (:require [skeptic.worker.process :as proc]
            [skeptic.worker.client :as wc]
            [skeptic.analysis.class-oracle :as oracle]))

(defn- spawn-shared!
  "Spawns the singleton worker, connects, interns bootstrap host classes, and
   registers a shutdown hook to stop it. Returns `{:conn :handles}`."
  []
  (let [cp     (System/getProperty "java.class.path")
        worker (proc/spawn! cp false)
        conn   (wc/connect (:port worker))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (wc/disconnect! conn) (proc/stop! worker))))
    {:conn conn :handles (oracle/intern-host-classes! conn)}))

(def ^:private shared (delay (spawn-shared!)))

(defn with-shared-worker
  "clojure.test :once fixture. Binds `oracle/*worker-conn*` and
   `oracle/*host-class-handles*` to the suite-wide singleton worker (spawned on
   first use) and runs the namespace's tests."
  [t]
  (let [{:keys [conn handles]} @shared]
    (binding [oracle/*worker-conn* conn
              oracle/*host-class-handles* handles]
      (t))))
