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
  (:import [clojure.lang BigInt LazySeq Numbers Ratio RT Util IPersistentCollection]
           [java.math BigDecimal BigInteger]))

(def ^:dynamic *worker-conn*
  "Bound by `check-project` to the live worker connection. All RPCs below
   read from this var; nil means the host has no worker (test scaffolding only)."
  nil)

(def bootstrap-class-names
  "The closed set of host-runtime classes whose names are guaranteed unique by
   the JVM bootloader rules (D12). The host imports each one; the worker also
   loads each one at connect-time. After `intern-host-classes!` these get
   integer handles in *host-class-handles*."
  ["java.lang.Number"
   "java.lang.Object"
   "java.lang.Class"
   "java.lang.Float"
   "clojure.lang.RT"
   "clojure.lang.Util"
   "clojure.lang.LazySeq"
   "clojure.lang.Numbers"
   "clojure.lang.IPersistentCollection"
   "java.math.BigDecimal"
   "java.math.BigInteger"
   "clojure.lang.BigInt"
   "clojure.lang.Ratio"])

(def ^:private host-class-imports
  "Map from host-imported ^Class to its canonical name. Used to join the
   worker's reply (`{name handle}`) back into a host-keyed map (`{Class handle}`)."
  {Number "java.lang.Number"
   Object "java.lang.Object"
   Class "java.lang.Class"
   Float "java.lang.Float"
   RT "clojure.lang.RT"
   Util "clojure.lang.Util"
   LazySeq "clojure.lang.LazySeq"
   Numbers "clojure.lang.Numbers"
   IPersistentCollection "clojure.lang.IPersistentCollection"
   BigDecimal "java.math.BigDecimal"
   BigInteger "java.math.BigInteger"
   BigInt "clojure.lang.BigInt"
   Ratio "clojure.lang.Ratio"})

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
  (:result (wc/ask *worker-conn* {:op "class-rel" :rel rel :a a :b b})))

(s/defn resolve-class-sym :- s/Any
  "Asks the worker to resolve `sym` in `ns-name` to a Class. Returns a UUID
   handle on success, nil otherwise. Host never sees the Class."
  [ns-name :- s/Symbol sym :- s/Symbol]
  (:handle (wc/ask *worker-conn* {:op "resolve-class-sym"
                                  :ns (str ns-name)
                                  :sym (str sym)})))
