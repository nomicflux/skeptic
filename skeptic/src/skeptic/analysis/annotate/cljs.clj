(ns skeptic.analysis.annotate.cljs
  "ClojureScript-only AST op handlers. Mirrors `skeptic.analysis.annotate.jvm`:
  each handler recurses children via the step trampoline and assigns a `:type`
  derived from the cljs `:tag` (translated via `av/cljs-tag->type`), falling
  back to `Dyn` when the tag is `any` or unrecognized."
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.runner :as runner]
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

(s/defn annotate-host-call :- runner/Step
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:target node)
   (fn [target]
     (runner/sequence-children ctx (:args node)
      (fn [args]
        (runner/done
         (assoc node :target target :args args :type (tag-type ctx (:tag node)))))))))

(s/defn annotate-host-field :- runner/Step
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:target node)
   (fn [target]
     (runner/done
      (assoc node :target target :type (tag-type ctx (:tag node)))))))

(s/defn annotate-js :- runner/Step
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (runner/sequence-children ctx (:args node)
   (fn [args]
     (runner/done
      (assoc node :args args :type (tag-type ctx (:tag node)))))))

(s/defn annotate-js-var :- aas/AnnotatedNode
  [ctx :- aapi/AnnotateCtx node :- aas/AnnotatedNode]
  (assoc node :type (tag-type ctx (:tag node))))
