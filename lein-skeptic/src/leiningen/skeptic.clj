(ns leiningen.skeptic
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.main]
            [leiningen.core.classpath]
            [leiningen.core.project]
            [skeptic.cli.cljs.lein :as cljs-lein]
            [skeptic.cli.options :as cli-opts])
  (:import [java.io File]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['org.clojars.nomicflux/skeptic "0.9.0-rc5"
                                      :exclusions ['org.clojure/tools.deps]]
                                     ['prismatic/schema "1.4.1"]]})

(defn- implementation-project
  [project profile]
  (-> project
      (assoc :dependencies (:dependencies profile)
             :source-paths []
             :test-paths []
             :resource-paths []
             :java-source-paths []
             :prep-tasks [])))

(defn- checker-form
  [options root paths project-classpath-entries]
  `(do
     (require 'clojure.java.io)
     (require 'schema.core)
     (require 'skeptic.core)
     (require 'skeptic.profiling)
     (require 'skeptic.project-runtime)
     (let [runtime# (skeptic.project-runtime/runtime-from-classpath
                     ~root ~(vec paths) ~(vec project-classpath-entries))
           options# (assoc '~options :skeptic/project-runtime runtime#)
           output-path# (:output options#)
           writer# (when output-path# (clojure.java.io/writer output-path#))
           exit-code# (try
                        (binding [*out* (or writer# *out*)]
                          (schema.core/without-fn-validation
                            (skeptic.profiling/run options# ~(str root "/target")
                              (fn []
                                (apply skeptic.core/check-project
                                       options#
                                       ~root
                                       '~(vec paths))))))
                        (finally
                          (when writer#
                            (.flush writer#)
                            (.close writer#))))]
       (System/exit exit-code#))))

(defn- java-command
  [implementation-classpath form]
  ["java"
   "-cp" (str/join File/pathSeparator implementation-classpath)
   "clojure.main"
   "-e" (pr-str form)])

(defn- run-checker-subprocess!
  [root implementation-classpath form]
  (let [process (-> (ProcessBuilder. (into-array String (java-command implementation-classpath form)))
                    (doto (.directory (io/file root))
                          (.inheritIO))
                    (.start))]
    (.waitFor process)))

(defn skeptic
  {:doc (str "Run skeptic on this project's source- and test-paths.\n\n"
             "Usage: lein skeptic [OPTIONS]\n\n"
             "Options:\n"
             (:summary (cli-opts/parse [])))}
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)
        paths (:source-paths (cljs-lein/discover-sources project))
        {:keys [options summary errors]} (cli-opts/parse args)]
    (cond
      (:help options) (println summary)
      errors          (do (doseq [e errors] (leiningen.core.main/warn e))
                          (leiningen.core.main/warn summary)
                          (leiningen.core.main/abort))
      :else
      (let [project-classpath (vec (leiningen.core.classpath/get-classpath project))
            impl-project (implementation-project project profile)
            implementation-classpath (vec (leiningen.core.classpath/get-classpath impl-project))
            form (checker-form options (:root project) paths project-classpath)]
        (System/exit (run-checker-subprocess! (:root project)
                                              implementation-classpath
                                              form))))))
