(ns skeptic.schema.collect.cljs
  "ClojureScript admission for Plumatic Schema declarations.

  Mirrors `skeptic.schema.collect/ns-schema-results` for cljs source files.
  Operates on per-form analyzed ASTs from `skeptic.cljs.analyzer-driver`.
  Reads alias requires from the parsed ns AST (output of
  `cljs.analyzer.api/parse-ns`). The cljs analyzer does not attach the
  `s/defn` `:schema`/`:arglists` Var-metadata to the `:def` AST node, so the
  schema forms are read directly from the macroexpansion's `:let` binding
  init forms (`output-schema*` / `input-schema*`); the single-arity arglist is
  reconstructed from the `s/one` arg-name labels in the input-schema form. No
  cenv reads, no caller-managed compiler state.

  Three top-level shapes are recognized:

  - `s/def`        :op :let, single binding `output-schema__*__auto__`,
                   inner :def at [:body :ret].
  - `s/defschema`  :op :def, :init :form starts with vary-meta.
  - `s/defn`       :op :let, multi-binding outer let with `output-schema*`
                   and `input-schema*` gensyms.

  Each `output-schema*` / `input-schema*` binding init is a symbolic schema
  form (the cljs analyzer does not evaluate JVM-side); resolution to a real
  Schema record goes through `skeptic.cljs.schema-interpreter`, which
  interprets the form in a sci-sandboxed context that exposes only
  `schema.core`."
  (:require [clojure.walk :as walk]
            [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.cljs.analyzer-driver :as ad]
            [skeptic.cljs.schema-interpreter :as si]
            [skeptic.schema.collect :as collect]
            [skeptic.schema.collect.schema :as scs]))

(def ^:dynamic *requires*
  "Bound to the active cljs namespace's `:requires` map (alias-sym → ns-sym)
  for the duration of `ns-schema-results-cljs`. Used by `resolve-aliases`
  to rewrite `s/Int` → `schema.core/Int` before JVM eval."
  nil)

(s/defschema AdmitFormPair
  [(s/one s/Symbol "qsym")
   (s/one scs/SchemaAdmitOutcome "outcome")])

(s/defn ^:private binding-name-str :- (s/maybe s/Str)
  [b :- aas/AnnotatedNode]
  (some-> b :name str))

(s/defn ^:private top-level-let? :- s/Bool
  [ast :- aas/AnnotatedNode]
  (= :let (:op ast)))

(s/defn ^:private s-def-shape? :- s/Bool
  [ast :- aas/AnnotatedNode]
  (let [bs (:bindings ast)]
    (boolean
     (and (= 1 (count bs))
          (some-> (first bs) binding-name-str (.startsWith "output-schema__"))))))

