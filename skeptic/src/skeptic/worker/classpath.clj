(ns skeptic.worker.classpath
  "Shared worker classpath assembly. Entrypoints own project classpath discovery;
   this namespace owns the Skeptic worker runtime entries required to launch
   skeptic.worker.server on top of that project classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net JarURLConnection]
           [java.util.jar JarFile]))

(def ^:private worker-runtime-resources
  ["clojure/main.clj"
   "clojure/spec/alpha.clj"
   "clojure/core/specs/alpha.clj"
   "skeptic/worker/server.clj"
   "skeptic/worker/analyzer_clj.clj"
   "skeptic/worker/analyzer_cljs.clj"
   "skeptic/worker/client.clj"
   "skeptic/worker/project_context.clj"
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

(def ^:private project-clojure-runtime-resources
  ["clojure/main.clj"
   "clojure/core.clj"
   "clojure/spec/alpha.clj"
   "clojure/core/specs/alpha.clj"])

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

(defn- directory-entry-has-resource?
  [^java.io.File entry resource]
  (.exists (io/file entry resource)))

(defn- jar-entry-has-resource?
  [^java.io.File entry resource]
  (with-open [jar (JarFile. entry)]
    (some? (.getEntry jar resource))))

(defn- classpath-entry-has-resource?
  [entry resource]
  (let [f (io/file entry)]
    (cond
      (.isDirectory f) (directory-entry-has-resource? f resource)
      (.isFile f)      (jar-entry-has-resource? f resource)
      :else            false)))

(defn- project-resource-classpath-entry
  [project-classpath-entries resource]
  (some #(when (classpath-entry-has-resource? % resource) (str %))
        project-classpath-entries))

(defn- project-clojure-runtime-entries
  [project-classpath-entries]
  (vec
   (distinct
    (keep #(project-resource-classpath-entry project-classpath-entries %)
          project-clojure-runtime-resources))))

(defn worker-classpath-entries
  "Return the separated classpaths used to launch the worker JVM.

   `project-classpath-entries` is the already-resolved classpath for the project
   being analyzed. It is deliberately kept out of the JVM launch classpath so
   project jars cannot provide Skeptic's private worker runtime namespaces
   (nREPL/Nippy/analyzers/etc.). The worker receives the project classpath
   separately and installs it as the context loader only around project
   resolution/analyzer operations.

   Project operations run inside skeptic.worker.project-context, which binds the
   project loader consistently for load, read, macroexpansion, and analysis.
   Clojure itself is JVM-global, so project Clojure runtime entries must lead
   the worker boot classpath when present; ProjectContext cannot replace
   clojure.core after the worker JVM has already loaded it."
  [project-classpath-entries]
  (let [project-classpath-entries (vec (map str (or project-classpath-entries [])))]
    {:runtime (vec (distinct (concat (project-clojure-runtime-entries project-classpath-entries)
                                     (runtime-classpath-entries))))
     :project (vec (distinct project-classpath-entries))}))
