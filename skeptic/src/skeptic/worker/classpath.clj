(ns skeptic.worker.classpath
  "Worker launch classpath assembly. Skeptic owns its worker runtime as a
   coordinate declaration in `skeptic.worker.deps/worker-deps`; the lein and
   deps.edn callers each resolve those coordinates through their own build
   system and hand the resolved jar list (`worker-jars`) plus the project
   classpath (`project-classpath-entries`) to `worker-classpath-entries`.
   The result is a single launch classpath string: project-cp first, worker
   jars second, Skeptic's own `skeptic.worker.*` source entry tail (so the
   worker JVM can require its own server namespace at boot)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net JarURLConnection]))

(def ^:private path-separator
  (System/getProperty "path.separator"))

(defn- skeptic-worker-self-entry
  "Resolve the classpath entry containing Skeptic's own `skeptic.worker.*`
   source. The host JVM already has this on its classpath (it IS Skeptic);
   we find it via `io/resource` on a known worker namespace file and recover
   the containing jar or directory."
  []
  (let [url (or (io/resource "skeptic/worker/server.clj")
                (throw (ex-info "Could not locate skeptic/worker/server.clj on host classpath"
                                {})))]
    (case (.getProtocol url)
      "file" (let [f (io/file (.toURI url))
                   segments (count (str/split "skeptic/worker/server.clj" #"/"))]
               (loop [d f n segments]
                 (if (zero? n) (.getPath d) (recur (.getParentFile d) (dec n)))))
      "jar"  (let [conn (.openConnection url)]
               (when-not (instance? JarURLConnection conn)
                 (throw (ex-info (str "Unsupported jar resource connection for " url) {})))
               (.getPath (io/file (.toURI (.getJarFileURL ^JarURLConnection conn)))))
      (throw (ex-info (str "Unsupported skeptic worker self-entry protocol "
                           (.getProtocol url))
                      {:url (str url)})))))

(defn worker-classpath-entries
  "Assemble the worker JVM launch classpath.

   `worker-jars` is the resolved jar list for Skeptic's `:worker`
   declaration (`skeptic.worker.deps/worker-deps`) — supplied by the caller
   after asking its own build system to resolve the declaration.

   `project-classpath-entries` is the already-resolved classpath for the
   project being analyzed.

   Returns `{:combined <string>}`. `:combined` is
   `(distinct (concat project-cp worker-jars [skeptic-self-entry]))` joined
   by the path-separator. Project-first ordering means the project's pinned
   versions win on every shared lib via `distinct`'s first-occurrence
   semantics."
  [worker-jars project-classpath-entries]
  (let [worker (vec (map str (or worker-jars [])))
        project (vec (distinct (map str (or project-classpath-entries []))))
        skeptic-self (skeptic-worker-self-entry)
        combined-entries (vec (distinct (concat project worker [skeptic-self])))]
    {:combined (str/join path-separator combined-entries)}))
