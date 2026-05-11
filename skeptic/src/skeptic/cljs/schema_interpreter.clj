(ns skeptic.cljs.schema-interpreter
  "sci-sandboxed interpretation of post-macroexpansion Plumatic Schema forms
  collected from cljs ASTs.

  The sci context exposes `schema.core` as the only allowlisted user
  namespace; sci interprets the form by applying Plumatic's real JVM
  functions and returns real Plumatic Schema records. Symbols outside
  the allowlist (and sci's default clojure.core surface) cannot be
  resolved, so the interpreter cannot execute arbitrary user code."
  (:require [sci.core :as sci]
            [schema.core]))

(def ^:private schema-ns
  (sci/create-ns 'schema.core))

(def ^:private schema-ctx
  (sci/init {:namespaces {'schema.core (sci/copy-ns schema.core schema-ns)}}))

(defn interpret-schema-form
  [form]
  (sci/eval-form schema-ctx form))
