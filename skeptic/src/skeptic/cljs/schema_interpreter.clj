(ns skeptic.cljs.schema-interpreter
  "sci-sandboxed interpretation of post-macroexpansion Plumatic Schema forms
  collected from cljs ASTs.

  The sci context exposes `schema.core` as the only allowlisted user
  namespace; sci interprets the form by applying Plumatic's real JVM
  functions and returns real Plumatic Schema records. Symbols outside
  the allowlist (and sci's default clojure.core surface) cannot be
  resolved, so the interpreter cannot execute arbitrary user code.

  SCI is loaded through a private implementation namespace only when a CLJS
  schema form actually needs interpretation. This keeps `skeptic.core` and the
  Lein plugin load path from eagerly loading SCI/edamame."
  (:require [schema.core :as s]))

(defn- interpreter-var
  []
  (or (requiring-resolve 'skeptic.cljs.sci-schema-interpreter/interpret-schema-form)
      (throw (ex-info "Could not resolve CLJS schema interpreter implementation"
                      {:sym 'skeptic.cljs.sci-schema-interpreter/interpret-schema-form}))))

(s/defn interpret-schema-form :- s/Any
  [form :- s/Any]
  ((interpreter-var) form))
