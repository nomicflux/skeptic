(ns skeptic.project-runtime
  "Project resolution boundary for Skeptic tool/plugin runs.

  Skeptic's implementation classes are already loaded by the tool/plugin
  runtime. The project runtime adds client project classpath entries underneath
  that implementation loader so project namespaces, macros, classes, and
  resources can be resolved while Skeptic keeps owning its implementation stack."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.net URL URLClassLoader]))

(defn- has-deps-edn?
  [root]
  (.exists ^File (io/file root "deps.edn")))

(defn- implementation-loader
  []
  (or (.getContextClassLoader (Thread/currentThread))
      (.getClassLoader (class implementation-loader))))

(defn- entry-url
  ^URL [entry]
  (-> (io/file entry)
      .toURI
      .toURL))

(defn- classpath-entries
  [root aliases source-paths]
  (if (has-deps-edn? root)
    (do
      (require 'skeptic.cli.paths)
      (vec ((ns-resolve 'skeptic.cli.paths 'classpath-entries) root aliases)))
    (vec source-paths)))

(defn build-runtime
  "Build a lossless project runtime map.

  Classpath entries are converted directly to URLs. There is intentionally no
  per-entry existence validation here: real project loading/analyzer failures
  must surface from the production path that needs the entry."
  [root aliases source-paths]
  (let [root-file (.getCanonicalFile ^File (io/file root))
        root-path (.getPath root-file)
        aliases (vec (or aliases []))
        source-paths (vec (or source-paths []))
        classpath-entries (classpath-entries root-path aliases source-paths)
        loader (URLClassLoader. (into-array URL (map entry-url classpath-entries))
                                (implementation-loader))]
    {:root root-path
     :aliases aliases
     :source-paths source-paths
     :classpath-entries classpath-entries
     :loader loader}))

(defn current-runtime
  "Fallback runtime for callers, such as the Leiningen plugin, that already run
  inside the project classpath."
  [root source-paths]
  (let [root-file (.getCanonicalFile ^File (io/file root))
        loader (implementation-loader)
        source-paths (vec (or source-paths []))]
    {:root (.getPath root-file)
     :aliases []
     :source-paths source-paths
     :classpath-entries source-paths
     :loader loader}))

(defn runtime-from-classpath
  "Build a runtime from an already-resolved project classpath.

  This is used by callers such as the Leiningen plugin, where Leiningen owns
  dependency resolution and can pass Skeptic the complete project classpath
  without making Skeptic depend on Leiningen APIs."
  [root source-paths classpath-entries]
  (let [root-file (.getCanonicalFile ^File (io/file root))
        source-paths (vec (or source-paths []))
        classpath-entries (vec (or classpath-entries source-paths))
        loader (URLClassLoader. (into-array URL (map entry-url classpath-entries))
                                (implementation-loader))]
    {:root (.getPath root-file)
     :aliases []
     :source-paths source-paths
     :classpath-entries classpath-entries
     :loader loader}))

(defn with-project-runtime
  [runtime f]
  (let [loader (or (:loader runtime) (implementation-loader))
        thread (Thread/currentThread)
        previous-loader (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      (clojure.lang.Var/pushThreadBindings {clojure.lang.Compiler/LOADER loader})
      (try
        (f)
        (finally
          (clojure.lang.Var/popThreadBindings)))
      (finally
        (.setContextClassLoader thread previous-loader)))))
