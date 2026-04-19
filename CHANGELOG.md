# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Clojars coordinates `org.clojars.nomicflux/skeptic` and `org.clojars.nomicflux/lein-skeptic`, `:deploy-repositories` for Leiningen, CI version alignment (`script/verify-monorepo-versions.sh`), and GitHub Actions: **Release lifecycle** (orchestrates phases), **Change project versions** (reusable version bump), and **Publish to Clojars** (reusable deploy; see [`docs/releasing.md`](docs/releasing.md)).
- GitHub Actions CI (`.github/workflows/ci.yml`) running automated checks on
  pushes and pull requests.
- `lein skeptic -p` / `--porcelain` for newline-delimited JSON output (one
  object per line) via `skeptic.output.porcelain`, documented in the README.
- `lein skeptic --profile` for optional CPU, memory, and wall-clock profiling;
  when combined with `--porcelain`, the profile summary is written to stderr so
  stdout stays JSONL-only.
- Optional project `.skeptic/config.edn` with `:exclude-files` (root-relative
  globs that skip loading and checking matched paths) and `:type-overrides`
  (Plumatic Schema forms evaluated with `schema.core` in scope and merged into
  collected declarations, including `:output`-only overrides), wired through
  `skeptic.config`.
- Fine-grained check controls: `:skeptic/ignore-body` and `:skeptic/opaque` on
  `s/defn` attribute maps, and `^{:skeptic/type T}` on expressions (see README
  *Suppressing checks*); the expression type pin is applied through
  `skeptic.analysis.annotate.api`.
- `AGENTS.md` for contributors: namespace map, type-domain versus schema-domain
  rules, no re-exports, and API boundaries for `skeptic.analysis.annotate.api`,
  `skeptic.analysis.bridge`, and `skeptic.analysis.cast`.
- An expanded user-facing `README.md` that explains what Skeptic checks, how
  the plugin works, how to install it, how to interpret its reports, and where
  to find the algorithm reference (including later sections on configuration,
  JSONL output, suppressions, and building from source); maintainer release
  steps live in `docs/releasing.md`.
- A new `--analyzer` CLI flag to print analyzer output while inspecting a
  namespace.
- A new `skeptic/docs/blame-for-all.md` reference document describing the
  directional blame-style cast algorithm used by the checker.
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
- Further split analysis and checking across focused namespaces
  (`skeptic.analysis.annotate.*` with `skeptic.analysis.annotate.api`,
  bridge/cast/inconsistence modules, `skeptic.checking.pipeline`), with continued
  cast and blame-path refinements aligned to `docs/blame-for-all.md`.
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
