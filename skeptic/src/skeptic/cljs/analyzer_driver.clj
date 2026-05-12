(ns skeptic.cljs.analyzer-driver
  "Stateless cljs analyzer entrypoints via the public cljs.analyzer.api.

  Skeptic constructs no compiler state. Each cljs source file is parsed
  once via `parse-source-ns`, which JVM-loads any required macro
  namespaces and returns the analyzed ns AST. Per-form analysis uses the
  3-arity `cljs.analyzer.api/analyze` with that ns AST as the env's `:ns`
  slot, so aliased symbols (`s/defn`, `m/=>`, etc.) macroexpand correctly
  without any caller-managed compiler state.

  cljs ASTs carry `:type` on `:binding`/`:fn-method` nodes that conflicts
  with skeptic's `:type` slot (SemanticType), and `:binding` nodes lack
  `:form`. `analyze-form` strips and synthesizes via `normalize-cljs-node`
  so the skeptic annotate pipeline starts from a clean shape."
  (:require [skeptic.classloader-fix]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler]
            [clojure.java.io :as io]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]))

(defn- normalize-cljs-node
  [n]
  (let [n (dissoc n :type)]
    (if (and (= :binding (:op n)) (not (contains? n :form)))
      (assoc n :form (:name n))
      n)))

(defn- walk-ast
  "Directed AST walker: applies f to the node, then recurses only through
  the keys named in `:children`. Avoids `:env`, `:info`, `:meta`, and other
  non-AST slots that cause `clojure.walk/prewalk` to OOM (cycles + large
  binding-info trees) on real cljs ASTs."
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

(defn- strip-cljs-type [ast] (walk-ast normalize-cljs-node ast))

(defn parse-source-ns
  "Parse a `.cljs` / `.cljc` source file's `(ns ...)` form. Triggers JVM
  loading of any `:require-macros` namespaces and returns the analyzed ns
  AST: a map with `:name`, `:requires`, `:require-macros`, `:uses`, etc.
  Suitable for use as the `:ns` slot of an analysis env passed to
  `analyze-form`. Discards the ephemeral compiler state parse-ns auto-
  creates internally; the JVM-loaded macro namespaces persist."
  [source-file]
  (:ast (ana-api/parse-ns source-file
                          {:load-macros true :analyze-deps false})))

(defn analyze-form
  "Analyze a single cljs form via the 3-arity `cljs.analyzer.api/analyze`.
  `ns-ast` is the ns AST returned by `parse-source-ns` for the source
  file the form belongs to; it carries the file's `:requires` and
  `:require-macros` so aliased symbols resolve. The 3-arity auto-creates
  a fresh ephemeral compilation state; skeptic carries no state between
  calls."
  [ns-ast form]
  (ana-api/no-warn
   (-> (ana-api/analyze (assoc (ana-api/empty-env) :ns ns-ast)
                        form
                        (:name ns-ast))
       strip-cljs-type)))

(defn- read-all-forms
  "Read every top-level form from a cljs/cljc source file using
  `clojure.tools.reader` with the `:cljs` reader feature. State-free."
  [source-file]
  (with-open [r (io/reader source-file)]
    (let [pbr (reader-types/indexing-push-back-reader r 1 (str source-file))
          eof (Object.)
          opts {:eof eof :read-cond :allow :features #{:cljs}}]
      (loop [acc []]
        (let [form (reader/read opts pbr)]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defn- ns-form?
  [form]
  (and (seq? form) (= 'ns (first form))))

(defn analyze-source-file
  "Analyze every top-level non-ns form of a cljs/cljc source file. Returns
  `{:ns-ast <ns-AST> :asts [<form-AST> ...]}`. The ns form is consumed by
  `parse-source-ns`; remaining forms are analyzed with that ns AST as the
  env's `:ns` slot."
  [source-file]
  (let [ns-ast (parse-source-ns source-file)
        forms  (read-all-forms source-file)
        asts   (mapv #(analyze-form ns-ast %) (remove ns-form? forms))]
    {:ns-ast ns-ast :asts asts}))
