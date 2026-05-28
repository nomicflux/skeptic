(ns skeptic.worker.wire
  "Host-safe wire-contract constants shared by both JVMs. Holds ONLY the keys
   and accessors for values that cross the worker->host AST boundary; carries no
   nREPL, tools.analyzer, or other worker-classpath dependency, so the host may
   require it without re-coupling to worker-only code.

   The non-EDN sentinel: a raw analyzer-AST `:val`/`:form`/`:raw-forms` leaf that
   is not EDN-readable (regex Pattern, fn object, Var, Namespace) is shipped as
   `{::nonedn true ::class <class-handle>}`. The host types it by its class via
   the carried handle; it never inspects the original value.")

(defn nonedn-sentinel
  "Build a non-EDN sentinel carrying the opaque class handle `class-handle`."
  [class-handle]
  {::nonedn true ::class class-handle})

(defn nonedn?
  "True when `v` is a non-EDN sentinel map."
  [v]
  (and (map? v) (true? (::nonedn v))))

(defn nonedn-class
  "The class handle carried by a non-EDN sentinel."
  [v]
  (::class v))
