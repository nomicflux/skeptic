(ns skeptic.worker.server
  "Worker-side nREPL server. Runs in the spawned JVM on the combined
   project-first launch classpath and answers host requests on demand. Project
   operations run on the clojure.main launch thread, so project code loads
   exactly as the project's own runtime loads it — Skeptic adds no reader-Var
   or loading machinery. Plan 2 Phase 1.5 adds the handle-table machinery: every Class
   operand on the wire is an opaque handle (integer for bootstrap-interned
   host-runtime classes; UUID-string for project classes). Worker is sole owner
   of `Class/forName`, `.isAssignableFrom`, `instance?`, and class equality.

   Phase 5 adds the analyzer ops. The analyzer-execution glue lives in
   `skeptic.worker.analyzer-clj` / `skeptic.worker.analyzer-cljs`. No other
   `skeptic.*` namespace is required from this server: Skeptic's own analysis
   code and Plumatic Schema / Malli stay on the host (B3/B4).

   nREPL is lazy-loaded inside `start!` from the worker JVM's single launch
   classloader (project-cp first, worker jars second). The ns form does NOT
   require any nrepl.* namespace at load time — projects pinning a different
   nrepl win their version via first-occurrence on the launch cp. The
   `wrap-*` dispatchers are let-bound inside `start!`, closing over locally
   resolved `nrepl.transport/send`, `nrepl.misc/response-for`, and
   `nrepl.server/{start-server,unknown-op}`. Descriptor metadata is dead
   code under the explicit handler thread and has been removed."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [skeptic.worker.analyzer-clj :as wac]
            [skeptic.worker.analyzer-cljs :as wac-cljs]
            [skeptic.worker.client :as worker-client]
            [skeptic.worker.transport :as worker-transport]
            [skeptic.worker.wire :as wire])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(defonce ^:private handle-state
  (atom {:next-id 0 :id->class {} :class->id {}}))

(defonce ^:private fn-handle-state
  (atom {:next-id 0 :id->fn {} :fn->id {}}))

(def ^:dynamic *project-operation-thread* false)

(defonce ^:private project-operation-submit
  (atom nil))

(defn- intern-class!
  "Returns the existing handle for `c` if interned, else mints a new one. When
   `id-kind` is `:integer` (bootstrap path) the new id is an integer counter;
   when `:uuid` (resolve/project path) it is a UUID string. Bootstrap classes
   get integer ids because their identity is guaranteed unique by the JVM
   bootloader rules (D12); project classes get UUIDs."
  [^Class c id-kind]
  (let [{:keys [class->id]} @handle-state]
    (or (get class->id c)
        (let [new-id (case id-kind
                       :integer (-> (swap! handle-state update :next-id inc)
                                     :next-id)
                       :uuid    (str (java.util.UUID/randomUUID)))]
          (swap! handle-state
                 (fn [{:keys [id->class class->id] :as s}]
                   (assoc s
                          :id->class (assoc id->class new-id c)
                          :class->id (assoc class->id c new-id))))
          new-id))))

(defn- handle->class
  "Returns the ^Class for handle `id`, or nil if not interned."
  [id]
  (get (:id->class @handle-state) id))

(def ^:private primitive-name->class
  "Primitive classes are not loadable via `Class/forName`; resolve them through
   their boxed `TYPE` field instead."
  {"boolean" Boolean/TYPE "byte" Byte/TYPE "short" Short/TYPE
   "int" Integer/TYPE "long" Long/TYPE "float" Float/TYPE
   "double" Double/TYPE "char" Character/TYPE})

(declare handle-loopback-message project-entry)

