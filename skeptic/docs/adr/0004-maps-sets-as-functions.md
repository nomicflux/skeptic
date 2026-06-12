# ADR 0004 — Maps and sets cast as functions

Status: accepted (2026-06-11)

## Context

Clojure maps and sets implement IFn, and Plumatic's own `(s/=> ...)`
checker validates with `ifn?`, so a map literal flowing into a declared
function schema is valid under the contract's own semantics
(plumatic/schema itself passes `{}` as a `GeneratorWrappers`). The cast
engine treated MapT/SetT sources against FunT targets as unconditional
mismatches.

## Decision

In the cast engine (`skeptic.analysis.cast.function`):

- A MapT source casts against a FunT target as
  `(=> union-of-value-types union-of-key-types)`.
- A SetT source casts as `(=> Bool union-of-member-types)`.
- These are really dependent functions; the dependency is not modeled yet.
- Empty unions follow `ato/union-type`'s convention (Dyn), so `{}` casts as
  `(=> Dyn Dyn)`.

The synthesized FunT delegates to the existing function cast, so
contravariant domain flipping, blame paths, and arity diagnostics are
unchanged machinery.

## Consequences

- Wrong value/key types remain proveable mismatches (a `{:a Str}` map
  against `(=> Int Keyword)` still fails covariantly).
- Maps called at arities other than 1 (`(m k default)`) are not modeled;
  a 2-arity FunT target reports an arity mismatch.
- Keywords/vectors as IFn are deliberately not modeled; extending them is a
  new decision, not an extrapolation of this one.
