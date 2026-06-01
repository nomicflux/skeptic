(ns skeptic.malli-spec.collect.cljs
  "ClojureScript admission for Malli function schemas.

  Mirrors `skeptic.malli-spec.collect/ns-malli-spec-results` for cljs source
  files. Two channels:

  - Var-meta channel: the `:malli/schema` spec the worker captured off the
    raw source-form into each cljs entry's `:malli-schema` field
    (`skeptic.worker.server/project-cljs-entry`), exactly as the clj collector
    reads its entries. The cljs AST never carries user var-meta at
    `[:var :info :meta]`, so this channel reads the entry field, not the AST.
  - Registration channel: `(malli.core/=> sym SPEC)` macroexpands to a
    top-level `:op :do` whose form is
    `(do (malli.core/-register-function-schema! 'ns 'sym SPEC nil :cljs id) 'ns/sym)`.
    Classification walks the outer `:form` directly.

  No cenv reads, no caller-managed compiler state."
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.malli-spec.collect :as collect]
            [skeptic.malli-spec.collect.schema :as mcs]))

(s/defschema RegistryEntry
  [(s/one s/Symbol "fn-sym")
   (s/one s/Any "spec")])

(s/defschema VarMetaEntry
  [(s/one s/Symbol "fn-sym")
   (s/one s/Any "spec")])

(s/defn ^:private bare-sym :- (s/maybe s/Symbol)
  [s :- (s/maybe s/Symbol)]
  (when s (symbol (name s))))

(s/defn ^:private registration-form :- (s/maybe RegistryEntry)
  [ast :- aas/AnnotatedNode]
  (let [form (:form ast)
        invoke (when (and (seq? form) (= 'do (first form))) (second form))]
    (when (and (seq? invoke)
               (= 'malli.core/-register-function-schema! (first invoke)))
      (let [sym-quoted (nth invoke 2 nil)
            spec (nth invoke 3 nil)]
        (when (and (seq? sym-quoted) (= 'quote (first sym-quoted)))
          (when-let [sym (bare-sym (second sym-quoted))]
            [sym spec]))))))

(s/defn ^:private registry-entries :- [RegistryEntry]
  [top-level-asts :- [aas/AnnotatedNode]]
  (vec (keep registration-form top-level-asts)))

(s/defn ^:private var-meta-entries :- [VarMetaEntry]
  [entries         :- [{s/Keyword s/Any}]
   registered-syms :- #{s/Symbol}]
  (vec
   (keep (fn [{:keys [source-form malli-schema]}]
           (when (and malli-schema (seq? source-form))
             (when-let [fn-sym (bare-sym (second source-form))]
               (when-not (registered-syms fn-sym)
                 [fn-sym malli-schema]))))
         entries)))

(s/defn ^:private admit :- mcs/MalliAdmissionResult
  [ns-sym      :- s/Symbol
   source-file :- s/Any
   acc         :- mcs/MalliAdmissionResult
   entry       :- RegistryEntry]
  (let [[fn-sym spec] entry
        {:keys [entries errors]} acc
        qualified-sym (symbol (name ns-sym) (name fn-sym))]
    (try
      {:entries (assoc entries qualified-sym
                       {:name (str qualified-sym)
                        :malli-spec (amb/admit-malli-spec spec)})
       :errors errors}
      (catch Exception e
        {:entries entries
         :errors (conj errors (collect/malli-declaration-error-result
                               ns-sym qualified-sym {:file source-file} e))}))))

(s/defn ns-malli-spec-results-cljs :- mcs/MalliAdmissionResult
  "Per-namespace Malli admission for cljs sources. Inputs:
  - `source-file`: the cljs source file, attached to error results.
  - `ns-sym`: the namespace symbol.
  - `entries`: per-form cljs entries carrying `:ast` (registration channel)
    and `:malli-schema` (var-meta channel, captured off the raw source-form
    by the worker). No cenv reads."
  [source-file :- s/Any
   ns-sym      :- s/Symbol
   entries     :- [{s/Keyword s/Any}]]
  (let [top-level-asts (into [] (keep :ast) entries)
        registered (registry-entries top-level-asts)
        registered-syms (into #{} (map first) registered)
        meta-only (var-meta-entries entries registered-syms)]
    (reduce (partial admit ns-sym source-file)
            {:entries {} :errors []}
            (concat registered meta-only))))
