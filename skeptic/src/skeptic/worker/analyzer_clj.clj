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

(defn- stub-class-definition-parse
  "ana.jvm's parse of `deftype*` evals a method-less skeleton class under the
   record's FIXED class name (`-deftype`), replacing the loaded class in the
   shared DynamicClassLoader cache; lazy constant-pool resolution then hands
   the skeleton to code from the original load. The namespace is required
   before analysis, so the real class already exists — analyze the form as a
   nil constant instead. `reify*` keeps the default parse: its analysis class
   name is gensym'd and collides with nothing."
  [form env]
  (if (and (seq? form) (= 'deftype* (first form)))
    (ana.jvm/parse '(quote nil) env)
    (ana.jvm/parse form env)))

(defn- gen-interface-form?
  "Name check BEFORE resolution: ns-resolve on arbitrary heads would
   class-probe dotted symbols like (Foo. x), the known sibling-collision
   hazard. Only a head literally named gen-interface gets resolved."
  [form env]
  (let [head (when (seq? form) (first form))]
    (and (symbol? head)
         (= "gen-interface" (name head))
         (= #'clojure.core/gen-interface
            (ns-resolve (or (some-> (:ns env) find-ns) *ns*) head)))))

(defn- stub-gen-interface-macroexpand-1
  "clojure.core/gen-interface loads the interface class at MACROEXPANSION
   time; every defprotocol expansion contains one, so expanding it during
   analysis re-defines the protocol's interface under its fixed name. The
   interface already exists (require-before-analyze), so such forms expand
   to nil; everything else takes ana.jvm's expansion."
  [form env]
  (if (gen-interface-form? form env)
    '(quote nil)
    (ana.jvm/macroexpand-1 form env)))

(def ^:private analysis-class-safety-bindings
  "tools.analyzer's pluggable parse/macroexpand-1, overridden through
   ana.jvm/analyze's documented :bindings extension point, so analysis never
   (re)defines a class in the live worker runtime."
  {#'ta/parse stub-class-definition-parse
   #'ta/macroexpand-1 stub-gen-interface-macroexpand-1})

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
           (ana.jvm/analyze form env {:passes-opts skeptic-passes-opts
                                      :bindings analysis-class-safety-bindings})))))))

