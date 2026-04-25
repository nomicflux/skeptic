# Phase 1: `localize-value` becomes schema-boundary-only — COMPLETE

## Changes Made

### File: `skeptic/src/skeptic/analysis/bridge/localize.clj`

1. **Deleted** the entire private function `localize-semantic-type` (previously L48–L119, ~72 lines).
   - This function was the sole handler for semantic-type rebuilding during localization.
   - It recursively rebuilt all semantic-type records by extracting and localizing their nested fields.

2. **Replaced** the semantic-type branch in `localize-value*` (previously L148–L149):
   - **Before:** `(at/semantic-type-value? value) (localize-semantic-type value seen-vars)`
   - **After:** `(at/semantic-type-value? value) value`
   - Semantic-type values are now passed through unchanged.

3. **Removed** the unused `[skeptic.provenance :as prov]` import (L4).
   - `prov/of` was only called within `localize-semantic-type`, which is now deleted.

### File: `skeptic/test/skeptic/analysis/bridge_test.clj`

1. **Added** a new regression test: `localize-value-preserves-semantic-types-and-does-not-walk-payload-test` (after L162).
   - Creates lazy `(map ...)` payloads wrapped in `AdapterLeafT` and `ValueT` records.
   - Asserts `identical?` round-trip through `abl/localize-value` (proves no rebuild).
   - Asserts the lazy payload was never realized via `(false? @realized?)`.

## Test Results

### Targeted Tests
```
lein test :only skeptic.analysis.bridge-test
Ran 28 tests containing 85 assertions.
0 failures, 0 errors.
```

New test `localize-value-preserves-semantic-types-and-does-not-walk-payload-test` passes.

### Full Test Suite
```
lein test
Ran 504 tests containing 2352 assertions.
0 failures, 0 errors.
```

All existing tests continue to pass:
- `raw-schema-var-normalization-test` (L64) — Var resolution in schemas still works.
- `recursive-collections-reduce-by-construction-test` (L91) — Recursive collection handling unchanged.
- `localize-and-strip-derived-types-test` (L142) — Semantic types in raw maps preserved (equality).

### Lint Results
```
clj-kondo --lint src/skeptic/analysis/bridge/localize.clj test/skeptic/analysis/bridge_test.clj
linting took 239ms, errors: 0, warnings: 0
```

## Verification Against Plan

1. ✓ **Subtraction:** `localize-semantic-type` deleted, semantic-type branch simplified to no-op.
2. ✓ **Schema-boundary-only:** `localize-value` no longer rebuilds semantic types; only handles schema domain (nil, Vars, custom-schema maps, raw collections).
3. ✓ **Preservation of other branches:** nil, unbound Var, Var, custom-schema, raw-collection, and `:else` branches remain untouched.
4. ✓ **Identity preservation:** New test uses `identical?` to prove semantic-type records are not rebuilt.
5. ✓ **Lazy payload not realized:** New test asserts lazy `(map ...)` inside `:adapter-data` / `:value` was never forced.
6. ✓ **No dead code:** `localize-semantic-type` completely removed; no obsolete code paths remain.
7. ✓ **Functions <20 lines:** All functions in modified scope remain well under 20 lines; `localize-value*` is 20 lines.
8. ✓ **No future-proofing:** Only necessary deletions and replacements; no new abstractions or parameters.

## Deviation from Plan

None. All deliverables completed as specified.

## Ready for Phase 2

The schema-boundary enforcement is complete. `localize-value` now:
- Returns `nil`, bound Vars (resolved), unbound Vars (placeholders), custom-schema maps (localized), raw collections (recursively localized), and **semantic-type values (unchanged)**.
- Is safe to call as a boundary function before `canonicalize-schema*`.

Phase 2 will remove the redundant top-of-fn `localize-value` call from `canonicalize-schema*` and add extraction-site localization for Plumatic wrapper children.
