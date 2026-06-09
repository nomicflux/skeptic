(ns skeptic.worker.analyzer-clj
  "Worker-side clj analyzer execution. Mirrors the env-construction and
   `analyze-form` body that live in `skeptic.analysis.annotate`, with no
   Skeptic / Schema / Malli dependency. The worker reads the project's own
   source files with the real Clojure reader and analyzes them in bulk; no
   form ever crosses host->worker (the host sends only a source-file path).

   tools.analyzer.* and tools.reader.* are required eagerly at ns-load
   (worker boot), under the JVM launch classloader. The launch-classpath
   order already prefers project entries (`worker-classpath-entries` in
   `classpath.clj`), so the project's pinned tools.analyzer version wins
   via first-occurrence; no lazy require is needed."
  (:require [clojure.java.io :as io]
            [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.tools.analyzer.env :as ana.env]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]))

(def ^:private skeptic-passes-opts
  (assoc ana.jvm/default-passes-opts
         :validate/wrong-tag-handler (fn [t _ast] {t Object})))

(defn- synthetic-binding-node
  [idx sym]
  {:op :binding :form sym :name sym :local :let
   :atom (atom {}) :env {} :children [] :skeptic.synthetic/index idx})

(defn- target-ns
  [ns-sym]
  (or (some-> ns-sym find-ns)
      (some-> ns-sym create-ns)
      *ns*))

(defn- analyze-env
  [target-ns locals source-file]
  (cond-> (assoc (ta/empty-env)
                 :ns (ns-name target-ns)
                 :locals (into {}
                               (map-indexed (fn [idx sym]
                                              [sym (synthetic-binding-node idx sym)]))
                               (keys locals)))
    source-file
    (assoc :file source-file)))

(defn- normalize-raw-ast
  [ast]
  (ana.ast/prewalk ast (fn [node]
                         (cond-> node
                           (= :const (:op node)) (dissoc :type)))))

(defn- loaded-namespace-analyzer-env
  []
  (doto (ana.jvm/global-env)
    (swap! assoc :update-ns-map! (fn [] nil))))

