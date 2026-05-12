# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- ClojureScript source-file support, including project-layout discovery for
  deps.edn, Leiningen, and Shadow-CLJS. Skeptic now reads `.cljs` source
  files via the public `cljs.analyzer.api` and admits both Plumatic Schema
  and Malli declarations on cljs vars. `.cljc` files are admitted twice
  (once per host language); identical findings from both passes are deduped
  with their `lang` set to `["clj","cljs"]`.
- `clojure -M:skeptic` deps.edn entrypoint via `skeptic.cli.main`. Skeptic
  is now usable from a `deps.edn` alias in addition to the existing
  Leiningen plugin; the analyzer must load the project's namespaces, so
  `clj -T` (tool installation) is not supported.
- `--paths PATHS` and `--alias ALIAS` flags (deps.edn entrypoint only) for
  overriding the discovered source paths or merging additional deps.edn
  aliases before path resolution.
- `--cljs-disable` flag to skip ClojureScript admission entirely. With the
  flag set, `.cljs` files are dropped and `.cljc` files are admitted as
  `:clj`-only — the `:cljs` reader-conditional branch is discarded.
- `lang` field on every JSONL `location` object identifying the host
  language the reported type was admitted under: `"clj"`, `"cljs"`, or a
  sorted JSON array `["clj","cljs"]` when both passes of a `.cljc` file
  produced the same finding.
- `--plumatic-disable` and `--malli-disable` CLI flags to switch off either
  intake stream entirely. A disabled stream contributes no entries to the
  merged type dict, no provenance, and no findings whose source matches the
  disabled stream's tag (`schema` for `--plumatic-disable`, `malli` for
  `--malli-disable`). `--plumatic-disable` additionally suppresses
  `:skeptic/type-overrides` since overrides are a Plumatic-domain construct.
  A Var declared via both streams is still admitted via the enabled one;
  combining both flags leaves only Skeptic's built-in native-fn declarations.
- Basic, experimental Malli declaration support for `:malli/schema` var
  metadata. Skeptic now converts simple `[:=> [:cat ...] out]` function
  schemas, primitive leaves, `:maybe`, `:or`, `:enum`, and recognized
  predicate symbols into the checker type domain. Unsupported Malli forms
  currently remain dynamic.
- Finding output now carries source attribution so text and JSONL consumers can
  distinguish types that came from Schema, Malli, built-in/native declarations,
  type overrides, or inference.
- Function values are now checked against Java callable-interface targets
  (`Runnable`, `Callable`, `Comparator`, and the `java.util.function.*`
  single-abstract-method interfaces). Previously a `:- Runnable` parameter
  produced a confusing `(=> Any) but expected java.lang.Runnable` mismatch
  for any Clojure fn; now skeptic checks the source fn's arity against the
  interface's abstract-method arity and casts its declared return type to
  `Bool` for `Predicate` / `BiPredicate`, `Int` for `Comparator`, and `Dyn`
  for the rest.

### Changed

- Default type rendering now uses declared Schema, Malli, and type-override
  names for more compact reports when possible. Use `--explain-full` to show
  fully expanded structural forms.
- `lein skeptic` runs the analysis pass under
  `schema.core/without-fn-validation`, avoiding Plumatic Schema function
  validation overhead in projects that have runtime Schema validation enabled.
- Annotation and reporting hot paths have been tightened to reduce repeated
  type rendering, provenance, and checker work.

### Fixed

- Plumatic Schema map schemas using non-keyword required keys
  (e.g. `{(s/required-key "a") s/Int}`) now check correctly: correct
  call sites are no longer flagged with a spurious unexpected-key
  finding, and missing-required-key cases are now reported instead of
  being silently dropped. Keyword required keys were already handled
  by Plumatic's short-circuit to bare keywords; the bug only affected
  string and other non-keyword keys.
- Additional nullability and conditional-narrowing cases now preserve
  flow-sensitive facts through aliases, let-bound vars, path-shaped map
  projections, conditional branches, and enum/sum-type tests.
- Analysis no longer throws `Expected typed entry` on Malli `:map` or other
  unsupported Malli forms encountered while building the per-namespace
  declaration dict.
- Multi-arity `defn` output is now checked per arity against the declared
  return type for that specific arity, instead of comparing every analyzed
  method against the first arity's declared output.
- `(assoc nilable-map k v)` (and `update` on a nilable map) no longer
  carries a `Maybe` wrapper through the result, since `(assoc nil k v)`
  returns `{k v}` — not `nil`. The nil-branch is captured by downgrading
  the inner map's required keys to optional, so callers that previously
  saw a spurious nullability finding now get a clean check, while callers
  that depended on inner keys being required see a `:map-nullable-key`
  finding instead of a false pass.
