(ns skeptic.worker.analyzer-cljs
  "Worker-side cljs analyzer execution. Mirrors the parse + reader-loop
   that live in `skeptic.cljs.analyzer-driver`, with no Skeptic / Schema /
   Malli dependency. The host-side source-file wrapper is rewired to issue a
   worker RPC instead of running the cljs analyzer locally.

   Wire payloads carry the source-file descriptor only; the cljs compiler state
   never crosses the wire.

   cljs.analyzer / cljs.analyzer.api / cljs.env / cljs.compiler are loaded
   lazily inside `with-analysis-bindings` so they intern from the project's
   pinned clojurescript version when present, not Skeptic's runtime-cp version
   at worker boot."
  (:require [clojure.java.io :as io]))

(defonce ^:private ensure-cljs-loaded!
  (delay
    (require 'cljs.env)
    (require 'cljs.analyzer)
    (require 'cljs.analyzer.api)
    (require 'cljs.compiler)
    true))

(defn- resolved-ana-vars
  []
  @ensure-cljs-loaded!
  {:*compiler*          (requiring-resolve 'cljs.env/*compiler*)
   :*file-defs*         (requiring-resolve 'cljs.analyzer/*file-defs*)
   :*unchecked-if*      (requiring-resolve 'cljs.analyzer/*unchecked-if*)
   :*unchecked-arrays*  (requiring-resolve 'cljs.analyzer/*unchecked-arrays*)
   :*analyze-deps*      (requiring-resolve 'cljs.analyzer/*analyze-deps*)
   :*load-macros*       (requiring-resolve 'cljs.analyzer/*load-macros*)
   :*cljs-ns*           (requiring-resolve 'cljs.analyzer/*cljs-ns*)
   :*cljs-file*         (requiring-resolve 'cljs.analyzer/*cljs-file*)
   :*cljs-warnings*     (requiring-resolve 'cljs.analyzer/*cljs-warnings*)
   :empty-state         (requiring-resolve 'cljs.analyzer.api/empty-state)
   :find-ns             (requiring-resolve 'cljs.analyzer.api/find-ns)
   :current-ns          (requiring-resolve 'cljs.analyzer.api/current-ns)
   :empty-env           (requiring-resolve 'cljs.analyzer.api/empty-env)
   :forms-seq           (requiring-resolve 'cljs.analyzer.api/forms-seq)
   :analyze             (requiring-resolve 'cljs.analyzer.api/analyze)
   :with-core-cljs      (requiring-resolve 'cljs.compiler/with-core-cljs)})

(defonce ^:private node-modules-provides
  ;; The project's own npm resolution substrate: the identical walk
  ;; `cljs.closure/handle-js-modules` feeds `:node-module-index` from
  ;; (closure.clj:2870-2916 — `index-node-modules-dir` is a pure JVM
  ;; file-seq + package.json parse, no Node invocation). Computed once per
  ;; worker; the worker's cwd is the project root by the spawn contract, so
  ;; the default \"node_modules\" path is the project's. Empty when the
  ;; project has none — then string requires are admitted only via the
  ;; per-ns-form seeding, and symbol/transitive npm requires fail exactly
  ;; as the project's own build would without installed modules.
  (delay
    (if (.isDirectory (io/file "node_modules"))
      (let [index-dir (requiring-resolve 'cljs.closure/index-node-modules-dir)]
        (into #{} (mapcat :provides) (index-dir {})))
      #{})))

(defn- empty-state
  [vars]
  (let [state ((:empty-state vars))]
    (swap! state assoc-in [:options :spec-skip-macros] true)
    (when-let [provides (seq @node-modules-provides)]
      (swap! state update :node-module-index (fnil into #{}) provides))
    state))

(defn- walk-ast
  [f ast]
  (let [ast' (f ast)]
    (reduce (fn [a k]
              (let [v (get a k)]
                (cond
                  (and (map? v) (contains? v :op))
                  (assoc a k (walk-ast f v))
                  (and (vector? v) (seq v) (every? #(and (map? %) (contains? % :op)) v))
                  (assoc a k (mapv #(walk-ast f %) v))
                  :else a)))
            ast'
            (:children ast'))))

(defn- ast-node?
  [v]
  (and (map? v) (contains? v :op)))

(defn- back-ref-slot?
  [children k v]
  (and (not (contains? children k))
       (not= :meta k)
       (or (ast-node? v)
           (and (vector? v) (seq v) (every? ast-node? v)))))

(defn- normalize-cljs-node
  [n]
  (let [children (set (:children n))
        n (reduce-kv (fn [acc k v] (if (back-ref-slot? children k v) (dissoc acc k) acc))
                     (dissoc n :type :env)
                     n)
        n (cond
            (not (contains? n :info)) n
            (= :var (:op n))          (update n :info select-keys [:name :meta])
            :else                     (dissoc n :info))
        n (if (and (= :fn (:op n)) (map? (:name n)))
            (assoc n :name (or (:form (:name n)) (:name (:name n))))
            n)]
    (if (and (= :binding (:op n)) (not (contains? n :form)))
      (assoc n :form (:name n))
      n)))

(defn- strip-cljs-type
  [ast]
  (walk-ast normalize-cljs-node ast))

(defn- no-warn-bindings
  "Inline expansion of `cljs.analyzer.api/no-warn` (probe artifact
   `probes-v2/cljs-sources/v1.12.134/api.cljc:62-67`). The macro reads
   `cljs.analyzer/*cljs-warnings*` at expansion time; we read its current
   value through the resolved Var at call time instead."
  [vars]
  (let [warnings-var (:*cljs-warnings* vars)]
    {warnings-var (zipmap (keys @warnings-var) (repeat false))}))

(defn- ns-form-string-requires
  "String libs in an ns form's `:require`/`:require-macros`/`:use` clauses —
   npm/js requires like `[\"react\" :as react]` or a bare `\"react\"`."
  [ns-form]
  (->> ns-form
       (filter seq?)
       (mapcat (fn [clause]
                 (when (#{:require :require-macros :use} (first clause))
                   (rest clause))))
       (keep (fn [spec]
               (cond
                 (string? spec) spec
                 (and (vector? spec) (string? (first spec))) (first spec))))))

(defn- js-lib-root
  "`cljs.analyzer/lib&sublib` semantics (analyzer.cljc:823-829): a lib of the
   form `foo$bar` indexes under `foo`; anything else — including npm subpaths
   like `react-dom/client` — indexes under its full name."
  [s]
  (if-let [[_ lib _] (re-matches #"(.*)\$(.*)" s)]
    lib
    s))

(defn- seed-js-requires!
  "Register the ns form's string requires in the compiler state's
   `:node-module-index` before the ns form is analyzed.
   `cljs.analyzer/analyze-deps` accepts a dep when `node-module-dep?` finds
   `(first (lib&sublib dep))` in that index (clojurescript 1.11.132
   analyzer.cljc:858-864, 2731-2735), exactly how the project's own npm-aware
   build admits them; `missing-use?` consults the same index so member uses
   pass too. Nothing about an npm module enters the type domain — uses come
   back as `:js`-op nodes typed as Dyn."
  [state ns-form]
  (when-let [roots (seq (map js-lib-root (ns-form-string-requires ns-form)))]
    (swap! state update :node-module-index (fnil into #{}) roots)))

(defn- analyze-source-entry
  [vars state base-env source-form]
  (let [env (assoc base-env
                   :ns (or ((:find-ns vars) state ((:current-ns vars)))
                           (:ns base-env)))]
    (with-bindings (no-warn-bindings vars)
      (-> ((:analyze vars) state env source-form nil {})
          strip-cljs-type))))

(defn- analyze-source-entry-result
  [vars state base-env source-form]
  (try
    {:ast (analyze-source-entry vars state base-env source-form)}
    (catch Throwable e
      {:ast nil :exception e})))

(defn- with-analysis-bindings
  "Inline expansion of `cljs.analyzer.api/with-state` + the
   `(binding [ana/*file-defs* ...])` block from the pre-fix file. The
   `with-state` macro (probe artifact `probes-v2/cljs-sources/v1.12.134/api.cljc:51-55`)
   expands to `(binding [cljs.env/*compiler* state-atom] ...)`.

   Wraps `f` in `cljs.compiler/with-core-cljs` so cljs.core is loaded into
   the same state the analyzer's resolver consults — without this wrap,
   `cljs.analyzer/resolve-var` falls back to auto-interned stub Vars for
   every cljs.core symbol, producing thousands of bogus 'Use of undeclared
   Var' warnings (verified at plan-write time:
   `.scratch/phase5-probes/probe_aave_cljs_core_preload.output.txt` —
   1330 warnings without the wrap, 5 with it, all 5 residual being JS
   interop forms the analyzer classifies as `:host-field`/`:js-var`)."
  [path f]
  (let [vars  (resolved-ana-vars)
        state (empty-state vars)
        wcc   (:with-core-cljs vars)]
    (with-bindings {(:*compiler*         vars) state
                    (:*file-defs*        vars) (atom #{})
                    (:*unchecked-if*     vars) false
                    (:*unchecked-arrays* vars) false
                    (:*analyze-deps*     vars) true
                    (:*load-macros*      vars) true
                    (:*cljs-ns*          vars) 'cljs.user
                    (:*cljs-file*        vars) path}
      (wcc nil (fn [] (f vars state))))))

(defn- analyze-ns-form
  [vars state path source-file]
  (with-open [r (io/reader source-file)]
    (let [base-env (assoc ((:empty-env vars)) :build-options {})
          ns-form (some (fn [form]
                          (when (and (seq? form) (= 'ns (first form))) form))
                        ((:forms-seq vars) r path))]
      (if ns-form
        (do
          (seed-js-requires! state ns-form)
          (analyze-source-entry vars state base-env ns-form))
        (throw (ex-info "cljs source has no (ns ...) form" {:source-file path}))))))

(defn ns-head
  [source-file]
  (let [path (str source-file)
        ns-ast (with-analysis-bindings path
                 (fn [vars state] (analyze-ns-form vars state path source-file)))]
    (when-not (:name ns-ast)
      (throw (ex-info "cljs ns-head produced an ns-AST without :name"
                      {:source-file path
                       :ns-ast-op (:op ns-ast)
                       :ns-ast-keys (sort (keys ns-ast))
                       :ns-ast ns-ast})))
    (select-keys ns-ast [:name :requires :require-macros :use-macros])))

(defn analyze-source-file
  [source-file]
  (let [path (str source-file)]
    (with-analysis-bindings path
      (fn [vars state]
        (with-open [r (io/reader source-file)]
          (let [base-env (assoc ((:empty-env vars)) :build-options {})]
            (loop [forms ((:forms-seq vars) r path)
                   ns-ast nil
                   entries []]
              (if-let [s (seq forms)]
                (let [source-form (first s)
                      ns-form? (and (seq? source-form) (= 'ns (first source-form)))]
                  (if ns-form?
                    (let [_ (seed-js-requires! state source-form)
                          ns-ast (analyze-source-entry vars state base-env source-form)]
                      (recur (next s) ns-ast entries))
                    (let [result (analyze-source-entry-result vars state
                                                              (assoc base-env :ns ns-ast)
                                                              source-form)]
                      (recur (next s)
                             ns-ast
                             (conj entries (merge {:source-form source-form} result))))))
                {:ns-ast (or ns-ast
                             (throw (ex-info "cljs source has no (ns ...) form"
                                             {:source-file path})))
                 :entries entries
                 :asts (filterv some? (mapv :ast entries))}))))))))
