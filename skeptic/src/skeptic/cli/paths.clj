(ns skeptic.cli.paths
  "Source-path discovery for the deps.edn entrypoint. Reads the project's
  deps.edn through the official tools.deps API and returns the merged
  :paths vector for the given alias selection. The Leiningen plugin does
  not use this; it gets paths from the lein project map.

  `clojure.tools.deps` is resolved lazily inside `create-basis` rather than
  required at namespace load. That keeps its heavyweight transitive graph
  (maven-resolver, maven-core, cognitect-aws, jetty) off any classpath that
  only loads this namespace without calling it -- specifically the Leiningen
  plugin classloader, which loads this ns transitively (via skeptic.cli.main)
  during self-analysis but never invokes deps.edn path discovery.

  Eligibility filter: a discovered .clj/.cljc/.cljs file is sent to the
  worker iff every dep symbol in its ns-form's :require/:require-macros/
  :use/:use-macros clauses is either a namespace defined by another
  in-project source file OR is resolvable as a classpath resource against
  a URLClassLoader built from the basis's full classpath. Rejection is
  closed under transitive project-internal requires: when G is rejected,
  every project file whose ns-form transitively requires G is also
  rejected. Rejected files never reach the worker and produce a
  :unresolvable-deps failure entry that becomes a ns-discovery-warning."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as tr]
            [skeptic.file :as file])
  (:import [java.net URL URLClassLoader]))

(defn- deps-edn-file
  [root]
  (let [f (io/file root "deps.edn")]
    (when-not (.exists f)
      (throw (ex-info (str "No deps.edn found at " (.getAbsolutePath f))
                      {:root root})))
    f))

