(ns skeptic.worker.classpath
  "Shared worker classpath assembly. Entrypoints own project classpath discovery;
   this namespace owns the Skeptic worker runtime entries required to launch
   skeptic.worker.server on top of that project classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net JarURLConnection]))

(def ^:private worker-runtime-resources
  ["clojure/main.clj"
   "clojure/spec/alpha.clj"
   "clojure/core/specs/alpha.clj"
   "skeptic/worker/server.clj"
   "skeptic/worker/analyzer_clj.clj"
   "skeptic/worker/analyzer_cljs.clj"
   "skeptic/worker/transport.clj"
   "skeptic/worker/wire.clj"
   "nrepl/server.clj"
   "nrepl/transport.clj"
   "taoensso/nippy.clj"
   "taoensso/encore.cljc"
   "taoensso/truss.cljc"
   "io/airlift/compress/Compressor.class"
   "org/tukaani/xz/XZOutputStream.class"
   "clojure/tools/analyzer.clj"
   "clojure/tools/analyzer/ast.clj"
   "clojure/tools/analyzer/env.clj"
   "clojure/tools/analyzer/jvm.clj"
   "clojure/core/memoize.clj"
   "clojure/core/cache.clj"
   "clojure/data/priority_map.clj"
   "org/objectweb/asm/Type.class"
   "clojure/tools/reader.clj"
   "clojure/tools/reader/reader_types.clj"
   "cljs/analyzer.cljc"
   "cljs/analyzer/api.cljc"
   "cljs/compiler.cljc"
   "cljs/env.cljc"
   "com/google/javascript/jscomp/Compiler.class"])

(defn- parent-n
  [^java.io.File f n]
  (if (zero? n)
    f
    (recur (.getParentFile f) (dec n))))

(defn- file-resource-classpath-entry
  [resource url]
  (let [resource-file (io/file (.toURI url))
        segment-count (count (str/split resource #"/"))]
    (.getPath (parent-n resource-file segment-count))))

(defn- jar-resource-classpath-entry
  [url]
  (let [conn (.openConnection url)]
    (when-not (instance? JarURLConnection conn)
      (throw (ex-info (str "Unsupported jar resource connection for " url)
                      {:url (str url)})))
    (.getPath (io/file (.toURI (.getJarFileURL ^JarURLConnection conn))))))

(defn- resource-classpath-entry
  [resource]
  (let [url (or (io/resource resource)
                (throw (ex-info (str "Could not locate worker runtime resource " resource)
                                {:resource resource})))]
    (case (.getProtocol url)
      "file" (file-resource-classpath-entry resource url)
      "jar"  (jar-resource-classpath-entry url)
      (throw (ex-info (str "Unsupported worker runtime resource protocol "
                           (.getProtocol url))
                      {:resource resource
                       :url (str url)})))))

(defn- runtime-classpath-entries
  []
  (mapv resource-classpath-entry worker-runtime-resources))

(defn worker-classpath-entries
  "Return the classpath used to launch the worker JVM.

   `project-classpath-entries` is the already-resolved classpath for the project
   being analyzed. The project classpath comes FIRST, so the project's own
   Clojure and shared libraries resolve at their own versions; the Skeptic worker
   runtime is appended and (via `distinct`) contributes only the jars the project
   lacks but the worker needs to boot skeptic.worker.server and answer RPCs. The
   worker must analyze on the project's runtime, never on Skeptic's."
  [project-classpath-entries]
  (vec (distinct (concat (map str (or project-classpath-entries []))
                         (runtime-classpath-entries)))))
