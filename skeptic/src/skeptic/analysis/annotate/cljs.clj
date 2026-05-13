(ns skeptic.analysis.annotate.cljs
  "ClojureScript-only AST op handlers. Mirrors `skeptic.analysis.annotate.jvm`:
  each handler recurses children via (:recurse ctx) and assigns a `:type`
  derived from the cljs `:tag` (translated via `av/cljs-tag->type`), falling
  back to `Dyn` when the tag is `any` or unrecognized."
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(s/defn ^:private tag-type :- at/SemanticType
  [ctx :- aapi/AnnotateCtx
   tag :- s/Any]
  (if tag
    (av/cljs-tag->type (prov/with-ctx ctx) tag)
    (aapi/dyn ctx)))

(s/defn annotate-host-call :- aas/AnnotatedNode
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (let [target ((:recurse ctx) ctx (:target node))
        args (mapv #((:recurse ctx) ctx %) (:args node))]
    (assoc node :target target :args args :type (tag-type ctx (:tag node)))))

(s/defn annotate-host-field :- aas/AnnotatedNode
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (let [target ((:recurse ctx) ctx (:target node))]
    (assoc node :target target :type (tag-type ctx (:tag node)))))

(s/defn annotate-js :- aas/AnnotatedNode
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))]
    (assoc node :args args :type (tag-type ctx (:tag node)))))

(s/defn annotate-js-var :- aas/AnnotatedNode
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (assoc node :type (tag-type ctx (:tag node))))
