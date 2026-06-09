(ns skeptic.file
  (:require [clojure.java.io :as io]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt])
  (:import [java.io File]))

(defn is-ns-block?
  [[field & _]]
  (= field 'ns))

(defn try-read
  [stream]
  (tr/read {:eof nil :read-cond :allow :features #{:clj}} stream))

(defn pushback-reader
  [^File file]
  (rt/source-logging-push-back-reader (io/reader file) 1 (.getPath file)))

(defn- find-ns-form
  [^File file]
  (with-open [reader (pushback-reader file)]
    (loop [form (try-read reader)]
      (cond
        (nil? form) nil
        (is-ns-block? form) form
        :else (recur (try-read reader))))))

(defn ns-for-clojure-file
  [^File file]
  [(some-> (find-ns-form file) (->> (drop 1) first))
   file])

(defn- require-clause-ns
  "The required namespace symbol from one entry in a `(:require ...)` clause.
   Entries are either bare symbols `foo.bar` or vectors `[foo.bar :as f :refer [x]]`
   or prefix-lists `[foo [bar :as b] [baz :as bz]]` (where the first element is the
   prefix and following elements are vectors). Returns the qualified ns symbol, or
   nil for prefix-lists (which expand to multiple ns-syms — handled by caller)."
  [entry]
  (cond
    (symbol? entry) entry
    (vector? entry) (when (symbol? (first entry)) (first entry))
    :else nil))

(defn- expand-prefix-list
  "A prefix-list `(prefix [a ...] [b ...])` expands to ns-syms `prefix.a`, `prefix.b`.
   Returns a vector of ns-syms, or nil if `entry` is not a prefix-list."
  [entry]
  (when (and (or (list? entry) (vector? entry))
             (symbol? (first entry))
             (every? #(or (vector? %) (symbol? %)) (rest entry))
             (some #(or (vector? %) (list? %)) (rest entry)))
    (let [prefix (name (first entry))]
      (vec (keep (fn [sub]
                   (let [sub-sym (cond (symbol? sub) sub
                                       (vector? sub) (first sub))]
                     (when (symbol? sub-sym)
                       (symbol (str prefix "." (name sub-sym))))))
                 (rest entry))))))

(defn- require-clause-nses
  [entry]
  (or (expand-prefix-list entry)
      (some-> (require-clause-ns entry) vector)
      []))

(defn ns-head-for-clojure-file
  "Read the file's top-level `ns` form and return a head map suitable for
   topological sorting: `{:name <ns-sym> :requires {<ns-sym> <ns-sym>}
   :require-macros {} :use-macros {}}`. Bare clj/cljc files have no
   require-macros/use-macros — those slots stay empty so the shape matches
   the cljs head shape `topo-sort-files` consumes. Returns nil when the file
   has no readable `ns` form."
  [^File file]
  (when-let [form (find-ns-form file)]
    (let [ns-sym (some-> form (->> (drop 1) first))
          body (drop 2 form)
          require-clauses (->> body
                               (filter (fn [x] (and (seq? x) (= :require (first x)))))
                               (mapcat rest))
          requires-syms (into [] (mapcat require-clause-nses) require-clauses)]
      {:name ns-sym
       :requires (into {} (map (fn [s] [s s])) requires-syms)
       :require-macros {}
       :use-macros {}})))

(defn- clojure-file?
  [^File file]
  (and (.isFile file)
       (re-matches #".*\.clj[cs]?" (.getName file))))

(defn- discovery-result
  ([]
   (discovery-result [] [] #{}))
  ([files failures visited]
   {:files files
    :failures failures
    :visited visited}))

(defn- merge-discovery-results
  [left right]
  (discovery-result (into (:files left) (:files right))
                    (into (:failures left) (:failures right))
                    (:visited right)))

(defn- canonical-file
  [^File file]
  (.getCanonicalFile file))

(defn- discovery-failure
  [^File file exception]
  {:path (.getPath file)
   :exception exception})

(defn- list-directory
  [^File dir]
  (let [children (.listFiles dir)]
    (if (nil? children)
      (throw (ex-info (format "Could not list directory %s" (.getPath dir))
                      {:path (.getPath dir)}))
      children)))

(defn- discover-clojure-files*
  [^File file visited active]
  (try
    (let [canonical (canonical-file file)]
      (cond
        (clojure-file? canonical)
        (discovery-result [canonical] [] visited)

        (.isFile canonical)
        (discovery-result [] [] visited)

        (not (.exists canonical))
        (discovery-result [] [(discovery-failure file
                                                 (ex-info (format "Path does not exist: %s" (.getPath file))
                                                          {:path (.getPath file)}))]
                          visited)

        (not (.isDirectory canonical))
        (discovery-result [] [] visited)

        :else
        (let [canonical-path (.getPath canonical)]
          (cond
            (contains? visited canonical-path)
            (discovery-result [] [] visited)

            (contains? active canonical-path)
            (discovery-result [] [] visited)

            :else
            (let [children (list-directory canonical)
                  child-active (conj active canonical-path)
                  result (reduce (fn [acc child]
                                   (merge-discovery-results acc
                                                            (discover-clojure-files* child
                                                                                     (:visited acc)
                                                                                     child-active)))
                                 (discovery-result [] [] visited)
                                 children)]
              (assoc result :visited (conj (:visited result) canonical-path)))))))
    (catch Exception e
      (discovery-result [] [(discovery-failure file e)] visited))))

(defn discover-clojure-files
  [^String path]
  (let [{:keys [files failures]} (discover-clojure-files* (io/file path) #{} #{})
        files (->> files
                   (reduce (fn [acc ^File file]
                             (assoc acc (.getPath file) file))
                           {})
                   vals
                   vec)]
    {:files files
     :failures failures}))

(defn clojure-files-for-path
  [^String path]
  (:files (discover-clojure-files path)))

(defn relative-path
  [^File root ^String filename]
  (-> (.toURI root)
      (.relativize (.toURI (io/file filename)))
      (.getPath)))
