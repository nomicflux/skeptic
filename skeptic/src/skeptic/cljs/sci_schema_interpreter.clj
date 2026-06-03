(ns skeptic.cljs.sci-schema-interpreter
  "SCI-backed implementation for `skeptic.cljs.schema-interpreter`.

  Keep this namespace out of eager host/plugin load paths. It requires SCI
  directly because SCI's namespace-copy helpers are macros."
  (:require [schema.core :as s]
            [sci.core :as sci]))

(def ^:private schema-ns
  (sci/create-ns 'schema.core))

(def ^:private schema-ctx
  (sci/init {:namespaces {'schema.core (sci/copy-ns schema.core schema-ns)}}))

(s/defn interpret-schema-form :- s/Any
  [form :- s/Any]
  (sci/eval-form schema-ctx form))