(defn- read-one-form
  "One top-level form via tools.reader (reader conditionals on the :clj
   branch). Regex/fn/quote literals read natively here, unlike clojure.edn.
   tools.reader keeps its own data-reader Vars; mirror clojure.core's CURRENT
   values on every read so a registration evaluated earlier in this load (a
   set! of *data-readers* mid-file) is visible to the next form, exactly as
   it is to Clojure's own load."
  [reader]
  (binding [tr/*data-readers* (merge clojure.core/*data-readers*
                                     tr/*data-readers*)
            tr/*default-data-reader-fn* (or tr/*default-data-reader-fn*
                                            clojure.core/*default-data-reader-fn*)]
    (tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader)))

(defn- read-top-forms
  "Read every top-level form of `source-file` with tools.reader's
   source-logging reader, so each form carries :source/:line/:column metadata
   the host needs for blame and location. The path for namespaces an earlier
   require already loaded: the source is re-read without evaluation, with
   `*ns*` bound to the loaded namespace so auto-resolved `::keyword`s resolve
   against it. Load-frame-local reader registrations are gone by now;
   `load-top-forms` is the path that sees them."
  [tn source-file]
  (binding [*ns* tn]
    (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
      (->> (repeatedly #(read-one-form reader))
           (take-while #(not= ::eof %))
           doall))))

(defn- compiler-load-bindings
  "The thread bindings Compiler.load pushes around a file load: a fresh
   DynamicClassLoader, source path/name, and the reader/compiler dynamics
   whose in-file set!s must stay local to this load (a set! never escapes the
   file's own load — .scratch/reader-probes/ probe2)."
  [source-path source-name]
  {clojure.lang.Compiler/LOADER (clojure.lang.RT/makeClassLoader)
   clojure.lang.Compiler/SOURCE_PATH source-path
   clojure.lang.Compiler/SOURCE source-name
   #'*ns* *ns*
   #'*read-eval* *read-eval*
   #'*data-readers* *data-readers*
   #'*unchecked-math* *unchecked-math*
   #'*warn-on-reflection* *warn-on-reflection*})

(defn- load-top-forms
  "Load `source-file` exactly as the project's own require would — read one
   form, evaluate it, read the next — capturing each raw form with its
   :source/:line/:column metadata. Two readers walk the file in lockstep:
   Clojure's own reader yields the form that is EVALUATED (what Compiler.load
   compiles — line/column metadata only; evaluating the source-logged twin
   compiles its :source strings into the constant initializer and can blow
   the JVM's 64KB method limit on large literal files, observed on reitit's
   core_test.cljc), tools.reader's source-logging reader yields the CAPTURED
   twin. Both reads of form N happen before form N evaluates, so the
   evaluation between steps makes load-time reader registrations (a set! of
   *data-readers* at the top of the file) visible to the file's own later
   tagged literals; a cold re-read after the load cannot see them, because
   the load's binding frame has popped (.scratch/reader-probes/ probe4 vs
   probe5)."
  [source-file]
  (with-bindings (compiler-load-bindings (str source-file)
                                         (.getName (io/file source-file)))
    (with-open [eval-rdr (clojure.lang.LineNumberingPushbackReader. (io/reader source-file))
                capture-rdr (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
      (loop [forms []]
        (let [eval-form (read {:read-cond :allow :eof ::eof} eval-rdr)
              form (read-one-form capture-rdr)]
          (cond
            (and (= ::eof eval-form) (= ::eof form)) forms
            (or (= ::eof eval-form) (= ::eof form))
            (throw (ex-info "lockstep readers disagree on form count"
                            {:source-file (str source-file)
                             :forms-captured (count forms)}))
            :else (do (eval eval-form)
                      (recur (conj forms form)))))))))

(defn- namespace-loaded?
  [ns-sym]
  (contains? (loaded-libs) ns-sym))

(defn- mark-namespace-loaded!
  "What require records after a successful load, so a later project require
   of this namespace does not re-evaluate the file `load-top-forms` just
   evaluated."
  [ns-sym]
  (dosync (commute @#'clojure.core/*loaded-libs* conj ns-sym)))

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

(defn- schema-macros-parsers
  "The project's own s/defn grammar fns, resolved at runtime from the live
   schema.macros (the worker carries no static schema dependency; explicit
   require + resolve, Clojure 1.8 floor). nil when the project has no
   plumatic schema on its classpath."
  []
  (try
    (require 'schema.macros)
    (let [normalized (resolve 'schema.macros/normalized-defn-args)
          process (resolve 'schema.macros/process-arrow-schematized-args)
          split-rest (resolve 'schema.macros/split-rest-arg)]
      (when (and normalized process split-rest)
        {:normalized-defn-args normalized
         :process-args process
         :split-rest-arg split-rest}))
    (catch Throwable _ nil)))

(defn- clean-argvec
  "Strip :- annotations from one arg vector with schema's own parsers, in the
   composition process-fn-arity uses: process the flat vector, then let
   split-rest-arg recurse into an annotated rest-destructure."
  [{:keys [process-args split-rest-arg]} argvec]
  (let [[regular rest-arg] (split-rest-arg nil (process-args nil argvec))]
    (with-form-meta argvec
      (if rest-arg (conj (vec regular) '& rest-arg) (vec regular)))))

(defn- clean-arity
  [parsers decl]
  (let [[args & body] decl]
    (with-form-meta decl (list* (clean-argvec parsers args) body))))

(defn- strip-schema-defn
  [parsers form]
  (let [[name & more] ((:normalized-defn-args parsers) nil (rest form))
        decls (if (vector? (first more))
                [(clean-arity parsers more)]
                (map #(clean-arity parsers %) more))]
    (with-form-meta form
      (list* 'defn (vary-meta name dissoc :schema) decls))))

(defn- normalize-check-form
  "Rewrite an `s/defn` / `schema.core/defn` form into a plain `defn` so
   `ana.jvm/analyze` produces a root `:def` node (matching the in-process
   pre-cutover analysis contract). Head parsing (name, :- output, docstring,
   attr-map) and argvec stripping run on schema.macros' own grammar fns from
   the project runtime — the worker does not reimplement the s/defn grammar.
   Passing env nil matches the real macro's top-level clj expansion (&env is
   nil there). Non-`s/defn` forms, and projects without schema.macros, pass
   through unchanged."
  [form]
  (if (and (seq? form) (schema-defn-symbol? (first form)))
    (if-let [parsers (schema-macros-parsers)]
      (strip-schema-defn parsers form)
      form)
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
    (assoc :ast (analyze (binding [*ns* (target-ns (:ns opts))]
                           (normalize-check-form form))
                         opts))

    (class-declaration-form? form)
    (assoc :analysis-skipped? true)))

(defn- worker-log
  "Per-step marker the host's stdout-drain (process.clj/start-stdout-drain!)
   echoes to host stderr under `-v`. Goes to stdout because stderr is merged
   into stdout via the worker process's redirectErrorStream."
  [label]
  (println (str "WORKER " label))
  (flush))

(defn- source-top-forms
  "The file's top-level forms, by the path that matches the project's runtime
   state: a namespace nothing has loaded yet gets the capturing load — the
   worker performs the project's own load of the file, so load-time reader
   registrations are visible to the forms being captured — and is then
   recorded as loaded; a namespace some earlier require already pulled in is
   not re-evaluated, its source is re-read without evaluation."
  [ns-sym source-file]
  (if (namespace-loaded? ns-sym)
    (do (worker-log (str "reading top-forms of already-loaded " ns-sym))
        (read-top-forms (target-ns ns-sym) source-file))
    (do (worker-log (str "loading " ns-sym))
        (let [forms (load-top-forms source-file)]
          (mark-namespace-loaded! ns-sym)
          forms))))

(defn analyze-source-file
  "Analyze every top-level form of `source-file` in namespace `ns-sym`. The
   namespace's first load is performed by the worker itself, capturing forms
   as that load reads them (see `source-top-forms`), so refers/aliases/imports
   resolve and load-time reader state is honored. Each form is normalized
   (`s/defn` -> plain `defn`) before analysis so the AST root is the `:def`
   node def-discovery expects; the raw read form is kept as `:source-form` for
   host-side blame/location. The worker reads its own source; no form crosses
   the wire. Returns `{:entries [{:source-form form :ast ast} ...]}` pairing
   each raw top-level form with its tools.analyzer.jvm AST (`:const` `:type`
   stripped); the host projects each entry for the wire."
  [ns-sym source-file]
  (let [opts {:locals {} :ns ns-sym :source-file (str source-file)}
        forms (source-top-forms ns-sym source-file)
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
