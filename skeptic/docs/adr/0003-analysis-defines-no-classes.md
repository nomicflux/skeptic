# ADR 0003 — Worker analysis defines no classes

Status: accepted (2026-06-11)

## Context

tools.analyzer.jvm's `parse-deftype*` `eval`s a method-less skeleton class
under the record's fixed name during plain `analyze` (`-deftype`, tagged
HACK upstream), and `gen-interface` — present in every `defprotocol`
expansion — loads the interface class at macroexpansion time. Clojure's
DynamicClassLoader cache is process-wide and name-keyed, and constant-pool
resolution is lazy, so re-defining a class during analysis retroactively
poisons already-loaded code (reproduced outside Skeptic: AbstractMethodError
on `schema.core.Record/withMeta` after analyzing `schema/core.cljc`). The
prior guard — skipping forms whose head is literally named
`defrecord`/`deftype` — tested a syntax property; class definition is a
property of macroexpansion, and wrapper macros (schema's
`defrecord-schema`) sail past.

## Decision

The worker's analysis must never (re)define a JVM class. Enforcement is at
the language primitives, through tools.analyzer's documented `:bindings`
extension point (`skeptic.worker.analyzer-clj/analysis-class-safety-bindings`):

- `deftype*` parses as a nil-constant stub (the real class exists —
  require-before-analyze).
- `gen-interface` forms macroexpand to a no-op.
- `reify*` keeps the default parse: its analysis-time class name is
  gensym'd (`reify__NNNN`) and collides with nothing.

## Consequences

- Accepted coverage loss: inline method bodies of macro-wrapped record
  definitions are not checked (plain `defrecord`/`deftype` bodies were
  already skipped by name).
- A lossless variant exists (neutering `-deftype` itself) but couples to a
  t.a.jvm internal annotated `;; HACK`; rejected for now.
- The override surface (`#'ana/parse`, `#'ana/macroexpand-1`) is a
  compatibility surface across whatever t.a.jvm version the project pins
  (project-first classpath); floor-version coverage belongs in the
  conformance corpus (ADR 0001).
