(ns skeptic.analysis.class-oracle
  "Host-side wrappers around the worker's class oracle. Every `:class` slot on
   the host carries an opaque handle: an integer for bootstrap host-runtime
   classes (interned at connect-time via `intern-host-classes!`), or a UUID
   string for project classes (minted by the worker on `resolve-class-sym`).
   Host NEVER calls `Class/forName`, `.isAssignableFrom`, `instance?`, or
   class-equality on a project class. Every relation routes through the
   worker via `class-rel`."
  (:require [schema.core :as s]
            [skeptic.worker.client :as wc])
  (:import [clojure.lang BigInt Keyword LazySeq Numbers Ratio RT Symbol Util IPersistentCollection]
           [java.math BigDecimal BigInteger]))

(def ^:dynamic *worker-conn*
  "Bound by `check-project` to the live worker connection. All RPCs below
   read from this var; nil means the host has no worker (test scaffolding only)."
  nil)

(def ^:dynamic *class-rel-cache*
  "Per-run memo for the pure oracle relations, keyed by request. Sound because
   the worker's handle table is additive (handles never re-point), so
   `:equals`/`:assignable-from`/`class-name`/`resolve-class-sym` are pure
   functions of their handles for the life of the worker. Bound to one atom per
   run by `check-project`; the default fresh atom keeps the oracle correct (just
   un-shared) when no binding is installed. `:instance?` is never cached."
  (atom {}))

(defn current-cache
  "The currently-bound per-run cache atom. Pipeline sites reuse this when a run
   has already installed one, rather than shadowing it with a fresh atom."
  []
  *class-rel-cache*)

(defn- cached-rel
  "Returns the cached value for `k`, else runs `thunk`, caches its result under
   `k`, and returns it."
  [k thunk]
  (let [cache @*class-rel-cache*]
    (if (contains? cache k)
      (get cache k)
      (let [v (thunk)]
        (swap! *class-rel-cache* assoc k v)
        v))))

(def bootstrap-class-names
  "The closed set of host-runtime classes whose names are guaranteed unique by
   the JVM bootloader rules (D12). The host imports each one; the worker also
   loads each one at connect-time. After `intern-host-classes!` these get
   integer handles in *host-class-handles*."
  ["java.lang.Number"
   "java.lang.Object"
   "java.lang.Class"
   "java.lang.Float"
   "java.lang.Double"
   "java.lang.Long"
   "java.lang.Integer"
   "java.lang.Short"
   "java.lang.Byte"
   "java.lang.String"
   "java.lang.Boolean"
   "clojure.lang.Keyword"
   "clojure.lang.Symbol"
   "clojure.lang.RT"
   "clojure.lang.Util"
   "clojure.lang.LazySeq"
   "clojure.lang.Numbers"
   "clojure.lang.IPersistentCollection"
   "java.math.BigDecimal"
   "java.math.BigInteger"
   "clojure.lang.BigInt"
   "clojure.lang.Ratio"
   "boolean"
   "int"
   "long"
   "short"
   "byte"
   "double"
   "float"])

(def ^:private host-class-imports
  "Map from host-imported ^Class to its canonical name. Used to join the
   worker's reply (`{name handle}`) back into a host-keyed map (`{Class handle}`)."
  {Number "java.lang.Number"
   Object "java.lang.Object"
   Class "java.lang.Class"
   Float "java.lang.Float"
   Double "java.lang.Double"
   Long "java.lang.Long"
   Integer "java.lang.Integer"
   Short "java.lang.Short"
   Byte "java.lang.Byte"
   String "java.lang.String"
   Boolean "java.lang.Boolean"
   Keyword "clojure.lang.Keyword"
   Symbol "clojure.lang.Symbol"
   RT "clojure.lang.RT"
   Util "clojure.lang.Util"
   LazySeq "clojure.lang.LazySeq"
   Numbers "clojure.lang.Numbers"
   IPersistentCollection "clojure.lang.IPersistentCollection"
   BigDecimal "java.math.BigDecimal"
   BigInteger "java.math.BigInteger"
   BigInt "clojure.lang.BigInt"
   Ratio "clojure.lang.Ratio"
   Boolean/TYPE "boolean"
   Integer/TYPE "int"
   Long/TYPE "long"
   Short/TYPE "short"
   Byte/TYPE "byte"
   Double/TYPE "double"
   Float/TYPE "float"})

