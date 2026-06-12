# ADR 0001 — Conformance fixture corpus

Status: accepted (2026-06-11)

## Context

Skeptic's correctness condition is universal: behave exactly like the target
project's own runtime, for every project, across every channel of global JVM
state (data readers, classloaders, protocol impl maps, var roots). Every
recent field failure (bespoke data readers, Clojure 1.8 pins, wrapper macros
around `deftype`, `s/protocol` signatures, annotated rest-destructures,
`(s/pred map?)` parameters) came from a project outside the regression
corpus, whose members are uniformly small, modern, and idiomatic. Invariants
discovered in the field were recorded as prose (AGENTS.md) and scratch
probes (`.scratch/reader-probes/`), neither of which executes in CI, so they
regressed silently.

## Decision

Every field-failure family becomes a **checked-in minimal fixture project**
driven by the real plugin/worker path in the harness tests, as a gate. A
post-mortem is not closed until its reproduction is a fixture. Fixture #1 is
the bespoke-reader project (tagged literal whose reader is registered via
`alter-var-root` at load, plus a `data_readers.clj` variant).

## Consequences

- The corpus tests the union of observed Clojure usage, not the
  intersection; "green on the corpus" gains meaning monotonically.
- Each fixture adds harness runtime; acceptable — a fixture is one tiny
  project.
- Scratch probes are promoted or deleted; `.scratch/` is never the resting
  place of a contract.
