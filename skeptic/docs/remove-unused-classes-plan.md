# Remove Unused Classes Plan

## Summary

This cleanup should start from the current repo state, not from earlier terminology. In the current tree there are no checked-in production `deftype` or `defrecord` definitions for the old object-form semantic types.

The goal of this cleanup is therefore narrow:

- remove only references to absent object-form semantic-type classes
- do not disturb live runtime behavior that still has production callers and tests

The remaining class-related code in production is the `clojure.lang.Var$Unbound` normalization path in `localize-value*`, and current tests exercise it. That path is live and out of scope for "remove unused classes".

Preserve the actual two-domain design:

- literal Plumatic schemas at the boundary
- tagged-map semantic types internally

## Current Evidence

- There are no checked-in production object-form semantic-type classes such as `GroundT`, `FnMethodT`, `UnionT`, `MapT`, or `ValueT` under `skeptic/src`.
- The only remaining production callers of `skeptic.analysis.types/same-class-name?` and `skeptic.analysis.types/read-instance-field` are in the live `clojure.lang.Var$Unbound` branch of `skeptic.analysis.bridge.localize/localize-value*`.
- Current `localize-value*` no longer contains the earlier explicit class-name dispatch over absent semantic-type classes. It now operates on tagged-map schema wrappers from `schema_base.clj`, canonical tagged-map semantic types from `types.clj`, runtime vars, collections, and plain values.
- Current tests exercise bound-var, unbound-var, raw unbound-root, and recursive-var normalization in `skeptic.analysis.bridge-test/raw-schema-var-normalization-test`.
- There are no current tests that fabricate absent compatibility classes solely to exercise deleted object-form semantic-type paths.

## Planned Changes

### 1. Re-audit before editing

- Re-check the current definitions of:
  - `skeptic.analysis.bridge.localize/localize-value*`
  - `skeptic.analysis.types/same-class-name?`
  - `skeptic.analysis.types/read-instance-field`
  - `skeptic.analysis.schema-base/*schema?` helpers
- Re-check the current tests for any fabricated compatibility-only classes before editing.

### 2. Remove only genuinely dead class references

- Remove only code that is both:
  - tied to absent object-form semantic-type classes, and
  - no longer part of any live production behavior or test obligation
- Do not remove `same-class-name?` or `read-instance-field` while they remain required by the live unbound-var normalization path.
- Do not refactor the `clojure.lang.Var$Unbound` path just to express it differently.

### 3. Keep tests aligned with the actual live surface

- If any tests are found that fabricate compatibility-only classes for absent object-form paths, remove or rewrite them.
- Keep tests for the behavior that remains live:
  - literal Plumatic schema boundary handling
  - tagged-map semantic type handling
  - var, unbound-var, and recursive-var handling

### 4. Tighten docs and naming after the re-audit

- Update docs, comments, and naming so they no longer imply that:
  - absent object-form semantic-type classes still exist in production
  - the live `Var$Unbound` path is dead compatibility code to be removed as part of this cleanup
- Keep the language precise:
  - schemas = literal Plumatic schemas
  - types = internal tagged-map semantic types

## Test Plan

- Re-run the focused live-path coverage first:
  - `skeptic.analysis.bridge-test/raw-schema-var-normalization-test`
- If code tied to absent class compatibility is removed, run the surrounding regression suites:
  - `skeptic.analysis.bridge-test`
  - `skeptic.analysis.type-ops-test`
  - `skeptic.analysis.cast-test`
  - `skeptic.analysis.map-ops-test`
  - `skeptic.inconsistence.display-test`
  - `skeptic.inconsistence.report-test`
- Acceptance criteria:
  - no production code mentions absent object-form semantic-type classes
  - no tests fabricate absent compatibility classes
  - bound, unbound, and recursive var normalization still pass exactly as they do now
  - schema-boundary and tagged-map type behavior remain unchanged

## Assumptions

- "Unused classes" in the current repo means absent object-form semantic-type classes, not any live runtime class used by current behavior.
- The `clojure.lang.Var$Unbound` path remains live unless a separate change intentionally drops unbound-var placeholder support.
- Another agent may continue renaming away `schema` language, so implementation should re-read the touched files immediately before editing and prefer the current names in the tree over names from earlier plans or conversation.