(defn- with-loaded-namespace-analyzer-env
  "Delta A: bind `ana.env/*env*` directly to the global-env Atom, not to
   `(atom (loaded-namespace-analyzer-env))`. The macro `ana.env/with-env`
   would wrap a fresh Atom around its arg; we avoid that wrapper because
   `loaded-namespace-analyzer-env` already returns an Atom and double-wrap
   breaks `(env/deref-env)` → `mmerge`."
  [f]
  (if ana.env/*env*
    (f)
    (with-bindings {#'ana.env/*env* (loaded-namespace-analyzer-env)}
      (f))))

(defn analyze
  "Analyze `form` in `ns` against the project classpath. `opts` is a map with
   optional `:locals` (map of sym→anything; only keys are used to seed the
   analyzer's `:locals`) and `:source-file` (string). Returns a raw
   tools.analyzer.jvm AST with `:const` `:type` slots stripped."
  [form opts]
  (let [{:keys [locals ns source-file]} opts
        tn (target-ns ns)
        env (binding [*ns* tn]
              (analyze-env tn locals source-file))]
    (with-loaded-namespace-analyzer-env
      (fn []
        (binding [*ns* tn]
          (normalize-raw-ast
           (ana.jvm/analyze form env {:passes-opts skeptic-passes-opts})))))))

(defn- read-top-forms
  "Read every top-level form of `source-file` with tools.reader's
   source-logging reader (reader conditionals on the :clj branch), so each
   form carries :source/:line/:column metadata the host needs for blame and
   location. Regex/fn/quote literals read natively here, unlike clojure.edn.
   `*ns*` is bound to the (already-required) source namespace so auto-resolved
   `::keyword`s resolve against it, matching how Clojure loads the file.
   tools.reader uses a separate data-reader registry, so inherit the project
   runtime's classpath-discovered readers for this second source read."
  [tn source-file]
  (binding [*ns* tn
            tr/*data-readers* (merge clojure.core/*data-readers*
                                     tr/*data-readers*)]
    (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
      (->> (repeatedly #(tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader))
           (take-while #(not= ::eof %))
           doall))))

(defn- with-form-meta
  [original rewritten]
  (if (instance? clojure.lang.IObj rewritten)
    (with-meta rewritten (meta original))
    rewritten))

(defn- schema-defn-symbol?
  [sym]
  (and (symbol? sym)
       (= "defn" (name sym))
       (#{"s" "schema.core"} (namespace sym))))

(defn- strip-schema-argvec
  [argvec]
  (with-form-meta
    argvec
    (loop [[x & more] argvec acc []]
      (cond
        (nil? x)  (vec acc)
        (= x ':-) (recur (next more) acc)
        :else     (recur more (conj acc x))))))

(defn- strip-schema-method
  [decl]
  (let [[args & body] decl]
    (with-form-meta decl (list* (strip-schema-argvec args) body))))

(defn- strip-schema-defn
  [form]
  (let [[_defn-sym name & more] form
        more (if (= ':- (first more)) (nnext more) more)
        [docstring more] (if (string? (first more)) [(first more) (next more)] [nil more])
        [attr-map more] (if (map? (first more)) [(first more) (next more)] [nil more])
        decls (if (vector? (first more))
                [(with-form-meta (first more) (list* (strip-schema-argvec (first more)) (next more)))]
                (map strip-schema-method more))]
    (with-form-meta form
      (list* 'defn name (concat (when docstring [docstring]) (when attr-map [attr-map]) decls)))))

(defn- normalize-check-form
  "Rewrite an `s/defn` / `schema.core/defn` form into a plain `defn` by stripping
   `:- T` schema annotations from the head, return position, and arg vectors, so
   `ana.jvm/analyze` produces a root `:def` node (matching the in-process
   pre-cutover analysis contract). Non-`s/defn` forms pass through unchanged.
   Pure symbol/list surgery: no schema.core dependency, worker-safe."
  [form]
  (if (and (seq? form) (schema-defn-symbol? (first form)))
    (strip-schema-defn form)
    form))

(def ^:private class-declaration-head-names
  #{"defrecord" "deftype"})

(defn- class-declaration-form?
  [form]
  (when (seq? form)
    (let [head (first form)]
      (and (symbol? head)
           (contains? class-declaration-head-names (name head))))))

(defn- analyze-entry
  [opts form]
  (cond-> {:source-form form}
    (not (class-declaration-form? form))
    (assoc :ast (analyze (normalize-check-form form) opts))

    (class-declaration-form? form)
    (assoc :analysis-skipped? true)))

(defn- require-with-reload-retry
  [ns-sym]
  (try
    (require ns-sym)
    (catch Throwable e
      (println (str "WORKER require of " ns-sym " threw "
                    (.getName (class e)) ": " (.getMessage e)
                    " — retrying with :reload-all"))
      (flush)
      (require ns-sym :reload-all))))

(defn- worker-log
  "Per-step marker the host's stdout-drain (process.clj/start-stdout-drain!)
   echoes to host stderr under `-v`. Goes to stdout because stderr is merged
   into stdout via the worker process's redirectErrorStream."
  [label]
  (println (str "WORKER " label))
  (flush))

(defn analyze-source-file
  "Analyze every top-level form of `source-file` in namespace `ns-sym`. Loads
   the namespace first so its refers/aliases/imports resolve (matching the host
   pipeline's require-before-analyze contract). Each form is normalized
   (`s/defn` -> plain `defn`) before analysis so the AST root is the `:def`
   node def-discovery expects; the raw read form is kept as `:source-form` for
   host-side blame/location. The worker reads its own source; no form crosses
   the wire. Returns `{:entries [{:source-form form :ast ast} ...]}` pairing
   each raw top-level form with its tools.analyzer.jvm AST (`:const` `:type`
   stripped); the host projects each entry for the wire."
  [ns-sym source-file]
  (worker-log (str "requiring " ns-sym))
  (require-with-reload-retry ns-sym)
  (worker-log (str "reading top-forms of " ns-sym))
  (let [opts {:locals {} :ns ns-sym :source-file (str source-file)}
        forms (read-top-forms (target-ns ns-sym) source-file)
        form-count (count forms)
        _ (worker-log (str "analyzing " form-count " forms in " ns-sym))
        entries (into [] (map-indexed
                          (fn [idx form]
                            (when (zero? (mod idx 50))
                              (worker-log (str "  " ns-sym " form " idx "/" form-count)))
                            (analyze-entry opts form)))
                      forms)]
    (worker-log (str "done analyzing " ns-sym))
    {:entries entries}))