(def ^:dynamic *host-class-handles*
  "Bound by `check-project` after the bootstrap RPC. Map from host-imported
   ^Class to its integer handle. Lookup via `host-handle`."
  {})

(s/defn intern-host-classes! :- {Class s/Any}
  "Asks the worker to intern every bootstrap class. Returns a host-keyed map
   `{^Class handle-id}` suitable for binding into *host-class-handles*."
  [conn :- s/Any]
  (let [{:keys [handles]} (wc/ask conn {:op "intern-host-classes"
                                        :class-names bootstrap-class-names})]
    (reduce-kv (fn [acc cls nm]
                 (if-let [h (get handles nm)]
                   (assoc acc cls h)
                   acc))
               {} host-class-imports)))

(s/defn host-handle :- s/Any
  "Returns the integer handle for `c` if `c` is a bootstrap-interned host
   runtime class, else nil. Cheap host-local lookup; no RPC."
  [^Class c]
  (get *host-class-handles* c))

(s/defn handle? :- s/Bool
  "Discriminator: is `v` an opaque class handle? Integer (bootstrap) or
   UUID-shaped string (project)."
  [v :- s/Any]
  (boolean (or (integer? v)
               (and (string? v) (re-matches #"[0-9a-f-]{36}" v)))))

(s/defn class-rel :- s/Any
  "Routes a class relation to the worker. `a` is always a handle. For
   `:instance?` `b` is a runtime value (worker uses it directly); otherwise
   `b` is a handle. Returns boolean."
  [rel :- s/Keyword a :- s/Any b :- s/Any]
  (let [ask-rel #(:result (wc/ask *worker-conn* {:op "class-rel" :rel rel :a a :b b}))]
    (if (= rel :instance?)
      (ask-rel)
      (cached-rel [rel a b] ask-rel))))

(s/defn class-rel-batch :- [s/Any]
  "Answers a vector of `{:rel :a :b}` triples, returning a vector of booleans
   positionally matching `triples`. Cache hits never hit the wire; only the
   uncached triples are sent in one `class-rel-batch` round-trip, then cached."
  [triples :- [{s/Keyword s/Any}]]
  (let [cache @*class-rel-cache*
        key-of (fn [{:keys [rel a b]}] [rel a b])
        misses (vec (remove #(contains? cache (key-of %)) triples))
        results (when (seq misses)
                  (:results (wc/ask *worker-conn* {:op "class-rel-batch" :triples misses})))
        miss->result (zipmap (map key-of misses) results)]
    (doseq [[k v] miss->result] (swap! *class-rel-cache* assoc k v))
    (mapv (fn [t] (let [k (key-of t)]
                    (if (contains? cache k) (get cache k) (get miss->result k))))
          triples)))

(s/defn resolve-class-sym :- s/Any
  "Asks the worker to resolve `sym` in `ns-name` to a Class. Returns a UUID
   handle on success, nil otherwise. Host never sees the Class."
  [ns-name :- s/Symbol sym :- s/Symbol]
  (cached-rel [:resolve (str ns-name) (str sym)]
              #(:handle (wc/ask *worker-conn* {:op "resolve-class-sym"
                                               :ns (str ns-name)
                                               :sym (str sym)}))))

(s/defn class-handle :- s/Any
  "A worker-recognized handle for a host-held `^Class`. Bootstrap classes use
   the cheap host-local handle; any other class is resolved by name through the
   worker so the returned handle is comparable via `class-rel`. nil only if the
   worker cannot resolve the name."
  [^Class c]
  (or (host-handle c)
      (resolve-class-sym 'clojure.core (symbol (.getName c)))))

(s/defn class-name :- s/Any
  "Asks the worker for the canonical name of the Class behind handle `a`.
   Returns the name string, nil if `a` is nil/uninterned. The worker holds the
   Class; the host never calls `.getName` on a project class."
  [a :- s/Any]
  (when a
    (cached-rel [:class-name a]
                #(:name (wc/ask *worker-conn* {:op "class-name" :a a})))))
