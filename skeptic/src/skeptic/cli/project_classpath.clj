(ns skeptic.cli.project-classpath
  "Project classpath trampoline for the deps.edn CLI entrypoint."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skeptic.cli.cljs.shadow :as shadow]
            [skeptic.cli.paths :as paths])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect]
           [java.util.regex Pattern]))

(def ready-property "skeptic.cli.project-classpath.ready")

(defn ready?
  []
  (Boolean/getBoolean ready-property))

(defn deps-project?
  [root]
  (.exists ^File (io/file root "deps.edn")))

(defn runtime-aliases
  [root options]
  (->> (concat (:alias options) (shadow/deps-aliases root))
       (filter keyword?)
       distinct
       vec))

(defn current-classpath-entries
  []
  (let [cp (System/getProperty "java.class.path" "")]
    (if (str/blank? cp)
      []
      (vec (.split cp (Pattern/quote File/pathSeparator))))))

(defn merge-classpath-entries
  [project-entries current-entries]
  (vec (distinct (concat (map str project-entries)
                         (map str current-entries)))))

(defn classpath-string
  [entries]
  (str/join File/pathSeparator entries))

(defn java-executable
  []
  (let [java-home (System/getProperty "java.home")]
    (str (io/file java-home "bin" "java"))))

(defn child-command
  [classpath args]
  (vec (concat [(java-executable)
                (str "-D" ready-property "=true")
                "-cp"
                classpath
                "clojure.main"
                "-m"
                "skeptic.cli.main"]
               args)))

(defn project-classpath-command
  [root options args]
  (let [aliases (runtime-aliases root options)]
    (when (and (not (ready?))
               (deps-project? root)
               (seq aliases))
      (let [project-cp (paths/classpath-entries root aliases)
            merged-cp  (merge-classpath-entries project-cp (current-classpath-entries))]
        {:command (child-command (classpath-string merged-cp) args)
         :directory (.getAbsoluteFile ^File (io/file root))
         :aliases aliases
         :classpath merged-cp}))))

(defn- copy-stream!
  [in out]
  (with-open [input in]
    (io/copy input out)
    (.flush out)))

(defn run-child!
  [{:keys [command directory]}]
  (let [pb (ProcessBuilder. ^java.util.List command)]
    (.directory pb ^File directory)
    (.redirectInput pb ProcessBuilder$Redirect/INHERIT)
    (let [process (.start pb)
          stdout  (future (copy-stream! (.getInputStream process) *out*))
          stderr  (future (copy-stream! (.getErrorStream process) *err*))
          exit    (.waitFor process)]
      @stdout
      @stderr
      exit)))
