(ns skeptic.worker.project-context
  "Worker-side project execution context.

   The worker has a private runtime used to boot nREPL/Nippy/analyzers. Project
   code is loaded and analyzed only while this context is active, so require,
   macroexpansion, source reading, analyzer class lookup, predicate calls, and
   declaration collection all see the same project class loader bindings."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.util Collections]))

(defrecord ProjectContext [^ClassLoader loader classpath])

(defonce ^:private installed-context (atom nil))

(defn- classpath-entries
  [cp]
  (if (str/blank? (or cp ""))
    []
    (str/split cp (re-pattern File/pathSeparator))))

(defn- file-url
  ^URL [path]
  (-> (File. path) .toURI .toURL))

(def ^:private parent-first-trees
  "Package roots whose entire subtree is parent-owned. A class belongs
   when its name equals the entry or starts with `entry + \".\"`.

   JDK packages are always trees. `clojure.lang`, `clojure.asm`, and
   `clojure.java` are Clojure-runtime internals. `clojure.spec` is the
   spec.alpha space shipped in `clojure-X.Y.Z.jar`. The remaining
   `clojure.*` entries are Skeptic-owned contrib libraries whose nested
   packages also belong to Skeptic's parent loader."
  ["java" "javax" "jdk" "sun" "com.sun"
   "clojure.lang" "clojure.asm" "clojure.java" "clojure.spec"
   "clojure.tools.analyzer" "clojure.tools.cli" "clojure.tools.deps"
   "clojure.tools.logging" "clojure.tools.reader"
   "clojure.data.json" "clojure.data.priority-map" "clojure.data.xml"
   "taoensso"])

(def ^:private parent-first-leaves
  "Single-namespace package roots whose only legitimate parent-owned
   children are JVM inner classes (`prefix$...`) or Clojure-emitted
   helpers (`prefix__init`, `prefix_deftype`, etc.). A class belongs
   when its name equals the entry, starts with `entry + \"$\"`, or
   starts with `entry + \"_\"`.

   These entries deliberately exclude the `.` boundary because their
   `prefix.X` children are independent third-party libraries (e.g.
   `clojure.core.async`, `clojure.data.priority-map`,
   `clojure.tools.namespace`) that must load from the project's
   classpath."
  ["clojure.core" "clojure.data" "clojure.datafy" "clojure.edn"
   "clojure.genclass" "clojure.gvec" "clojure.inspector" "clojure.instant"
   "clojure.main" "clojure.math" "clojure.parallel" "clojure.pprint"
   "clojure.reflect" "clojure.repl" "clojure.set" "clojure.stacktrace"
   "clojure.string" "clojure.template" "clojure.test" "clojure.uuid"
   "clojure.walk" "clojure.xml" "clojure.zip"])

(defn- in-tree?
  [class-name prefix]
  (or (= class-name prefix)
      (str/starts-with? class-name (str prefix "."))))

(defn- in-leaf?
  [class-name prefix]
  (or (= class-name prefix)
      (str/starts-with? class-name (str prefix "$"))
      (str/starts-with? class-name (str prefix "_"))))

(defn- parent-first-class?
  [class-name]
  (boolean
   (or (some (partial in-tree? class-name) parent-first-trees)
       (some (partial in-leaf? class-name) parent-first-leaves))))

(defn- enumeration
  [xs]
  (Collections/enumeration (vec xs)))

(defn- project-first-loader
  ^URLClassLoader [urls parent-loader]
  (proxy [URLClassLoader] [urls parent-loader]
    (loadClass
      ([class-name]
       (.loadClass this class-name false))
      ([class-name resolve?]
       (locking this
         (if (parent-first-class? class-name)
           (proxy-super loadClass class-name resolve?)
           (let [class# (try
                          (proxy-super findClass class-name)
                          (catch ClassNotFoundException _
                            (proxy-super loadClass class-name false))
                          (catch LinkageError _
                            (proxy-super loadClass class-name false)))]
             (when resolve?
               (proxy-super resolveClass class#))
             class#)))))
    (getResource [resource-name]
      (or (proxy-super findResource resource-name)
          (when-let [parent# (.getParent this)]
            (.getResource parent# resource-name))))
    (getResources [resource-name]
      (let [child# (enumeration-seq (proxy-super findResources resource-name))
            parent# (when-let [parent-loader# (.getParent this)]
                      (enumeration-seq (.getResources parent-loader# resource-name)))]
        (enumeration (concat child# parent#))))))

(defn make
  "Build a ProjectContext from the effective project classpath string supplied
   by the entrypoint."
  [project-cp parent-loader]
  (let [entries (vec (classpath-entries project-cp))]
    (->ProjectContext (project-first-loader (into-array URL (map file-url entries))
                                            parent-loader)
                      entries)))

(defn install!
  [project-cp]
  (reset! installed-context
          (make project-cp (.getContextClassLoader (Thread/currentThread)))))

(defn current
  []
  @installed-context)

(defn loader
  ^ClassLoader []
  (or (:loader (current))
      (.getContextClassLoader (Thread/currentThread))))

(def compiler-loader-var
  (delay
    (let [field (doto (.getDeclaredField clojure.lang.Compiler "LOADER")
                  (.setAccessible true))
          v (.get field nil)]
      (when-not (var? v)
        (throw (ex-info "clojure.lang.Compiler/LOADER is not a Var"
                        {:value-class (some-> v class .getName)})))
      v)))

(defmacro with-project-context
  "Run body inside the installed project execution context.

   This binds all loader knobs Clojure consults during project require/load and
   macroexpansion: the thread context ClassLoader, *use-context-classloader*,
   and clojure.lang.Compiler/LOADER."
  [& body]
  `(let [loader# (loader)
         thread# (Thread/currentThread)
         old-loader# (.getContextClassLoader thread#)]
     (try
       (.setContextClassLoader thread# loader#)
       (with-bindings {(force compiler-loader-var) loader#}
         (binding [*use-context-classloader* true]
           ~@body))
       (finally
         (.setContextClassLoader thread# old-loader#)))))
