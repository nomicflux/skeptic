# ADR 0002 — Totality at decode boundaries

Status: accepted (2026-06-11)

## Context

The host-side wire decoder (`skeptic.schema.wire/decode-record`) was a
closed `case` over 17 `schema.core.*` class names that threw on anything
else. Plumatic schema records are an open domain — `(s/protocol ...)` and
any project-defined `Schema` implementation (e.g. abstract-map's
`AbstractSchema`) lie outside any enumerable vocabulary. Each unenumerated
construct surfaced as a fresh declaration-phase crash on the first project
that used it. The same shape recurred elsewhere: predicate symbol tables,
head-name sets, a rejecting `:else` in leaf casts.

## Decision

A boundary that consumes an open domain must be **total**: the default arm
carries explicit, stated semantics instead of throwing. For schema record
decode the semantics are gradual-typing's: an unintelligible declaration
proves nothing, so an unknown record admits as `(s/named s/Any '<class>)` —
Dyn with the record's class name preserved for display. Precise arms for
known constructs are enrichment, never crash fixes.

## Consequences

- Declaration admission is lossy-but-total; findings lose precision (never
  soundness) on unknown constructs.
- Latent bugs previously masked by upstream crashes become reachable (the
  `decode-one` arity bug surfaced exactly this way); they are bugs to fix,
  not reasons to keep the throw.
- Closed enumerations over open domains elsewhere (e.g. duplicate predicate
  registries) are audit targets under this ADR.