(s/defn ^:private s-defn-shape? :- s/Bool
  [ast :- aas/AnnotatedNode]
  (let [names (map binding-name-str (:bindings ast))]
    (boolean
     (and (some #(and % (re-matches #"output-schema\d+" %)) names)
          (some #(and % (re-matches #"input-schema\d+" %)) names)))))

(s/defn ^:private s-defschema-shape? :- s/Bool
  [ast :- aas/AnnotatedNode]
  (boolean
   (and (= :def (:op ast))
        (let [init-form (some-> ast :init :form)]
          (and (seq? init-form)
               (= 'clojure.core/vary-meta (first init-form)))))))

(s/defn ^:private binding-init-form-by-prefix :- s/Any
  "Init form of the first binding whose name starts with `prefix`. The cljs
  `s/defn` macroexpansion binds the output schema to `output-schema<gensym>`
  and the input schema to `input-schema<gensym>`; the analyzer no longer
  attaches the `:schema` var-meta naming those gensyms, so they are located by
  their fixed prefix instead."
  [bindings :- [aas/AnnotatedNode]
   prefix   :- s/Str]
  (some (fn [b] (when (some-> (binding-name-str b) (.startsWith prefix))
                  (-> b :init :form)))
        bindings))

(s/defn ^:private bare-sym :- (s/maybe s/Symbol)
  [s :- (s/maybe s/Symbol)]
  (when s (symbol (name s))))

(s/defn ^:private unquote-form :- s/Any
  "Unwrap one level of `quote`: `(quote x)` → `x`. The cljs analyzer preserves
  literal symbols/forms quoted, e.g. the `s/one` arg-name label."
  [form :- s/Any]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(s/defn ^:private one-entry-arg-name :- (s/maybe s/Symbol)
  "Arg name from a `(schema.core/one <schema> (quote <name>))` input entry."
  [entry :- s/Any]
  (when (and (seq? entry) (= 3 (count entry)))
    (let [nm (unquote-form (nth entry 2))]
      (when (symbol? nm) nm))))

(s/defn ^:private arglist-from-input-form :- [s/Symbol]
  "Single-arity arglist recovered from the `input-schema` binding form
  `[(s/one <schema> (quote <name>)) ...]`. The cljs analyzer drops the
  `:arglists` var-meta, but every positional input carries its name as the
  `s/one` label, so the arglist is reconstructable from the input schema."
  [input-form :- s/Any]
  (if (vector? input-form)
    (vec (keep one-entry-arg-name input-form))
    []))

(s/defn ^:private resolve-alias-sym :- s/Symbol
  [sym :- s/Symbol]
  (if-let [ns-part (some-> sym namespace symbol)]
    (if-let [target (get *requires* ns-part)]
      (symbol (name target) (name sym))
      sym)
    sym))

(s/defn ^:private resolve-aliases :- s/Any
  [form :- s/Any]
  (walk/postwalk
   (fn [x] (if (symbol? x) (resolve-alias-sym x) x))
   form))

(s/defn ^:private resolve-schema-form :- s/Any
  [form :- s/Any]
  (si/interpret-schema-form (resolve-aliases form)))

(s/defn ^:private build-fn-schema :- s/Any
  [output-form :- s/Any
   input-form  :- s/Any]
  (s/->FnSchema (resolve-schema-form output-form)
                [(resolve-schema-form input-form)]))

(s/defn ^:private defschema-form :- s/Any
  [ast :- aas/AnnotatedNode]
  (let [init-form (-> ast :init :form)
        schema-with-name-call (second init-form)]
    (second schema-with-name-call)))

(s/defn ^:private require-def-ast :- aas/AnnotatedNode
  [ast :- aas/AnnotatedNode]
  (or (ad/find-by-op :def ast)
      (throw (ex-info "expected :def AST under shape-validated form" {:ast ast}))))

(s/defn ^:private require-name-sym :- s/Symbol
  [def-ast :- aas/AnnotatedNode]
  (or (bare-sym (:name def-ast))
      (throw (ex-info "expected non-nil :name on :def AST" {:def-ast def-ast}))))

(s/defn ^:private error-result :- scs/SchemaErrorResult
  [ns-sym        :- s/Symbol
   qualified-sym :- s/Symbol
   defn-sym      :- s/Symbol
   source-file   :- s/Any
   e             :- Exception]
  (collect/declaration-error-result
   ns-sym qualified-sym (with-meta defn-sym {:file source-file}) e))

(s/defn ^:private admit-with :- scs/SchemaAdmitOutcome
  [ns-sym        :- s/Symbol
   qualified-sym :- s/Symbol
   defn-sym      :- s/Symbol
   source-file   :- s/Any
   build]
  (try
    {:ok (build)}
    (catch Exception e
      {:err (error-result ns-sym qualified-sym defn-sym source-file e)})))

(s/defn ^:private admit-s-def :- AdmitFormPair
  [ns-sym      :- s/Symbol
   source-file :- s/Any
   ast         :- aas/AnnotatedNode]
  (let [def-ast (require-def-ast ast)
        defn-sym (require-name-sym def-ast)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        output-form (binding-init-form-by-prefix (:bindings ast) "output-schema")
        schema (resolve-schema-form output-form)]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema schema :ns ns-sym :name defn-sym :arglists []}))]))

(s/defn ^:private admit-s-defschema :- AdmitFormPair
  [ns-sym      :- s/Symbol
   source-file :- s/Any
   ast         :- aas/AnnotatedNode]
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

(s/defn ^:private admit-s-defn :- AdmitFormPair
  [ns-sym      :- s/Symbol
   source-file :- s/Any
   ast         :- aas/AnnotatedNode]
  (let [def-ast (require-def-ast ast)
        defn-sym (require-name-sym def-ast)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        bindings (:bindings ast)
        output-form (binding-init-form-by-prefix bindings "output-schema")
        input-form (binding-init-form-by-prefix bindings "input-schema")
        fn-schema (build-fn-schema output-form input-form)]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema fn-schema
                    :ns ns-sym
                    :name defn-sym
                    :arglists [(arglist-from-input-form input-form)]}))]))

(s/defn ^:private admit-form :- (s/maybe AdmitFormPair)
  [ns-sym      :- s/Symbol
   source-file :- s/Any
   ast         :- aas/AnnotatedNode]
  (cond
    (and (top-level-let? ast) (s-defn-shape? ast))
    (admit-s-defn ns-sym source-file ast)

    (and (top-level-let? ast) (s-def-shape? ast))
    (admit-s-def ns-sym source-file ast)

    (s-defschema-shape? ast)
    (admit-s-defschema ns-sym source-file ast)

    :else nil))

(s/defn ns-schema-results-cljs :- scs/SchemaAdmissionResult
  "Per-namespace Plumatic Schema admission for cljs sources.

  Inputs:
  - `ns-ast`: the parsed ns AST from
    `skeptic.cljs.analyzer-driver/parse-source-ns`. Provides `:requires`
    (alias→target map) for alias rewriting before JVM eval of schema forms.
  - `source-file`: the cljs source file, attached to error results.
  - `ns-sym`: the namespace symbol.
  - `top-level-asts`: per-form analyzed ASTs for the file's top-level
    forms (from `analyzer-driver/analyze-form`)."
  [ns-ast         :- aas/AnnotatedNode
   source-file    :- s/Any
   ns-sym         :- s/Symbol
   top-level-asts :- [aas/AnnotatedNode]]
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
