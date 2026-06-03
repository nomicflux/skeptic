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

(defn- parent-first-class?
  [class-name]
  (or (str/starts-with? class-name "java.")
      (str/starts-with? class-name "javax.")
      (str/starts-with? class-name "jdk.")
      (str/starts-with? class-name "sun.")
      (str/starts-with? class-name "com.sun.")
      (str/starts-with? class-name "clojure.")))

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
