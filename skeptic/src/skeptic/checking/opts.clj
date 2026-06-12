(ns skeptic.checking.opts
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.provenance.schema :as provs]))

(s/defschema RawTypeOverride
  {(s/optional-key :schema) s/Any
   (s/optional-key :output) s/Any
   (s/optional-key :arglists) s/Any
   s/Keyword s/Any})

(s/defschema SkepticConfig
  {(s/optional-key :exclude-files) [s/Str]
   (s/optional-key :type-overrides) {s/Symbol RawTypeOverride}
   s/Keyword s/Any})

(s/defschema CompiledTypeOverride at/SemanticType)

(s/defschema DiscoveredNamespaces
  "Every [ns-sym source-file] discovered for the current project pass — a
  sequence of pairs, never a map: one namespace may be declared by more
  than one file (.clj alongside .cljc), and which file defines it is the
  project's own load's decision, made in the worker, not a host-side
  collapse. ALWAYS the full discovery — never pre-filtered by `-n` /
  `:namespace`, which is a CHECKING-time filter applied after admission.
  Filtering before handing to `skeptic.checking.pipeline/project-state`
  violates the cross-namespace admission invariant: declarations from
  un-requested namespaces would never reach the merged dict, and call
  sites depending on them would silently degrade to Dyn. The filter for
  `-n` lives in `skeptic.core/check-project` against the per-namespace
  CHECK loop, never against admission."
  [[(s/one s/Symbol "ns-sym") (s/one java.io.File "source-file")]])

(s/defschema CheckProjectOpts
  {(s/optional-key :namespace) [s/Str]
   (s/optional-key :verbose) s/Bool
   (s/optional-key :keep-empty) s/Bool
   (s/optional-key :show-context) s/Bool
   (s/optional-key :explain-full) s/Bool
   (s/optional-key :porcelain) s/Bool
   (s/optional-key :debug) s/Bool
   (s/optional-key :profile) s/Bool
   (s/optional-key :output) (s/maybe s/Str)
   (s/optional-key :analyzer) s/Bool
   (s/optional-key :cljs-disable) s/Bool
   (s/optional-key :plumatic-disable) s/Bool
   (s/optional-key :malli-disable) s/Bool
   (s/optional-key :help) s/Bool
   (s/optional-key :paths) [s/Str]
   (s/optional-key :alias) [s/Keyword]
   (s/optional-key :cljs-only-namespaces) #{s/Symbol}
   (s/optional-key :skeptic/config) (s/maybe SkepticConfig)
   (s/optional-key :skeptic/type-overrides) {s/Symbol CompiledTypeOverride}
   (s/optional-key :skeptic/source-files) [java.io.File]
   (s/optional-key :skeptic/source-discovery-failures) [s/Any]
   (s/optional-key :worker-classpath) (s/maybe {:combined s/Str})
   (s/optional-key :worker-spawn) s/Any})

(s/defschema Lang (s/enum :clj :cljs :both))

(s/defschema DiscoveryEntry
  {:role s/Keyword
   :declared-sym s/Symbol
   :form s/Any
   s/Keyword s/Any})

(s/defschema DiscoveryOut
  {:declarations {s/Symbol DiscoveryEntry}
   :errors [s/Any]})

(s/defschema VarProvs {s/Symbol provs/Provenance})

(s/defschema FormCheckOpts
  {(s/optional-key :keep-empty) s/Bool
   (s/optional-key :remove-context) s/Bool
   (s/optional-key :debug) s/Bool})

(s/defschema PrinterOpts
  {(s/optional-key :verbose) s/Bool
   (s/optional-key :debug) s/Bool
   (s/optional-key :analyzer) s/Bool
   (s/optional-key :explain-full) s/Bool
   (s/optional-key :show-context) s/Bool})

(s/defschema PolarityOpts {:polarity (s/enum :positive :negative)})

(s/defschema CanonicalizeOpts {(s/optional-key :constrained->base?) s/Bool})
