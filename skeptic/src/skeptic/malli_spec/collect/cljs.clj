(ns skeptic.malli-spec.collect.cljs
  "ClojureScript admission for Malli function schemas.

  Mirrors `skeptic.malli-spec.collect/ns-malli-spec-results` for cljs source
  files. Two channels:

  - Var-meta channel: each per-form `:def` AST has `[:var :info :meta]`
    holding the source-form metadata. The `:malli/schema` key on that map
    holds the literal Malli spec vector (self-evaluating; no quote wrapper).
  - Registration channel: `(malli.core/=> sym SPEC)` macroexpands to a
    top-level `:op :do` whose form is
    `(do (malli.core/-register-function-schema! 'ns 'sym SPEC nil :cljs id) 'ns/sym)`.
    Classification walks the outer `:form` directly.

  No cenv reads, no caller-managed compiler state."
  (:require [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.cljs.analyzer-driver :as ad]
            [skeptic.malli-spec.collect :as collect]))

(defn- bare-sym
  [s]
  (when s (symbol (name s))))

(defn- registration-form
  [ast]
  (let [form (:form ast)
        invoke (when (and (seq? form) (= 'do (first form))) (second form))]
    (when (and (seq? invoke)
               (= 'malli.core/-register-function-schema! (first invoke)))
      (let [sym-quoted (nth invoke 2 nil)
            spec (nth invoke 3 nil)]
        (when (and (seq? sym-quoted) (= 'quote (first sym-quoted)))
          [(bare-sym (second sym-quoted)) spec])))))

(defn- registry-entries
  [top-level-asts]
  (keep registration-form top-level-asts))

(defn- var-meta-entries
  [top-level-asts registered-syms]
  (keep (fn [ast]
          (when-let [def-ast (ad/find-by-op :def ast)]
            (let [meta-info (get-in def-ast [:var :info :meta])
                  spec (:malli/schema meta-info)
                  fn-sym (bare-sym (:name def-ast))]
              (when (and spec fn-sym (not (registered-syms fn-sym)))
                [fn-sym spec]))))
        top-level-asts))

(defn- admit
  [ns-sym source-file {:keys [entries errors]} [fn-sym spec]]
  (let [qualified-sym (symbol (name ns-sym) (name fn-sym))]
    (try
      {:entries (assoc entries qualified-sym
                       {:name (str qualified-sym)
                        :malli-spec (amb/admit-malli-spec spec)})
       :errors errors}
      (catch Exception e
        {:entries entries
         :errors (conj errors (collect/malli-declaration-error-result
                               ns-sym qualified-sym {:file source-file} e))}))))

(defn ns-malli-spec-results-cljs
  "Per-namespace Malli admission for cljs sources. Inputs:
  - `source-file`: the cljs source file, attached to error results.
  - `ns-sym`: the namespace symbol.
  - `top-level-asts`: per-form analyzed ASTs from
    `skeptic.cljs.analyzer-driver/analyze-form`. Both intake channels
    derive from these ASTs alone; no cenv reads."
  [source-file ns-sym top-level-asts]
  (let [registered (registry-entries top-level-asts)
        registered-syms (into #{} (map first) registered)
        meta-only (var-meta-entries top-level-asts registered-syms)]
    (reduce (partial admit ns-sym source-file)
            {:entries {} :errors []}
            (concat registered meta-only))))