- `(when (or (nil? a) (nil? b) ...) (throw ...))` now narrows every
  guarded local in subsequent statements, not just the single-variable
  shape. Previously a disjunctive throw-guard's De-Morgan negation was
  treated as unsupported, so the rest of the enclosing `do` continued to
  see each local as `(maybe T)` — yielding a spurious nullability
  finding on a correct program. The conjunctive shape
  `(when (and (nil? a) (nil? b)) (throw))` was already handled and is
  unchanged.

## [0.7.1] - 2026-04-22

### Added

- `-o`/`--output OUTPUT_FILE` on `lein skeptic` so skeptic's findings, summary, or JSONL stream can be written to a file while lein/JVM chatter stays on stdout (#2).

### Changed

### Fixed

- `(or x fallback)` no longer reports a spurious nullability error when `fallback` is truthy.
- Flow-sensitive narrowing now flows through named destructured map keys: a
  `(str/blank? a)` or `(some? a)` guard on a `{:keys [a]}` destructure refines
  the local `a` itself, not only the parent map, so downstream reads of `a`
  see the narrowed type.

## [0.7.0] - 2026-04-19

### Added

- Clojars coordinates `org.clojars.nomicflux/skeptic` and `org.clojars.nomicflux/lein-skeptic`, `:deploy-repositories` for Leiningen, CI checks that keep the library and plugin versions aligned before publish, and GitHub Actions for **Release lifecycle** (orchestrates phases), **Change project versions** (reusable version bump), and **Publish to Clojars** (reusable deploy to Clojars).
- GitHub Actions CI running automated checks on pushes and pull requests.
- `lein skeptic -p` / `--porcelain` for newline-delimited JSON output (one
  JSON object per line), documented in the README.
- `lein skeptic --profile` for optional CPU, memory, and wall-clock profiling;
  when combined with `--porcelain`, the profile summary is written to stderr so
  stdout stays JSONL-only.
- Optional project `.skeptic/config.edn` with `:exclude-files` (root-relative
  globs that skip loading and checking matched paths) and `:type-overrides`
  (Plumatic Schema forms evaluated with `schema.core` in scope and merged into
  collected declarations, including `:output`-only overrides).
- Fine-grained check controls: `:skeptic/ignore-body` and `:skeptic/opaque` on
  `s/defn` attribute maps, and `^{:skeptic/type T}` on expressions (see README
  *Suppressing checks*).
- An expanded user-facing `README.md` that explains what Skeptic checks, how
  the plugin works, how to install it, how to interpret its reports, where to
  find the algorithm reference, and how to use configuration, JSONL output,
  suppressions, and a short “building from source” section.
- A new `--analyzer` CLI flag to print analyzer output while inspecting a
  namespace.
- Richer mismatch reports with source expressions, file and line information,
  enclosing forms, and affected inputs.
- Output validation for function bodies, so Skeptic now checks declared
  return Plumatic Schema annotations in addition to call-site argument
  compatibility.
- Static-call coverage and tests for Plumatic-Schema-sensitive operations such as `get`
  and `merge`.

### Changed

- Reworked the checker to analyze forms through `clojure.tools.analyzer` ASTs
  instead of the older macroexpansion-driven pipeline.
- Switched mismatch checking to a directional cast engine that reports cast
  rules, blame side, and blame polarity instead of only undirected mismatch
  against declared Plumatic Schema annotations.
- Switched namespace reading to `clojure.tools.reader` with source logging so
  reports can preserve source text and location metadata.
- Canonicalized Plumatic Schema handling across checker internals so built-in classes,
  Plumatic Schema aliases, map keys, and maybe types compare more consistently.
- Expanded namespace declaration collection to inspect schematized interned vars
  instead of only public vars.
- Further refactored analysis and the per-namespace checking pipeline, with
  continued improvements to the directional cast and blame reporting model
  (theory from Ahmed, Findler, Siek, and Wadler, *Blame for All*, POPL 2011; see
  [README](README.md#cast-checking-and-attribution)).
- Leiningen plugin uses an explicit Skeptic dependency profile so classpath wiring for the library stays clear.

### Fixed

- Preserved auto-resolved keywords from the target namespace when collecting
  declared Plumatic Schema annotations and checking forms.
- Reduced nested helper calls and map lookups to their final inferred types
  before reporting mismatches.
- Improved output mismatch rendering for declared map Plumatic Schema
  annotations so reported types are canonical and easier to read.
- Stabilized self-analysis and internal checking so recursive or self-hosted
  runs no longer depend on loader-sensitive semantic values embedded in
  canonical analysis data.
- Leiningen now surfaces a non-zero exit when Skeptic reports inconsistencies
  instead of treating the run as successful in some failure cases.
- Broader inference and checking fixes: flow-sensitive nil narrowing around
  `throw`, `case`, and `cond`; numeric tower comparisons; `clojure.string/blank?`;
  conditional branches; `maybe` / `eq` / nil edge cases; invoke analysis without
  redundant walks; cyclic type graphs; preservation of map projections through
  checks; and a single consolidated boundary for schema-side compatibility checks
  against inferred types.
