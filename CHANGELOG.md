# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- An expanded user-facing `README.md` that explains what Skeptic checks, how
  the plugin works, how to install it, how to interpret its reports, and where
  to find the algorithm reference.
- A new `--analyzer` CLI flag to print analyzer output while inspecting a
  namespace.
- A new `skeptic/docs/blame-for-all.md` reference document describing the
  directional blame-style cast algorithm used by the checker.
- Richer mismatch reports with source expressions, file and line information,
  enclosing forms, and affected inputs.
- Output-schema validation for function bodies, so Skeptic now checks declared
  return schemas in addition to call-site argument compatibility.
- Static-call coverage and tests for schema-sensitive operations such as `get`
  and `merge`.

### Changed

- Reworked the checker to analyze forms through `clojure.tools.analyzer` ASTs
  instead of the older macroexpansion-driven pipeline.
- Switched mismatch checking to a directional cast engine that reports cast
  rules, blame side, and blame polarity instead of only undirected schema
  incompatibility.
- Switched namespace reading to `clojure.tools.reader` with source logging so
  reports can preserve source text and location metadata.
- Canonicalized schema handling across checker internals so built-in classes,
  Schema aliases, map keys, and maybe types compare more consistently.
- Expanded namespace schema collection to inspect schematized interned vars
  instead of only public vars.

### Fixed

- Preserved auto-resolved keywords from the target namespace when collecting
  schemas and checking forms.
- Reduced nested helper calls and map lookups to their final inferred schemas
  before reporting mismatches.
- Improved output mismatch rendering for map schemas so reported types are
  canonical and easier to read.
- Stabilized self-analysis and internal checking so recursive or self-hosted
  runs no longer depend on loader-sensitive semantic values embedded in
  canonical analysis data.
