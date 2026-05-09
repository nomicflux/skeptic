(ns skeptic.schema.collect.cljs
  "ClojureScript admission for Plumatic Schema declarations.

  Mirrors `skeptic.schema.collect/ns-schema-results` for cljs source files.
  Operates on per-form analyzed ASTs from `skeptic.cljs.analyzer-driver`,
  plus the compiler-env's `[::ana/namespaces ns :defs]` map.

  Three top-level shapes are recognized:

  - `s/def`        :op :let, single binding `output-schema__*__auto__`,
                   inner :def at [:body :ret].
  - `s/defschema`  :op :def, :init :form starts with vary-meta.
  - `s/defn`       :op :let, multi-binding outer let with `output-schema*`
                   and `input-schema*` gensyms.

  The cljs `:meta :schema` value is a symbolic form (the cljs analyzer
  does not evaluate JVM-side); resolving it to a real Schema record uses
  `eval`, which works because schema forms after macroexpansion are fully
  qualified (`schema.core/Int`, `(schema.core/one schema.core/Int (quote x))`)
  and `schema.core` is a `.cljc` namespace loadable on the JVM."
  (:require [cljs.analyzer :as ana]
            [clojure.walk :as walk]
            [schema.core :as s]
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
  "cljs cenv-meta preserves arglists as the quoted form `(quote ([x]))` rather
  than the evaluated value `([x])` (the JVM-side `(meta v)` evaluates meta).
  Unwrap one level of `quote` so downstream code sees the same shape."
  [form]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(defn- inner-def-name
  [ast]
  (bare-sym (or (some-> ast :body :ret :name)
                (some-> ast :body :ret :bindings first :init :name)
                (some-> ast :body :ret :bindings first :init :statements first :name))))

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
  (binding [*ns* (the-ns 'user)]
    (eval (resolve-aliases form))))

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
  [cenv ns-sym source-file ast]
  (let [defn-sym (inner-def-name ast)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        meta-info (get-in @cenv [::ana/namespaces ns-sym :defs defn-sym :meta])
        schema (resolve-schema-form (:schema meta-info))]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema schema :ns ns-sym :name defn-sym :arglists nil}))]))

(defn- admit-s-defschema
  [_cenv ns-sym source-file ast]
  (let [defn-sym (:name ast)
        qualified-sym (if (qualified-symbol? defn-sym)
                        defn-sym
                        (symbol (name ns-sym) (name defn-sym)))
        bare-name (symbol (name defn-sym))
        schema (resolve-schema-form (defschema-form ast))]
    [qualified-sym
     (admit-with ns-sym qualified-sym bare-name source-file
                 #(collect/collect-schemas
                   {:schema schema :ns ns-sym :name bare-name :arglists nil}))]))

(defn- admit-s-defn
  [cenv ns-sym source-file ast]
  (let [defn-sym (inner-def-name ast)
        qualified-sym (symbol (name ns-sym) (name defn-sym))
        meta-info (get-in @cenv [::ana/namespaces ns-sym :defs defn-sym :meta])
        fn-schema (build-fn-schema ast meta-info)]
    [qualified-sym
     (admit-with ns-sym qualified-sym defn-sym source-file
                 #(collect/collect-schemas
                   {:schema fn-schema
                    :ns ns-sym
                    :name defn-sym
                    :arglists (unquote-form (:arglists meta-info))}))]))

(defn- admit-form
  [cenv ns-sym source-file ast]
  (cond
    (and (top-level-let? ast) (s-defn-shape? ast))
    (admit-s-defn cenv ns-sym source-file ast)

    (and (top-level-let? ast) (s-def-shape? ast))
    (admit-s-def cenv ns-sym source-file ast)

    (s-defschema-shape? ast)
    (admit-s-defschema cenv ns-sym source-file ast)

    :else nil))

(defn ns-schema-results-cljs
  [cenv source-file ns-sym top-level-asts]
  (binding [*requires* (get-in @cenv [::ana/namespaces ns-sym :requires])]
    (reduce
     (fn [acc ast]
       (if-let [[qualified-sym {:keys [ok err]}] (admit-form cenv ns-sym source-file ast)]
         (cond-> acc
           ok  (update :entries assoc qualified-sym ok)
           err (update :errors conj err))
         acc))
     {:entries {} :errors []}
     top-level-asts)))
