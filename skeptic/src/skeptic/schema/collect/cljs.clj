(ns skeptic.schema.collect.cljs
  "ClojureScript admission for Plumatic Schema declarations.

  Mirrors `skeptic.schema.collect/ns-schema-results` for cljs source files.
  Operates on per-form analyzed ASTs from `skeptic.cljs.analyzer-driver`.
  Reads alias requires from the parsed ns AST (output of
  `cljs.analyzer.api/parse-ns`) and Var metadata directly from each
  `:def` AST node's `[:var :info :meta]` slot — no cenv reads, no caller-
  managed compiler state.

  Three top-level shapes are recognized:

  - `s/def`        :op :let, single binding `output-schema__*__auto__`,
                   inner :def at [:body :ret].
  - `s/defschema`  :op :def, :init :form starts with vary-meta.
  - `s/defn`       :op :let, multi-binding outer let with `output-schema*`
                   and `input-schema*` gensyms.

  The cljs `:meta :schema` value is a symbolic form (the cljs analyzer
  does not evaluate JVM-side); resolution to a real Schema record goes
  through `skeptic.cljs.schema-interpreter`, which interprets the form
  in a sci-sandboxed context that exposes only `schema.core`."
  (:require [clojure.walk :as walk]
            [schema.core :as s]
            [skeptic.cljs.schema-interpreter :as si]
            [skeptic.schema.collect :as collect]))

(def ^:dynamic *requires*
  "Bound to the active cljs namespace's `:requires` map (alias-sym → ns-sym)
  for the duration of `ns-schema-results-cljs`. Used by `resolve-aliases`
  to rewrite `s/Int` → `schema.core/Int` before JVM eval."
  nil)

(defn- binding-name-str
  [b]
  (some-> b :name str))

(defn- top-level-let?
  [ast]
  (= :let (:op ast)))

(defn- s-def-shape?
  [ast]
  (let [bs (:bindings ast)]
    (and (= 1 (count bs))
         (some-> (first bs) binding-name-str (.startsWith "output-schema__")))))