(defn create-basis
  [root aliases]
  (let [f (deps-edn-file root)
        create (requiring-resolve 'clojure.tools.deps/create-basis)]
    (create {:project (.getAbsolutePath f)
             :aliases (or aliases [])})))

(defn- root-absolute-path
  [root path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      path
      (.getPath (io/file root path)))))

(defn- basis-classpath-entries
  [root basis]
  (mapv (partial root-absolute-path root)
        (keys (:classpath basis))))

(defn source-paths-from-basis
  [basis]
  (->> (:classpath basis)
       (remove (fn [[_ entry]] (contains? entry :lib-name)))
       (mapv key)))

(def ^:private dependency-clause-heads
  #{:require :require-macros :use :use-macros})

(def ^:private source-resource-extensions
  [".clj" ".cljc" ".cljs" "__init.class"])

(defn- distinct-files
  [files]
  (->> files
       (reduce (fn [acc f]
                 (assoc acc (.getCanonicalPath f) f))
               {})
       vals
       vec))

(defn- discover-source-files
  [root source-paths]
  (reduce (fn [{:keys [files failures]} path]
            (let [resolved (io/file (root-absolute-path root path))]
              (if-not (.exists resolved)
                {:files files :failures failures}
                (let [{new-files :files
                       new-failures :failures}
                      (file/discover-clojure-files (.getPath resolved))]
                  {:files (into files new-files)
                   :failures (into failures new-failures)}))))
          {:files []
           :failures []}
          source-paths))

(defn- features-for-file
  [^java.io.File source-file cljs-enabled?]
  (let [n (.getName source-file)]
    (cond
      (str/ends-with? n ".cljs") [#{:cljs}]
      (str/ends-with? n ".cljc") (if cljs-enabled? [#{:clj} #{:cljs}] [#{:clj}])
      :else [#{:clj}])))

(defn- read-first-ns-block
  [source-file features]
  (binding [tr/*read-eval* false]
    (with-open [reader (file/pushback-reader source-file)]
      (loop [form (tr/read {:eof nil :read-cond :allow :features features}
                           reader)]
        (cond
          (nil? form) nil
          (and (seq? form) (= 'ns (first form)) (symbol? (second form))) form
          :else (recur (tr/read {:eof nil :read-cond :allow :features features}
                                reader)))))))

(defn- read-first-in-ns-symbol
  "Some files declare their namespace via (in-ns 'sym) rather than (ns sym ...).
  Returns the declared symbol or nil."
  [source-file features]
  (binding [tr/*read-eval* false]
    (with-open [reader (file/pushback-reader source-file)]
      (loop [form (tr/read {:eof nil :read-cond :allow :features features}
                           reader)]
        (cond
          (nil? form) nil
          (and (seq? form)
               (= 'in-ns (first form))
               (= 2 (count form))
               (seq? (second form))
               (= 'quote (first (second form)))
               (symbol? (second (second form))))
          (second (second form))
          :else (recur (tr/read {:eof nil :read-cond :allow :features features}
                                reader)))))))

(defn- prefixed-symbol
  [prefix suffix]
  (symbol (str prefix "." suffix)))

(defn- vector-libspec-as-alias?
  [spec]
  (and (vector? spec)
       (boolean (some #{:as-alias} (drop 1 spec)))))

(defn- prefixed-libspec-symbol
  [prefix spec]
  (cond
    (and (vector? spec) (symbol? (first spec)) (vector-libspec-as-alias? spec)) []
    (and (vector? spec) (symbol? (first spec))) [(prefixed-symbol prefix (first spec))]
    (symbol? spec) [(prefixed-symbol prefix spec)]
    :else []))

(defn- libspec-symbols
  [spec]
  (cond
    (vector-libspec-as-alias? spec) []
    (and (vector? spec) (symbol? (first spec))) [(first spec)]
    (symbol? spec) [spec]
    (and (seq? spec) (symbol? (first spec))) (mapcat (partial prefixed-libspec-symbol
                                                               (first spec))
                                                     (rest spec))
    :else []))

(defn- ns-dependency-symbols-from-form
  [ns-form]
  (->> (drop 2 ns-form)
       (filter #(and (seq? %) (contains? dependency-clause-heads (first %))))
       (mapcat rest)
       (mapcat libspec-symbols)
       (remove nil?)
       (filter symbol?)
       (remove namespace)))

(defn ns-dependency-symbols
  "Given an ns-form (the raw `(ns ...)` list returned by the reader),
  produce the deduped list of dep symbols across :require / :require-macros
  / :use / :use-macros clauses. Empty/malformed libspecs and libspecs
  decorated with `:as-alias` contribute nothing. Public for the
  depsedn-ns-dep-extraction facet."
  [ns-form]
  (->> (ns-dependency-symbols-from-form ns-form)
       distinct
       vec))

(defn- ns-form-and-deps
  [source-file cljs-enabled?]
  (let [features (features-for-file source-file cljs-enabled?)
        forms (keep #(read-first-ns-block source-file %) features)
        primary (first forms)]
    (when primary
      {:ns (second primary)
       :dependencies (->> forms
                          (mapcat ns-dependency-symbols-from-form)
                          (remove nil?)
                          distinct
                          vec)})))

(defn- source-entry
  [source-file cljs-enabled?]
  (try
    (if-let [{:keys [ns dependencies]} (ns-form-and-deps source-file cljs-enabled?)]
      {:ns ns
       :source-file source-file
       :dependencies dependencies}
      (let [features (features-for-file source-file cljs-enabled?)]
        (when-let [in-ns-sym (some #(read-first-in-ns-symbol source-file %) features)]
          {:ns in-ns-sym
           :source-file source-file
           :dependencies []})))
    (catch Exception e
      {:source-file source-file
       :read-error e})))

(defn- ns-resource-prefix
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- ns-resource-candidates
  [ns-sym]
  (let [prefix (ns-resource-prefix ns-sym)]
    (mapv #(str prefix %) source-resource-extensions)))

(defn- url-for-classpath-entry
  ^URL [entry]
  (-> (io/file entry) .toURI .toURL))

(defn- classpath-loader
  ^URLClassLoader [classpath-entries]
  (URLClassLoader. (into-array URL (map url-for-classpath-entry classpath-entries))
                   nil))

(defn- classpath-has-ns?
  [^URLClassLoader loader ns-sym]
  (boolean (some #(.findResource loader ^String %) (ns-resource-candidates ns-sym))))

(defn- dependency-resolvable?
  [project-nss loader dep]
  (or (contains? project-nss dep)
      (classpath-has-ns? loader dep)))

(defn- direct-unresolvable-deps
  "Returns the vector of dep symbols (in declaration order, deduped) that
  the eligibility probe could not resolve. Empty vector means the entry is
  directly eligible."
  [project-nss loader {:keys [dependencies]}]
  (->> dependencies
       (remove (partial dependency-resolvable? project-nss loader))
       distinct
       vec))

(defn- direct-rejections
  "Per-entry rejection map keyed by canonical source-file path. Each value
  is {:ns SYM, :source-file FILE, :unresolvable-deps [SYM ...]} for entries
  whose ns-form parsed but had at least one unresolvable dep."
  [project-nss loader entries]
  (reduce (fn [acc {:keys [ns source-file dependencies read-error]
                    :as entry}]
            (if (or read-error (nil? ns) (empty? dependencies))
              acc
              (let [missing (direct-unresolvable-deps project-nss loader entry)]
                (if (empty? missing)
                  acc
                  (assoc acc (.getCanonicalPath ^java.io.File source-file)
                         {:ns ns
                          :source-file source-file
                          :unresolvable-deps missing})))))
          {}
          entries))

(defn- ns->path-of
  [entries]
  (into {}
        (keep (fn [{:keys [ns ^java.io.File source-file]}]
                (when (and ns source-file)
                  [ns (.getCanonicalPath source-file)]))
              entries)))

(defn- transitive-rejection-closure
  "Expands direct-rejections to include every project file F whose
  ns-form's :require closure (through project-namespaces only) reaches a
  rejected file. Returns a map of canonical-path → rejection record:
  {:ns SYM, :source-file FILE, :unresolvable-deps [SYM ...], :transitive-via [SYM ...]}.
  The :unresolvable-deps for a transitively-rejected file lists the root
  unresolvable dep symbols (carried from the original rejection); the
  :transitive-via field lists the project-internal ns symbols through
  which the rejection reaches this file."
  [entries direct]
  (let [ns->path (ns->path-of entries)]
    (loop [acc direct]
      (let [rejected-nss (set (keep :ns (vals acc)))
            acc' (reduce
                  (fn [a {:keys [ns dependencies ^java.io.File source-file]}]
                    (let [canonical (when source-file (.getCanonicalPath source-file))]
                      (cond
                        (nil? canonical) a
                        (contains? a canonical) a
                        (nil? ns) a
                        :else
                        (let [via (filter rejected-nss dependencies)]
                          (if (empty? via)
                            a
                            (let [root-deps (->> via
                                                 (mapcat
                                                  (fn [n]
                                                    (let [r (get a (get ns->path n))]
                                                      (or (:unresolvable-deps r) []))))
                                                 distinct
                                                 vec)]
                              (assoc a canonical
                                     {:ns ns
                                      :source-file source-file
                                      :unresolvable-deps root-deps
                                      :transitive-via (vec via)})))))))
                  acc
                  entries)]
        (if (= acc acc')
          acc'
          (recur acc'))))))

(defn- rejection->failure
  "Convert a rejection record to the failures-list shape (path + reason)."
  [root {:keys [^java.io.File source-file unresolvable-deps transitive-via]}]
  (let [path (.getPath source-file)
        rel (let [^java.io.File rf (io/file path)
                  root-path (.getCanonicalPath (io/file root))
                  abs (.getCanonicalPath rf)]
              (if (str/starts-with? abs (str root-path "/"))
                (subs abs (inc (count root-path)))
                ;; outside-root: leave the discovered path intact
                path))
        deps-str (str/join ", " (map str unresolvable-deps))
        msg (if (seq transitive-via)
              (format "skipped: unresolvable dep(s) %s reach this file via project requires %s"
                      deps-str
                      (str/join " → " (map str transitive-via)))
              (format "skipped: unresolvable dep(s) %s in ns-form"
                      deps-str))]
    {:path rel
     :unresolvable-deps (mapv str unresolvable-deps)
     :message msg}))

(defn- selected-source-scope
  "Given a project root, a tools.deps basis, the basis classpath entries,
  a cljs-enabled? flag, and an OPTIONAL source-paths override, return:

    {:source-files [File ...]         ; files eligible for the worker
     :source-discovery-failures [...]  ; ns-discovery-warning records}

  When source-paths-override is supplied, discovery walks those paths
  instead of the basis source-paths. The eligibility classpath probe and
  project-namespace set are still derived from the basis (per the
  symmetry invariant: :paths is a source-path override within the basis,
  never a separate basis). Failure records have shape
  {:path REL :exception E} for read-errors (legacy shape from
  file/discover-clojure-files and source-entry's catch) and
  {:path REL :unresolvable-deps [SYM ...] :message TEXT} for eligibility
  rejections."
  [root basis classpath-entries cljs-enabled? source-paths-override]
  (let [source-paths (or source-paths-override (source-paths-from-basis basis))
        {:keys [files failures]} (discover-source-files root source-paths)
        files (distinct-files files)
        entries (keep #(source-entry % cljs-enabled?) files)
        project-nss (set (keep :ns entries))
        direct (with-open [loader (classpath-loader classpath-entries)]
                 (direct-rejections project-nss loader entries))
        rejected (transitive-rejection-closure entries direct)
        rejected-paths (set (keys rejected))
        eligible-files (->> entries
                            (remove (fn [{:keys [^java.io.File source-file read-error]}]
                                      (and source-file
                                           (not read-error)
                                           (contains? rejected-paths
                                                      (.getCanonicalPath source-file)))))
                            (mapv :source-file)
                            distinct-files)
        rejection-failures (mapv (partial rejection->failure root) (vals rejected))]
    {:source-files eligible-files
     :source-discovery-failures (into (vec failures) rejection-failures)}))

(defn project-context
  "Return the source paths and project classpath derived from one tools.deps
   basis for the selected aliases. opts (third arity) may carry
   :cljs-enabled? and :source-paths-override. :source-paths-override means
   the user supplied :paths in the CLI and discovery should walk those
   paths instead of the basis's source-paths; eligibility still uses the
   alias-selected basis."
  ([root aliases]
   (project-context root aliases {}))
  ([root aliases opts]
   (let [{:keys [cljs-enabled? source-paths-override]} opts
         basis (create-basis root aliases)
         classpath-entries (basis-classpath-entries root basis)
         source-scope (selected-source-scope root basis classpath-entries
                                             cljs-enabled? source-paths-override)]
     {:basis basis
      :source-paths (source-paths-from-basis basis)
      :source-files (:source-files source-scope)
      :source-discovery-failures (:source-discovery-failures source-scope)
      :classpath-entries classpath-entries})))

(defn classpath-entries
  [root aliases]
  (basis-classpath-entries root (create-basis root aliases)))

(defn discover-paths
  [root aliases]
  (source-paths-from-basis (create-basis root aliases)))
