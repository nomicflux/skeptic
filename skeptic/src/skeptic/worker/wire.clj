(ns skeptic.worker.wire
  "Host-safe wire-contract constants shared by both JVMs. Holds ONLY the keys
   and accessors for values that cross the worker->host AST boundary; carries no
   nREPL, tools.analyzer, or other worker-classpath dependency, so the host may
   require it without re-coupling to worker-only code.

   The non-EDN sentinel: a raw analyzer-AST `:val`/`:form`/`:raw-forms` leaf that
   is not EDN-readable (regex Pattern, fn object, Var, Namespace) is shipped as
   `{::nonedn true ::class <class-handle>}`. The host types it by its class via
   the carried handle; it never inspects the original value.

   Form metadata: the worker captures the host-read meta keys off each form into
   a plain data vector in `clojure.walk/postwalk` order (`capture-form-meta`);
   the host replays them onto the structurally-identical received form in the
   same order (`apply-form-meta`). Shape is never altered, so structural form
   walks survive."
  (:require [clojure.walk :as walk]))

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

(def form-meta-keys
  "The form-metadata keys the host reads back off received `:form`/`:source-form`
   values: source location (`node-location`/`form-location`), the raw source text
   (`form-source`), and the user `^{:skeptic/type T}` override (annotate)."
  [:file :line :column :end-line :end-column :source :skeptic/type])

(defn- selected-meta
  [x]
  (when (instance? clojure.lang.IObj x)
    (not-empty (select-keys (meta x) form-meta-keys))))

(defn capture-form-meta
  "Walk `form` in postwalk order, collecting each node's host-read metadata (or
   nil) into a plain EDN vector. Pairs with `apply-form-meta`, which replays the
   vector onto a structurally-identical form in the same order."
  [form]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (vswap! acc conj (selected-meta x)) x) form)
    @acc))

(defn apply-form-meta
  "Replay `metas` (from `capture-form-meta`) onto `form` in postwalk order,
   `with-meta`-ing each IObj node that had captured metadata. `form` must be the
   same shape `capture-form-meta` saw, so the postwalk visit order matches."
  [form metas]
  (let [idx (volatile! 0)]
    (walk/postwalk
     (fn [x]
       (let [m (nth metas @idx nil)]
         (vswap! idx inc)
         (if (and m (instance? clojure.lang.IObj x))
           (with-meta x (merge (meta x) m))
           x)))
     form)))

(defn strip-form-meta
  "Drop metadata from every IObj in `form` after it has been captured into a
   sidecar. Nippy preserves metadata, so the worker must make the sidecar the
   only metadata channel."
  [form]
  (walk/postwalk
   (fn [x]
     (if (instance? clojure.lang.IObj x)
       (with-meta x nil)
       x))
   form))

(defn- ast-node?
  "True when `v` is a tools.analyzer AST node (a plain map carrying `:op`).
   Sorted maps are excluded: `(contains? v :op)` throws on a `PersistentTreeMap`
   keyed by non-keyword-comparable keys (e.g. a `case*` dispatch map). AST nodes
   are always plain hash-maps, never sorted."
  [v]
  (and (map? v) (not (sorted? v)) (contains? v :op)))

(defn- ast-child-keys
  "The slots `handle-project-node` recurses through, in its order: every
   `:children` key, then `:meta` when it is itself an AST node."
  [n]
  (into (vec (:children n))
        (when (ast-node? (:meta n)) [:meta])))

(defn- walk-ast-spine
  "Apply `f` to `n`, then descend each child slot (single node or vector of
   nodes) in `handle-project-node` order — `:children` keys ONLY (plus an AST
   `:meta`). NEVER touches `:env`/`:info`/back-ref slots, so a cljs AST does not
   run away. Returns the rebuilt node (`f` may return an updated node)."
  [f n]
  (let [n' (f n)
        descend-slot (fn [v]
                       (cond
                         (ast-node? v) (walk-ast-spine f v)
                         (and (vector? v) (seq v) (every? ast-node? v))
                         (mapv #(walk-ast-spine f %) v)
                         :else v))]
    (reduce (fn [a k] (assoc a k (descend-slot (get a k))))
            n'
            (ast-child-keys n'))))

(defn capture-ast-form-meta
  "Collect each AST node's `(meta (:form node))` (host-read keys only) into a
   plain EDN vector, visiting nodes along the `:children` spine in
   `handle-project-node` order. Pairs with `apply-ast-form-meta`. The form
   metadata of cljs AST nodes carries the source location `node-location`
   reads; capturing it along the safe `:children` spine avoids the runaway full
   AST walk and keeps the host-side contract explicit."
  [ast]
  (let [acc (volatile! [])]
    (walk-ast-spine (fn [n]
                      (vswap! acc conj
                              (when (instance? clojure.lang.IObj (:form n))
                                (not-empty (select-keys (meta (:form n)) form-meta-keys))))
                      n)
                    ast)
    @acc))

(defn apply-ast-form-meta
  "Replay `metas` (from `capture-ast-form-meta`) onto each AST node's `:form`
   along the same `:children` spine, in the same order, `with-meta`-ing where a
   node carried captured metadata."
  [ast metas]
  (let [idx (volatile! 0)]
    (walk-ast-spine (fn [n]
                      (let [m (nth metas @idx nil)]
                        (vswap! idx inc)
                        (if (and m (instance? clojure.lang.IObj (:form n)))
                          (update n :form with-meta (merge (meta (:form n)) m))
                          n)))
                    ast)))

(defn strip-ast-form-meta
  "Drop metadata from each AST node's `:form` along the safe child spine. Used by
   cljs projection, where a full tree walk can touch analyzer back-refs."
  [ast]
  (walk-ast-spine (fn [n]
                    (cond-> n
                      (instance? clojure.lang.IObj (:form n))
                      (update :form with-meta nil)))
                  ast))