(defn- s-defn-shape?
  [ast]
  (let [names (map binding-name-str (:bindings ast))]
    (and (some #(and % (re-matches #"output-schema\d+" %)) names)
         (some #(and % (re-matches #"input-schema\d+" %)) names))))

(defn- s-defschema-shape?
  [ast]
  (and (= :def (:op ast))
       (let [init-form (some-> ast :init :form)]
         (and (seq? init-form)
              (= 'clojure.core/vary-meta (first init-form))))))

(defn- find-binding-init-form
  [bindings target-sym]
  (some (fn [b] (when (= target-sym (:name b)) (-> b :init :form)))
        bindings))

(defn- bare-sym
  [s]
  (when s (symbol (name s))))

(defn- unquote-form
  "cljs analyzer preserves arglists meta as the quoted form `(quote ([x]))`
  rather than the evaluated value `([x])`. Unwrap one level of `quote` so
  downstream code sees the same shape as JVM `(meta v)`."
  [form]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(defn- find-def-ast
  "Walk an AST to find the inner `:def` op node. The s/defn / s/def shapes
  nest the actual `:def` inside one or more `:let`s; this finds it directly
  without committing to a single nesting depth."
  [ast]
  (cond
    (= :def (:op ast)) ast
    (map? ast) (some find-def-ast (vals ast))
    (sequential? ast) (some find-def-ast ast)
    :else nil))

(defn- def-ast-meta
  "Reads Var metadata (e.g. {:schema ... :arglists ...}) from a `:def` AST
  node. cljs analyzer attaches it at `[:var :info :meta]`."
  [def-ast]
  (get-in def-ast [:var :info :meta]))

(defn- resolve-alias-sym
  [sym]
  (if-let [ns-part (some-> sym namespace symbol)]
    (if-let [target (get *requires* ns-part)]
      (symbol (name target) (name sym))
      sym)
    sym))

(defn- resolve-aliases
  [form]
  (walk/postwalk
   (fn [x] (if (symbol? x) (resolve-alias-sym x) x))
   form))

(defn- resolve-schema-form
  [form]
  (si/interpret-schema-form (resolve-aliases form)))

(defn- build-fn-schema
  [ast meta-info]
  (let [[_ output-sym input-vec] (:schema meta-info)
        input-sym (first input-vec)
        bindings (:bindings ast)
        output-form (find-binding-init-form bindings output-sym)
        input-form (find-binding-init-form bindings input-sym)]
    (s/->FnSchema (resolve-schema-form output-form)
                  [(resolve-schema-form input-form)])))

(defn- defschema-form
  [ast]
  (let [init-form (-> ast :init :form)
        schema-with-name-call (second init-form)]
    (second schema-with-name-call)))

(defn- error-result
  [ns-sym qualified-sym defn-sym source-file e]
  (collect/declaration-error-result
   ns-sym qualified-sym (with-meta defn-sym {:file source-file}) e))

(defn- admit-with
  [ns-sym qualified-sym defn-sym source-file build]
  (try
    {:ok (build)}
    (catch Exception e
      {:err (error-result ns-sym qualified-sym defn-sym source-file e)})))

(defn- admit-s-def
  [ns-sym source-file ast]
  (let [def-ast (find-def-ast ast)
        defn-sym (some-> def-ast :name bare-sym)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        meta-info (def-ast-meta def-ast)
        schema (resolve-schema-form (:schema meta-info))]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema schema :ns ns-sym :name defn-sym :arglists []}))]))

(defn- admit-s-defschema
  [ns-sym source-file ast]
  (let [defn-sym (:name ast)
        qualified-sym (if (qualified-symbol? defn-sym)
                        defn-sym
                        (symbol (name ns-sym) (name defn-sym)))
        bare-name (symbol (name defn-sym))
        schema (resolve-schema-form (defschema-form ast))]
    [qualified-sym
     (admit-with ns-sym qualified-sym bare-name source-file
                 #(collect/collect-schemas
                   {:schema schema :ns ns-sym :name bare-name :arglists []}))]))

(defn- admit-s-defn
  [ns-sym source-file ast]
  (let [def-ast (find-def-ast ast)
        defn-sym (some-> def-ast :name bare-sym)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        meta-info (def-ast-meta def-ast)
        fn-schema (build-fn-schema ast meta-info)]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema fn-schema
                    :ns ns-sym
                    :name defn-sym
                    :arglists (unquote-form (:arglists meta-info))}))]))

(defn- admit-form
  [ns-sym source-file ast]
  (cond
    (and (top-level-let? ast) (s-defn-shape? ast))
    (admit-s-defn ns-sym source-file ast)

    (and (top-level-let? ast) (s-def-shape? ast))
    (admit-s-def ns-sym source-file ast)

    (s-defschema-shape? ast)
    (admit-s-defschema ns-sym source-file ast)

    :else nil))

(defn ns-schema-results-cljs
  "Per-namespace Plumatic Schema admission for cljs sources.

  Inputs:
  - `ns-ast`: the parsed ns AST from
    `skeptic.cljs.analyzer-driver/parse-source-ns`. Provides `:requires`
    (alias→target map) for alias rewriting before JVM eval of schema forms.
  - `source-file`: the cljs source file, attached to error results.
  - `ns-sym`: the namespace symbol.
  - `top-level-asts`: per-form analyzed ASTs for the file's top-level
    forms (from `analyzer-driver/analyze-form`)."
  [ns-ast source-file ns-sym top-level-asts]
  (binding [*requires* (:requires ns-ast)]
    (reduce
     (fn [acc ast]
       (if-let [[qualified-sym {:keys [ok err]}] (admit-form ns-sym source-file ast)]
         (cond-> acc
           ok  (update :entries assoc qualified-sym ok)
           err (update :errors conj err))
         acc))
     {:entries {} :errors []}
     top-level-asts)))