(defn- project-oracle-vars
  []
  (try
    (require 'skeptic.analysis.class-oracle)
    (let [oracle-ns (find-ns 'skeptic.analysis.class-oracle)]
      (select-keys (ns-publics oracle-ns)
                   '[*worker-conn* *host-class-handles* *class-rel-cache* *predicate-cache*
                     intern-host-classes!]))
    (catch Throwable e
      (println (str "WORKER project-oracle-vars: skeptic.analysis.class-oracle unavailable: "
                    (.getName (class e)) ": " (.getMessage e)))
      (flush)
      nil)))

(defn- project-oracle-bindings
  []
  (when-let [{:syms [*worker-conn* *host-class-handles* *class-rel-cache* *predicate-cache*
                    intern-host-classes!]}
             (project-oracle-vars)]
    (let [conn (worker-client/loopback-conn handle-loopback-message)
          host-handles (if intern-host-classes!
                         (intern-host-classes! conn)
                         {})]
      (cond-> {*worker-conn* conn}
        *host-class-handles* (assoc *host-class-handles* host-handles)
        *class-rel-cache* (assoc *class-rel-cache* (atom {}))
        *predicate-cache* (assoc *predicate-cache* (atom {}))))))

(defn- run-project-operation
  [f]
  (cond
    *project-operation-thread* (f)
    @project-operation-submit (@project-operation-submit f)
    :else (f)))

(defmacro ^:private with-project-operation
  [& body]
  `(run-project-operation
    (fn []
      (let [bindings# (project-oracle-bindings)]
        (if (seq bindings#)
          (with-bindings bindings#
            ~@body)
          (do ~@body))))))

(defn- resolve-bootstrap-class
  "The ^Class for bootstrap name `nm`: a primitive via its TYPE field, else
   `Class/forName`. nil if the name does not resolve. `Class/forName` can
   surface a missing or unloadable class as `ClassNotFoundException`,
   `NoClassDefFoundError` (a transitive supertype/interface is missing on
   the worker classpath), `ExceptionInInitializerError` (the class's static
   initializer threw), or other `LinkageError` subclasses. Treat any of them
   as 'unresolved' so a hostile project class on the worker classpath cannot
   make `intern-host-classes!` throw — that would escape `wrap-intern-host-classes`
   with no reply and hang the host's `recv-reply`."
  [nm]
  (or (get primitive-name->class nm)
      (try (Class/forName nm)
           (catch ClassNotFoundException _ nil)
           (catch NoClassDefFoundError _ nil)
           (catch ExceptionInInitializerError _ nil)
           (catch LinkageError _ nil))))

(defn- intern-host-classes-reply
  "Per-name: resolve the Class; on success intern as integer; skip if unresolved.
   Returns the `{name handle-id}` map for names that resolved."
  [class-names]
  (reduce (fn [acc nm]
            (if-let [c (resolve-bootstrap-class nm)]
              (assoc acc nm (intern-class! c :integer))
              acc))
          {} class-names))

(defn- run-class-rel
  "Dispatches a class relation against handle operands. `:a` is always a handle.
   For `:instance?` `:b` is a runtime value; otherwise `:b` is a handle."
  [rel a b]
  (let [ca (handle->class a)]
    (case rel
      :assignable-from (boolean (and ca (when-let [cb (handle->class b)]
                                          (.isAssignableFrom ^Class ca ^Class cb))))
      :equals          (boolean (and ca (= ca (handle->class b))))
      :instance?       (boolean (and ca (instance? ^Class ca b))))))

(defn- run-class-rel-batch
  "Answers a vector of `{:rel :a :b}` triples in one pass, returning a vector of
   booleans positionally matching `triples`."
  [triples]
  (mapv (fn [{:keys [rel a b]}] (run-class-rel rel a b)) triples))

(defn- resolve-sym-to-handle
  "In the namespace `ns-name`, resolve `sym` to a Class and return its handle
   (minting via the UUID branch). nil if `sym` doesn't resolve to a Class."
  [ns-name sym]
  (with-project-operation
    (or (when-let [c (some (fn [class-name]
                             (when class-name
                               (or (resolve-bootstrap-class class-name)
                                   (try
                                     (Class/forName class-name false (.getContextClassLoader (Thread/currentThread)))
                                     (catch ClassNotFoundException _ nil)))))
                           [(str sym)
                            (when-not (namespace (symbol sym))
                              (str "java.lang." sym))])]
          (intern-class! ^Class c :uuid))
        (when-let [n (find-ns (symbol ns-name))]
          (binding [*ns* n]
            (let [v (resolve (symbol sym))]
              (when (class? v)
                (intern-class! ^Class v :uuid))))))))

(defn- reader-error-cause
  "If `e` or any of its causes is a tools.reader exception (a known
   phase-localized analyzer failure — unbalanced delimiter, EOF inside a form,
   etc.), return that Throwable. nil for any other failure kind. This is the
   ONLY catch-and-encode allowed in `analyze-namespace-reply`: a reader error
   on one source-file is a per-namespace finding (the pipeline routes it as a
   `:clj-load` finding so other namespaces still run), not a transport-level
   exception. Every other Throwable propagates and the wrap-* boundary
   converts it to a loud `:exception-class` reply."
  [^Throwable e]
  (->> (iterate #(.getCause ^Throwable %) e)
       (take-while some?)
       (some (fn [^Throwable c]
               (when (= :reader-exception (:type (ex-data c))) c)))))

(defn- analyze-namespace-reply
  "Returns either `{:entries ...}` on success or `{:read-failure <msg>}` when a
   tools.reader exception fires (a known phase-localized failure). Any other
   Throwable propagates, hits the wrap-* boundary, and surfaces as a loud
   `:exception-class` wire reply."
  [ns source-file]
  (try
    (let [projected (with-project-operation
                      (let [ns-sym (symbol ns)
                            {:keys [entries]} (wac/analyze-source-file ns-sym (io/file source-file))]
                        (mapv #(project-entry ns-sym %) entries)))]
      {:entries projected})
    (catch Throwable e
      (if-let [re (reader-error-cause e)]
        {:read-failure (or (.getMessage ^Throwable re) (str re))}
        (throw e)))))

(defn- class-name-for-handle
  "The canonical name of the Class behind handle `a`, or nil if not interned.
   The worker holds the Class, so `.getName` is legal here."
  [a]
  (when-let [c (handle->class a)]
    (.getName ^Class c)))

(defn- ast-node?
  "True when `v` is a tools.analyzer AST node (a plain map carrying `:op`).
   Excludes sorted maps: a `case*` dispatch map is a `PersistentTreeMap` keyed by
   integer hashes, and `(contains? v :op)` on it throws when its comparator is
   handed the keyword `:op`. AST nodes are always plain hash-maps, never sorted,
   so a sorted map is data to be projected, not a node to recurse into."
  [v]
  (and (map? v) (not (sorted? v)) (contains? v :op)))

(defn- strip-host-unread
  "Remove host-unread slots. Strips :atom :env :o-tag :return-tag, plus the
   verified host-unread analyzer flags :once :max-fixed-arity :variadic? :bridges.
   Strips :info but retains [:info :name]. Strips :meta only when it is raw
   var-metadata (no :op key)."
  [n]
  (let [info-name (get-in n [:info :name])
        n' (dissoc n :atom :env :o-tag :return-tag :info
                   :once :max-fixed-arity :variadic? :bridges)
        n' (if info-name (assoc-in n' [:info :name] info-name) n')
        meta-val (:meta n')]
    (if (and (some? meta-val) (not (ast-node? meta-val)))
      (dissoc n' :meta)
      n')))

(defn- edn-safe?
  [v]
  (cond
    (or (nil? v) (instance? Boolean v) (number? v) (string? v) (keyword? v) (symbol? v)) true
    ;; A Class can print as a readable symbol, but Nippy preserves the actual
    ;; Class object. It must cross only through the worker's opaque handle model.
    (class? v) false
    ;; An analyzer AST node (map with :op) is never an EDN-safe incidental leaf:
    ;; it carries `:env` back-refs (cljs) / cycles that this recursive check
    ;; would run away into. The host reads AST nodes only through `:children`
    ;; projection, never raw off an incidental slot — so treat any :op map as
    ;; non-EDN WITHOUT descending it. Closes the recursive-local-fn runaway.
    (ast-node? v) false
    ;; A defrecord (e.g. clojure.reflect.Method) is a map? but pr-str emits a
    ;; `#fqcn{...}` tagged literal the host's edn/read cannot read regardless of
    ;; field contents — never EDN-safe via the map branch.
    (instance? clojure.lang.IRecord v) false
    (map? v) (every? (fn [[k mv]] (and (edn-safe? k) (edn-safe? mv))) v)
    (coll? v) (every? edn-safe? v)
    ;; Leaves beyond the plain types above are wire-safe only when transit's
    ;; default handlers carry them with their class intact (probe:
    ;; .scratch/load-as-project/transit-leaf-probe.output.txt). A pr-str
    ;; round-trip is NOT the test: a project print-method that emits a
    ;; readable form (joda DateTime as #inst) makes a live object look
    ;; plain while the marshaller still has no handler for it. Date is
    ;; exact-class because transit returns subclasses (sql.Timestamp) as
    ;; java.util.Date, which would lie to host-side class typing.
    :else (or (char? v)
              (instance? java.util.UUID v)
              (= java.util.Date (class v)))))

(defn- nonedn-sentinel [v]
  (wire/nonedn-sentinel (intern-class! (class v) :uuid)))

(defn- project-val-form
  "For :val/:form slots: Class→handle path already handled by project-class-slots.
   Non-EDN non-Class value → sentinel (preserving form metadata)."
  [v]
  (if (edn-safe? v)
    v
    (let [s (nonedn-sentinel v)
          m (meta v)]
      (if m (with-meta s m) s))))

(defn- project-raw-forms
  "Deep-walk a :raw-forms structure (raw pre-macroexpansion source forms),
   sentinelling non-EDN leaves (e.g. regex Patterns) while preserving the
   surrounding structure the host's unanalyze/raw-form-value walks over. Uses
   `prewalk` so descent is decided top-down: a defrecord or an analyzer AST node
   (map with :op) is sentinelled into an opaque leaf BEFORE recursion, so its
   `:env` back-refs / cycles are never entered. (`postwalk` cannot do this — it
   recurses into every collection before the fn runs.)"
  [raw-forms]
  (walk/prewalk (fn [x]
                  (if (and (coll? x)
                           (not (instance? clojure.lang.IRecord x))
                           (not (ast-node? x)))
                    x
                    (project-val-form x)))
                raw-forms))

(defn- project-class-slot
  "If `v` is a ^Class, replace it with a UUID handle (bootstrap classes
   short-circuit through the integer cache) and return [handle display-name].
   Otherwise return [v nil]."
  [v]
  (if (class? v)
    [(intern-class! ^Class v :uuid) (.getName ^Class v)]
    [v nil]))

(def ^:private class-slot-keys
  {:class :class-display-name
   :tag :tag-display-name
   :val :val-display-name})

(defn- project-class-slots
  "Replace any ^Class in `:class`/`:tag`/`:val` of `n` with a handle and
   populate the sibling display-name field. For :val/:form, apply sentinel
   to non-EDN non-Class values."
  [n]
  (let [n' (reduce-kv (fn [acc k disp-k]
                        (if (contains? acc k)
                          (let [[h disp] (project-class-slot (get acc k))]
                            (cond-> (assoc acc k h)
                              disp (assoc disp-k disp)))
                          acc))
                      n
                      class-slot-keys)]
    (cond-> n'
      (contains? n' :form) (update :form project-val-form)
      (and (contains? n' :val) (not (class? (:val n)))) (update :val project-val-form)
      (contains? n' :raw-forms) (update :raw-forms project-raw-forms))))

(def ^:private core-fn-class->symbol
  {"clojure.core$integer_QMARK_" 'clojure.core/integer?
   "clojure.core$int_QMARK_" 'clojure.core/int?
   "clojure.core$string_QMARK_" 'clojure.core/string?
   "clojure.core$keyword_QMARK_" 'clojure.core/keyword?
   "clojure.core$symbol_QMARK_" 'clojure.core/symbol?
   "clojure.core$boolean_QMARK_" 'clojure.core/boolean?
   "clojure.core$fn_QMARK_" 'clojure.core/fn?
   "clojure.core$nil_QMARK_" 'clojure.core/nil?
   "clojure.core$number_QMARK_" 'clojure.core/number?
   "clojure.core$pos_QMARK_" 'clojure.core/pos?
   "clojure.core$neg_QMARK_" 'clojure.core/neg?})

(def ^:private core-fn-class-fragments
  [["clojure.core$integer_QMARK_" 'clojure.core/integer?]
   ["clojure.core$int_QMARK_" 'clojure.core/int?]
   ["clojure.core$string_QMARK_" 'clojure.core/string?]
   ["clojure.core$keyword_QMARK_" 'clojure.core/keyword?]
   ["clojure.core$symbol_QMARK_" 'clojure.core/symbol?]
   ["clojure.core$boolean_QMARK_" 'clojure.core/boolean?]
   ["clojure.core$fn_QMARK_" 'clojure.core/fn?]
   ["clojure.core$nil_QMARK_" 'clojure.core/nil?]
   ["clojure.core$number_QMARK_" 'clojure.core/number?]
   ["clojure.core$pos_QMARK_" 'clojure.core/pos?]
   ["clojure.core$neg_QMARK_" 'clojure.core/neg?]])

(defn- core-fn-symbol
  [class-name]
  (or (get core-fn-class->symbol class-name)
      (some (fn [[fragment sym]]
              (when (str/includes? class-name fragment) sym))
            core-fn-class-fragments)))

(defn- intern-fn!
  [f]
  (let [{:keys [fn->id]} @fn-handle-state]
    (or (get fn->id f)
        (let [new-id (-> (swap! fn-handle-state update :next-id inc)
                         :next-id)]
          (swap! fn-handle-state
                 (fn [st]
                   (-> st
                       (assoc-in [:id->fn new-id] f)
                       (assoc-in [:fn->id f] new-id))))
          new-id))))

(declare encode-schema-value)

(defn- encode-fn
  [f]
  (let [class-name (.getName (class f))]
    (if-let [sym (core-fn-symbol class-name)]
      {:tag :fn :sym sym}
      {:tag :fn :handle (intern-fn! f) :display-name class-name})))

(defn- encode-schema-field
  [active value]
  (cond
    (fn? value) (encode-fn value)
    :else (encode-schema-value active value)))

(defn- encode-record-fields
  [active schema]
  (into {}
        (map (fn [[k v]] [k (encode-schema-field active v)]))
        (into {} schema)))

(defn- encode-var
  [active ^clojure.lang.Var v]
  (let [m (meta v)
        qsym (when (and (:ns m) (:name m))
               (symbol (str (ns-name (:ns m)) "/" (:name m))))]
    (cond-> {:tag :var-ref :qualified-sym qsym}
      (and qsym (not (contains? active qsym)) (bound? v))
      (assoc :schema (encode-schema-value (conj active qsym) @v)))))

(defn- instance-field
  [x field-name]
  (let [f (.getDeclaredField (class x) field-name)]
    (.setAccessible f true)
    (.get f x)))

(defn- encode-map-schema
  [active m]
  {:tag :map
   :entries (mapv (fn [[k v]]
                    [(encode-schema-value active k)
                     (encode-schema-value active v)])
                  m)})

(defn- encode-schema-value
  [active value]
  (cond
    (nil? value) {:tag :nil}
    (or (instance? Boolean value) (number? value) (string? value) (keyword? value) (symbol? value))
    {:tag :literal :value value}

    (class? value)
    (let [[handle display-name] (project-class-slot value)]
      {:tag :class :handle handle :display-name display-name})

    (instance? java.util.regex.Pattern value)
    {:tag :regex
     :pattern (.pattern ^java.util.regex.Pattern value)
     :flags (.flags ^java.util.regex.Pattern value)}

    (instance? clojure.lang.Var value)
    (encode-var active value)

    (instance? clojure.lang.Var$Unbound value)
    (encode-var active (instance-field value "v"))

    (and (record? value) (not (ast-node? value)))
    {:tag :record
     :class (.getName (class value))
     :fields (encode-record-fields active value)}

    (map? value)
    (encode-map-schema active value)

    (vector? value)
    {:tag :vector :items (mapv #(encode-schema-value active %) value)}

    (set? value)
    {:tag :set :items (mapv #(encode-schema-value active %) value)}

    (seq? value)
    {:tag :seq :items (mapv #(encode-schema-value active %) value)}

    (fn? value)
    (encode-fn value)

    :else
    (throw (ex-info "Unsupported Plumatic schema value on worker wire"
                    {:class (.getName (class value))
                     :value (pr-str value)}))))

(defn- opaque-var?
  [v]
  (boolean (-> v meta :skeptic/opaque)))

(defn- ignore-body-var?
  [v]
  (let [m (meta v)]
    (boolean (or (:skeptic/ignore-body m) (:skeptic/opaque m)))))

(def ^:private plumatic-declaration-heads
  '#{schema.core/def schema.core/defn schema.core/defschema})

(defn- qualify-source-head
  [ns-sym head]
  (when (symbol? head)
    (if-let [alias (some-> head namespace symbol)]
      (if-let [alias-ns (get (ns-aliases (the-ns ns-sym)) alias)]
        (symbol (str (ns-name alias-ns)) (name head))
        head)
      (symbol (name ns-sym) (name head)))))

(defn- plumatic-source-form?
  [ns-sym source-form]
  (contains? plumatic-declaration-heads
             (qualify-source-head ns-sym (first source-form))))

(defn- declared-var
  "The Var named by `declared-sym` interned in `ns-sym`, or nil. Uses the
   namespace's own intern table rather than `ns-resolve`: `ns-resolve` resolves a
   symbol to a Var *or a Class*, so a dotted declaration symbol (e.g. an `ns`
   form's name) routes through `Compiler/maybeResolveIn` -> `RT/classForName` and
   can throw `NoClassDefFoundError`. A declared var is always a simple-symbol key
   in the namespace's interns, so the intern lookup is both correct and free of
   any class resolution."
  [ns-sym declared-sym]
  (get (ns-interns (the-ns ns-sym)) declared-sym))

(defn- source-form-plumatic-schema
  [ns-sym source-form]
  (when (and (seq? source-form)
             (plumatic-source-form? ns-sym source-form)
             (symbol? (second source-form)))
    (let [declared-sym (second source-form)
          qualified-sym (symbol (name ns-sym) (name declared-sym))
          v (declared-var ns-sym declared-sym)]
      (when (and (var? v)
                 (not (:macro (meta v)))
                 (not (opaque-var? v))
                 (:schema (meta v)))
        {:qualified-sym qualified-sym
         :name (:name (meta v))
         :arglists (:arglists (meta v))
         :schema (encode-schema-value #{} (:schema (meta v)))
         :ignore-body? (ignore-body-var? v)}))))

(defn- source-form-schema-var-prov
  [ns-sym source-form]
  (when (and (seq? source-form) (symbol? (second source-form)))
    (let [declared-sym (second source-form)
          qualified-sym (symbol (name ns-sym) (name declared-sym))
          v (declared-var ns-sym declared-sym)]
      (when (and (var? v)
                 (not (:macro (meta v)))
                 (not (opaque-var? v))
                 (bound? v)
                 (not (fn? @v))
                 (not (or (instance? Boolean @v)
                          (number? @v)
                          (string? @v)
                          (keyword? @v)
                          (symbol? @v)))
                 (try
                   (encode-schema-value #{} @v)
                   true
                   (catch Throwable _ false)))
        qualified-sym))))

(defn- project-var-slot
  "Replace a clojure.lang.Var in :var with the qualified symbol ns/name."
  [n]
  (let [v (:var n)]
    (if (instance? clojure.lang.Var v)
      (assoc n :var (symbol (str (ns-name (.ns ^clojure.lang.Var v)))
                            (name (.sym ^clojure.lang.Var v))))
      n)))

(def ^:private explicitly-projected-slots
  "Slots handled by a dedicated projection step: class/value slots projected by
   project-class-slots, :var by project-var-slot, :meta and every :children slot
   by handle-project-node's recursion."
  #{:class :class-display-name :tag :tag-display-name
    :val :val-display-name :form :raw-forms :var :meta :children})

(defn- project-incidental-slot
  "Make one non-child, non-explicitly-projected slot value EDN-safe. These are
   analyzer-internal slots the host never reads (e.g. the reflected :methods /
   :bridges members tools.analyzer.jvm attaches to :static-call / :instance-call
   / :reify / :deftype / :method nodes, holding clojure.reflect.* records). EDN
   leaves pass through; non-EDN leaves (reflect records, etc.) become sentinels,
   so no analyzer slot can ride the wire raw and break the host's edn/read."
  [v]
  (if (edn-safe? v) v (project-raw-forms v)))

(defn- project-incidental-slots
  "Sentinel non-EDN leaves in every slot that is neither a child nor an
   explicitly-projected slot. Closes the projection's unsound default of
   shipping unrecognized slots raw."
  [n]
  (let [handled (into explicitly-projected-slots (:children n))]
    (reduce-kv (fn [acc k v]
                 (if (contains? handled k)
                   acc
                   (assoc acc k (project-incidental-slot v))))
               n
               n)))

(defn- project-child
  "Apply the runner `f` to a child slot: a single AST node, a vector of AST
   nodes, or pass the value through unchanged."
  [f v]
  (cond
    (ast-node? v) (f v)
    (and (vector? v) (seq v) (every? ast-node? v)) (mapv f v)
    :else v))

(defn- handle-project-node
  "Full projection runner. Strips host-unread slots, projects Class handles,
   sentinels non-EDN :val/:form, replaces :var with a qualified symbol, makes
   every remaining incidental (non-child, non-explicitly-projected) slot
   EDN-safe, then recurses into all child AST nodes (the :children keys plus
   :meta when it is an AST child). Sole locus of recursion; project-child is
   non-recursive."
  [n]
  (if-not (ast-node? n)
    n
    (let [n' (-> n strip-host-unread project-class-slots project-var-slot
                 project-incidental-slots)
          child-keys (into (vec (:children n'))
                           (when (ast-node? (:meta n')) [:meta]))
          descend (fn [a k] (assoc a k (project-child handle-project-node (get a k))))]
      (reduce descend n' child-keys))))

(defn- source-form-malli-schema
  "Channel-1 Malli spec (`:malli/schema`) read off the RAW `defn`/`s/defn`
   source-form, before projection. Both legal styles are covered: reader-meta
   `(defn ^{:malli/schema S} name …)` carries it on the name symbol's meta;
   attr-map `(defn name {:malli/schema S} …)` carries it in the map at form
   position 2. Returns nil when absent. Pure form/data inspection — no eval,
   no malli.core dependency; the spec is inert keyword/vector data."
  [source-form]
  (when (seq? source-form)
    (let [name-sym (second source-form)
          attr-map (nth source-form 2 nil)]
      (or (:malli/schema (meta name-sym))
          (when (map? attr-map) (:malli/schema attr-map))))))

(defn- project-entry
  "Project one clj analysis entry for the wire: AST via handle-project-node,
   source-form via the raw-forms sentinel walk (non-EDN body literals become
   sentinels). Form metadata (`:source`/`:line`/...) is captured into sibling
   vectors in postwalk order and replayed host-side (see
   `skeptic.worker.wire/capture-form-meta`). The channel-1 `:malli/schema` spec
   is captured here off the RAW source-form because the wire strips the symbol's
   reader-metadata (F-MALLISTRIP)."
  [ns-sym {:keys [source-form ast analysis-skipped?]}]
  (let [source-form' (project-raw-forms source-form)
        ast' (when ast
               (handle-project-node ast))
        source-form-meta (wire/capture-form-meta source-form')
        ast-meta (wire/capture-form-meta ast')
        malli-schema (source-form-malli-schema source-form)
        plumatic-schema (source-form-plumatic-schema ns-sym source-form)
        schema-var-prov (source-form-schema-var-prov ns-sym source-form)]
    (cond-> {:source-form (wire/strip-form-meta source-form')
             :source-form-meta source-form-meta
             :ast (wire/strip-form-meta ast')
             :ast-meta ast-meta}
      analysis-skipped? (assoc :analysis-skipped? true)
      malli-schema (assoc :malli-schema malli-schema)
      schema-var-prov (assoc :plumatic-var-prov schema-var-prov)
      plumatic-schema (assoc :plumatic-schema plumatic-schema))))

(defn- chain-messages
  "Each cause's message joined with ` <- `, deepest cause first. cljs.analyzer
   wraps the inner cause's real message inside an outer ExceptionInfo whose own
   `.getMessage` is nil; without walking the chain the host sees only nil."
  [^Throwable e]
  (->> (iterate #(.getCause ^Throwable %) e)
       (take-while some?)
       (map #(or (.getMessage ^Throwable %) (str %)))
       (remove str/blank?)
       distinct
       reverse
       (str/join " <- ")))

(defn- safe-pr-str
  "pr-str that never throws. Used for `ex-data` payloads which may carry values
   `pr-str` itself rejects (raw Class objects, Vars, regex Patterns, etc.). A
   throwing pr-str inside the wire-error path would shadow the original failure."
  [v]
  (try (pr-str v) (catch Throwable _ (str "<unprintable " (.getName (class v)) ">"))))

(defn- error-reply
  "Wire reply for a thrown exception. Carries `:status #{:error :done}` so the
   host MUST treat it as failure (wc/ask throws when `:exception-class` is
   present). The wire payload is `Throwable->map`'s pr-str — Clojure's standard
   EDN-readable exception serialization, carrying class + message + data + every
   cause link's class/message/data/at + the trace."
  [^Throwable e]
  {:status #{:error :done}
   :exception-class (.getName (class e))
   :exception-message (chain-messages e)
   :exception-via (safe-pr-str (Throwable->map e))})

(defn- project-cljs-entry
  "Project one cljs analysis entry. Like project-entry, but a cljs entry may
   carry an :exception (Throwable, non-EDN) instead of an :ast; ship its
   message string and drop the Throwable. Source-form metadata
   (`:source`/`:line`/...) is captured for the host to replay (locations on
   cljs findings depend on it); only the `:source-form` is walked — the cljs
   `:ast` is NEVER walked for meta capture (its `:env` back-refs run away)."
  [{:keys [source-form ast exception]}]
  (let [source-form' (project-raw-forms source-form)
        malli-schema (source-form-malli-schema source-form)
        base {:source-form (wire/strip-form-meta source-form')
              :source-form-meta (wire/capture-form-meta source-form')}]
    (if exception
      (assoc base
             :exception-message (or (.getMessage ^Throwable exception) (str exception))
             ;; Carry ex-data (and the inner cause's, since cljs.analyzer wraps)
             ;; as plain data so the host can branch on :clojure.error/phase
             ;; etc. without losing it through Throwable->wire serialization.
             :exception-data (safe-pr-str
                              (merge (some-> ^Throwable (.getCause ^Throwable exception) ex-data)
                                     (ex-data exception))))
      (let [ast' (handle-project-node ast)]
        (cond-> (assoc base
                       :ast (wire/strip-ast-form-meta ast')
                       :ast-form-meta (wire/capture-ast-form-meta ast'))
          malli-schema (assoc :malli-schema malli-schema))))))

(defn- analyze-cljs-namespace-reply
  [source-file]
  (let [{:keys [ns-ast entries]} (with-project-operation
                                   (wac-cljs/analyze-source-file (io/file source-file)))]
    {:ns-ast (wire/strip-ast-form-meta (handle-project-node ns-ast))
     :entries (mapv project-cljs-entry entries)}))

(defn- cljs-ns-head-reply
  [source-file]
  (with-project-operation
    (wac-cljs/ns-head (io/file source-file))))

(defn- handle-loopback-message
  [{:keys [op class-names rel a b triples ns sym source-file handle arg namespaces]}]
  (case op
    "intern-host-classes" {:handles (intern-host-classes-reply class-names)}
    "class-rel" {:result (run-class-rel rel a b)}
    "class-rel-batch" {:results (run-class-rel-batch triples)}
    "resolve-class-sym" {:handle (resolve-sym-to-handle ns sym)}
    "class-name" {:name (class-name-for-handle a)}
    "analyze-namespace" (analyze-namespace-reply ns source-file)
    "analyze-namespaces-stream"
    (mapv (fn [[ns-str source-file-str]]
            (try
              (let [{:keys [entries read-failure]}
                    (analyze-namespace-reply ns-str source-file-str)]
                (cond-> {:ns-sym ns-str :source-file source-file-str}
                  entries      (assoc :entries entries)
                  read-failure (assoc :read-failure read-failure)))
              (catch Throwable e
                {:ns-sym ns-str
                 :source-file source-file-str
                 :load-error (.getName (class e))
                 :load-error-message (or (.getMessage e) (str e))})))
          namespaces)
    "apply-predicate" (let [pred (or (get-in @fn-handle-state [:id->fn handle])
                                      (throw (ex-info (str "Unknown predicate handle: " handle)
                                                      {:handle handle})))]
                        {:result (with-project-operation (boolean (pred arg)))})
    (throw (ex-info (str "Unsupported worker loopback op: " op) {:op op}))))

(defn- worker-log
  "Worker-side startup marker. Goes to stdout because both launch paths drain
   that stream: direct launch reads the port handshake there, while the Lein
   launcher captures it independently from porcelain output. Each host
   surfaces the line on stderr under `-v`. Unconditional on the worker side:
   the worker has no flag to honor."
  [label]
  (println (str "WORKER " label))
  (flush))

(defn start!
  "Lazy-load nREPL, then start a server with an explicit handler chain. Every
   nREPL Var (`t/send`, `response-for`, `srv/start-server`,
   `srv/unknown-op`-equivalent) is resolved here from the worker JVM's launch
   classloader (project-first) so a project pinning a different nrepl wins
   via first-occurrence. Wrap-* dispatchers are let-bound inside this fn —
   they close over locally resolved `t-send` and `resp-for`, never reference
   a namespace-scoped alias. The shutdown callback is mandatory so a successful
   shutdown reply always corresponds to an actual server stop request."
  [request-shutdown!]
  (worker-log "requiring nrepl.transport")
  (require 'nrepl.transport)
  (worker-log "requiring nrepl.server")
  (require 'nrepl.server)
  (worker-log "requiring nrepl.misc")
  (require 'nrepl.misc)
  (let [t-send (resolve 'nrepl.transport/send)
        resp-for (resolve 'nrepl.misc/response-for)
        start-server (resolve 'nrepl.server/start-server)
        send-reply!
        (fn [transport msg reply]
          (try
            (t-send transport (resp-for msg reply))
            (catch Throwable send-err
              (try
                (t-send transport (resp-for msg (error-reply send-err)))
                (catch Throwable final-err
                  (println (str "WORKER send-reply!: both primary and error-reply sends failed. "
                                "Primary: " (.getName (class send-err)) ": " (.getMessage send-err)
                                ". Error-reply: " (.getName (class final-err)) ": " (.getMessage final-err)))
                  (flush)
                  (throw final-err))))))
        send-reply-or-error*
        (fn [transport msg body-fn]
          (let [reply (try (body-fn) (catch Throwable e (error-reply e)))]
            (send-reply! transport msg reply)))
        unknown-op
        (fn [{:keys [op transport] :as msg}]
          (t-send transport (resp-for msg :status #{:error :unknown-op :done} :op op)))
        wrap-ping
        (fn [h]
          (fn [{:keys [op transport] :as msg}]
            (if (= op "ping")
              (t-send transport (resp-for msg :pong "ok" :status #{:done}))
              (h msg))))
        wrap-shutdown
        (fn [h]
          (fn [{:keys [op transport] :as msg}]
            (if (= op "shutdown")
              (do
                (t-send transport (resp-for msg :status #{:done}))
                (request-shutdown!))
              (h msg))))
        wrap-intern-host-classes
        (fn [h]
          (fn [{:keys [op transport class-names] :as msg}]
            (if (= op "intern-host-classes")
              (send-reply-or-error* transport msg
                                    (fn []
                                      {:handles (intern-host-classes-reply class-names)
                                       :status #{:done}}))
              (h msg))))
        wrap-class-rel
        (fn [h]
          (fn [{:keys [op transport rel a b] :as msg}]
            (if (= op "class-rel")
              (send-reply-or-error* transport msg
                                    (fn []
                                      {:result (run-class-rel rel a b) :status #{:done}}))
              (h msg))))
        wrap-class-rel-batch
        (fn [h]
          (fn [{:keys [op transport triples] :as msg}]
            (if (= op "class-rel-batch")
              (send-reply-or-error* transport msg
                                    (fn []
                                      {:results (run-class-rel-batch triples) :status #{:done}}))
              (h msg))))
        wrap-resolve-class-sym
        (fn [h]
          (fn [{:keys [op transport ns sym] :as msg}]
            (if (= op "resolve-class-sym")
              (send-reply-or-error* transport msg
                                    (fn []
                                      {:handle (resolve-sym-to-handle ns sym) :status #{:done}}))
              (h msg))))
        wrap-class-name
        (fn [h]
          (fn [{:keys [op transport a] :as msg}]
            (if (= op "class-name")
              (send-reply-or-error* transport msg
                                    (fn []
                                      {:name (class-name-for-handle a) :status #{:done}}))
              (h msg))))
        wrap-analyze-namespace
        (fn [h]
          (fn [{:keys [op transport ns source-file] :as msg}]
            (if (= op "analyze-namespace")
              (send-reply-or-error* transport msg
                                    (fn []
                                      (let [{:keys [entries read-failure]}
                                            (analyze-namespace-reply ns source-file)]
                                        (cond-> {:status #{:done}}
                                          entries (assoc :entries entries)
                                          read-failure (assoc :read-failure read-failure)))))
              (h msg))))
        stream-namespace!
        (fn [transport msg [ns-str source-file-str]]
          (try
            (send-reply! transport msg {:ns-sym ns-str
                                        :source-file source-file-str
                                        :starting? true})
            (let [reply (try
                          (let [{:keys [entries read-failure]}
                                (analyze-namespace-reply ns-str source-file-str)]
                            (cond-> {:ns-sym ns-str :source-file source-file-str}
                              entries      (assoc :entries entries)
                              read-failure (assoc :read-failure read-failure)))
                          (catch Throwable e
                            {:ns-sym ns-str
                             :source-file source-file-str
                             :load-error (.getName (class e))
                             :load-error-message (chain-messages e)}))]
              (send-reply! transport msg reply))
            (catch Throwable e
              (println (str "WORKER analyze-namespaces-stream: aborting stream at " ns-str
                            ": " (.getName (class e)) ": " (.getMessage e)))
              (flush)
              (throw e))))
        wrap-analyze-namespaces-stream
        (fn [h]
          (fn [{:keys [op transport namespaces] :as msg}]
            (if (= op "analyze-namespaces-stream")
              (do
                (doseq [pair namespaces]
                  (stream-namespace! transport msg pair))
                (send-reply! transport msg {:status #{:done}}))
              (h msg))))
        wrap-analyze-cljs-namespace
        (fn [h]
          (fn [{:keys [op transport source-file] :as msg}]
            (if (= op "analyze-cljs-namespace")
              (send-reply-or-error* transport msg
                                    (fn []
                                      (let [{:keys [ns-ast entries]}
                                            (analyze-cljs-namespace-reply source-file)]
                                        {:status #{:done} :ns-ast ns-ast :entries entries})))
              (h msg))))
        wrap-cljs-ns-head
        (fn [h]
          (fn [{:keys [op transport source-file] :as msg}]
            (if (= op "cljs-ns-head")
              (send-reply-or-error* transport msg
                                    (fn []
                                      (let [{:keys [name requires require-macros use-macros]}
                                            (cljs-ns-head-reply source-file)]
                                        {:status #{:done}
                                         :name name
                                         :requires requires
                                         :require-macros require-macros
                                         :use-macros use-macros})))
              (h msg))))
        wrap-apply-predicate
        (fn [h]
          (fn [{:keys [op transport handle arg] :as msg}]
            (if (= op "apply-predicate")
              (send-reply-or-error* transport msg
                                    (fn []
                                      (let [pred (or (get-in @fn-handle-state [:id->fn handle])
                                                     (throw (ex-info (str "Unknown predicate handle: " handle)
                                                                     {:handle handle})))]
                                        {:status #{:done}
                                         :result (with-project-operation (boolean (pred arg)))})))
              (h msg))))
        handler (-> unknown-op
                    wrap-shutdown
                    wrap-apply-predicate
                    wrap-cljs-ns-head
                    wrap-analyze-cljs-namespace
                    wrap-analyze-namespaces-stream
                    wrap-analyze-namespace
                    wrap-class-name
                    wrap-resolve-class-sym
                    wrap-class-rel-batch
                    wrap-class-rel
                    wrap-intern-host-classes
                    wrap-ping)
        _ (worker-log "starting nrepl server on port 0 (transit transport)")
        server (start-server :port 0
                             ;; worker output is drained by the host and only
                             ;; forwarded under -v, so verbose? is always true here
                             :transport-fn #(worker-transport/transit true %)
                             :handler handler)
        _ (worker-log (str "nrepl server bound port=" (:port server)))]
    server))

(def ^:private shutdown-marker ::shutdown)

(defn- install-project-operation-queue!
  []
  (let [queue (LinkedBlockingQueue.)]
    (reset! project-operation-submit
            (fn [f]
              (let [reply (promise)]
                (.put queue [f reply])
                (let [{:keys [value error]} @reply]
                  (if error
                    (throw error)
                    value)))))
    queue))

(defn- run-project-operation-loop!
  [^LinkedBlockingQueue queue]
  (binding [*project-operation-thread* true]
    (loop []
      (let [request (.take queue)]
        (when-not (= shutdown-marker request)
          (let [[f reply] request]
            (try
              (deliver reply {:value (f)})
              (catch Throwable e
                (deliver reply {:error e}))))
          (recur))))))

(defn run-worker!
  "Start the worker and run the project-operation loop on this thread — the
   `clojure.main` launch thread the project's own boot set up, where lein's
   prep, `:global-vars`, and `:injections` already ran. Project code loads
   exactly as the project's own runtime loads it: Skeptic adds no reader-Var
   machinery, so every registration mechanism behaves as it does under the
   project's own `clojure.main` (observed in `.scratch/reader-probes/`).
   nREPL handler threads enqueue project operations onto this thread's queue.
   `announce-port!` is called with the bound port once the server is up: the
   owned Lein subprocess writes a port-file, direct launch prints the stdout
   handshake line."
  [announce-port!]
  (worker-log "run-worker! entered")
  (let [queue (install-project-operation-queue!)
        request-shutdown! #(.put queue shutdown-marker)
        server (start! request-shutdown!)]
    (announce-port! (:port server))
    (try
      (run-project-operation-loop! queue)
      (finally
        (reset! project-operation-submit nil)
        ((resolve 'nrepl.server/stop-server) server)))))

(defn -main
  [& _args]
  (run-worker! (fn [port]
                 (println (str "SKEPTIC-WORKER-PORT " port))
                 (flush))))
