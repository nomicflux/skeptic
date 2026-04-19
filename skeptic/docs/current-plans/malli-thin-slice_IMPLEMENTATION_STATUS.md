# Phase 1: Malli :malli/schema Discovery - Implementation Status

## Phase Number
Phase 1

## Files Modified
1. `src/skeptic/malli_spec/collect.clj` — Malli spec collection mirror of Schema-side implementation
2. `test/skeptic/malli_spec/collect_test.clj` — Comprehensive test coverage

## Implementation Details

### src/skeptic/malli_spec/collect.clj
- **Namespace**: `skeptic.malli-spec.collect`
- **Aliases**: `skeptic.analysis.schema-base :as sb`, `skeptic.analysis.malli-spec.bridge :as amb`
- **Functions**:
  - `malli-declaration-error-result` (11 lines) — Helper to format error maps in :malli-declaration phase
  - `ns-malli-spec-results` (19 lines) — Public: walks ns-interns, admits malli specs, returns {:entries {} :errors []}
  - `ns-malli-specs` (3 lines) — Public: returns just the :entries map

### test/skeptic/malli_spec/collect_test.clj
- **Namespace**: `skeptic.malli-spec.collect-test`
- **Test Fixtures**:
  - `demo-fn` — Function with `:malli/schema [:=> [:cat :int] :int]` metadata
  - `plain-fn` — Function without malli metadata (skipped)
- **Test Cases**:
  1. `discovers-vars-with-malli-schema-metadata` — Validates entry shape and :errors []
  2. `skips-vars-without-malli-schema-metadata` — Verifies plain-fn not in entries
  3. `ns-malli-specs-returns-just-entries` — Confirms ns-malli-specs delegates correctly

## Test Results
```
Ran 3 tests containing 5 assertions.
0 failures, 0 errors.
```

## Lint Status
```
linting took 204ms, errors: 0, warnings: 0
```

## Full Test Suite Status (All Tests)
```
Ran 289 tests containing 1580 assertions.
0 failures, 0 errors.
```

## Code Quality Checklist
- [x] All functions < 20 lines
- [x] No dead code or TODOs
- [x] No future-proofing abstractions
- [x] 100% test pass rate
- [x] 0 lint errors/warnings
- [x] Terminology: "malli-spec" used consistently (not "schema" for Malli)
- [x] Error shape mirrors Schema-side with :phase :malli-declaration
- [x] Reduction pattern mirrors ns-schema-results from schema/collect.clj
- [x] qualified-var-symbol utility used from schema-base
- [x] admit-malli-spec wrapped via bridge alias

## Observations
- Pattern faithfully mirrors `skeptic.schema.collect/ns-schema-results` structure
- Error accumulation and entry building follow the same reduce-over-interns approach
- Malli spec admission happens at boundary via `amb/admit-malli-spec` which calls `(m/form (m/schema value))`
- All vars without `:malli/schema` metadata are silently skipped as specified
- Macros are filtered out via `:macro (meta v)` check

---

# Phase 2: Malli :=> Function Signature Conversion - Implementation Status

## Phase Number
Phase 2

## Files Modified
1. `src/skeptic/analysis/malli_spec/bridge.clj` — Added `malli-leaf->type`, `function-shape?`, replaced `malli-spec->type` body
2. `test/skeptic/analysis/malli_spec/bridge_test.clj` — Replaced stub test with two new deftests

## Implementation Details

### src/skeptic/analysis/malli_spec/bridge.clj
- **Function**: `malli-leaf->type` (9 lines, private) — Maps Malli leaf values to semantic types using closed table:
  - `:int` → `(at/->GroundT :int 'Int)`
  - `:string` → `(at/->GroundT :str 'Str)`
  - `:keyword` → `(at/->GroundT :keyword 'Keyword)`
  - `:boolean` → `(at/->GroundT :bool 'Bool)`
  - `:any` → `at/Dyn`
  - anything else → `at/Dyn`
- **Function**: `function-shape?` (6 lines, private) — Checks if canonical form matches `[:=> [:cat & inputs] output]` pattern
- **Function**: `malli-spec->type` (12 lines, public, replaced) — Now converts `[:=> [:cat ...] ...]` to `FunT` with single method, falls back to `Dyn` for non-=> shapes

### test/skeptic/analysis/malli_spec/bridge_test.clj
- **Test**: `admit-malli-spec-accepts-malli-values-and-rejects-others` — KEPT unchanged
- **Test**: `malli-spec->type-converts-=>-with-primitive-leaves` (NEW) — Validates:
  - `[:=> [:cat :int] :int]` → single-input int-to-int function
  - `[:=> [:cat :string :keyword] :boolean]` → two-input function returning bool
  - `[:=> [:cat :any] :any]` → all-Dyn function
- **Test**: `malli-spec->type-falls-back-to-dyn-for-non-=>-shapes` (NEW) — Validates:
  - `[:map [:x :int]]` → `Dyn`
  - `[:vector :int]` → `Dyn`
  - `:int` → `Dyn`

## Test Results
```
Ran 290 tests containing 1584 assertions.
0 failures, 0 errors.
```

## Lint Status
```
linting took 1322ms, errors: 0, warnings: 0
```

## Code Quality Checklist
- [x] All functions < 20 lines (malli-leaf->type: 9, function-shape?: 6, malli-spec->type: 12)
- [x] No dead code or TODOs
- [x] No future-proofing abstractions
- [x] 100% test pass rate (290 tests)
- [x] 0 lint errors/warnings
- [x] Terminology: `:malli/schema` keyword preserved verbatim; "malli-spec" domain used consistently
- [x] Mirrors Schema-side analogue `primitive-ground-type` in style (cond over leaf value)
- [x] Helper extracted to keep main function focused and readable
- [x] Shape detection delegated to private `function-shape?` for clarity

## Observations
- `malli-leaf->type` mirrors the spirit of `skeptic.analysis.bridge/primitive-ground-type` with cond dispatch
- Function shape detection is explicit and readable: checks vector length, first element, nested cat structure
- `malli-spec->type` keeps the public contract: calls `admit-malli-spec` (which throws on invalid input), then branches on shape
- No defensive coding beyond the inherent shape check — if data is wrong, let it fail naturally
- All three private/public functions fit comfortably in <15 lines target
- Existing `admit-malli-spec` and `malli-spec-domain?` unchanged as specified

---

# Phase 3: Conflict Policy + Pipeline Regression - Implementation Status

## Phase Number
Phase 3

## Files Modified
1. `src/skeptic/typed_decls.clj` — One-line merge order flip in `typed-ns-results`
2. `AGENTS.md` — Two honesty updates: `malli-spec.collect` bullet and MalliSpec Domain paragraph
3. `test/skeptic/checking/pipeline/malli_test.clj` — New pipeline regression test

## Implementation Details

### src/skeptic/typed_decls.clj
- **Change**: `(merge schema-entries malli-entries)` → `(merge malli-entries schema-entries)` in `typed-ns-results`
- **Semantics**: Schema wins on key collision. When a var has both `:schema` (Plumatic) and `:malli/schema` (Malli) declarations, the schema-side desc is kept. The winning desc has `:schema` not `:malli-spec`, so `(some? malli-spec)` is false → falls to schema branch in `desc->typed-entry`. Correct.

### AGENTS.md
- **Bullet (a)**: Updated `skeptic.malli-spec.collect` description from "stub returning empty results" to accurate description of real `:malli/schema` var-metadata discovery.
- **Paragraph (b)**: Replaced "wired in but stubbed" MalliSpec Domain paragraph with honest slice status: discovery scope, conversion table, conflict policy, and explicit deferred list.

### test/skeptic/checking/pipeline/malli_test.clj
- **Pattern**: Mirrors `namespace_test.clj` inline `check-s-expr` style (no fixture catalog required)
- **Setup**: `intern` places `demo-fn` with `{:malli/schema [:=> [:cat :int] :int]}` metadata into the test namespace so the analyzer can resolve it
- **Dict**: Built via `malli-fun-dict-entry` helper which extracts `:arglists` and `:output-type` from the `FunT` returned by `amb/malli-spec->type` — needed because `typed-callable?` requires `:arglists` to fire input checking
- **Test**: `malli-=>-int-mismatch-on-keyword-arg` — passes `(demo-fn :nope)` where `:int` expected; asserts exactly 1 result with non-empty `:errors`

## Test Results
```
Ran 291 tests containing 1586 assertions.
0 failures, 0 errors.
```

## Lint Status
```
linting took 1419ms, errors: 0, warnings: 0
```

## Slice Complete

The vertical slice is fully implemented across three phases:
- Phase 1: `:malli/schema` var-metadata discovery (`ns-malli-spec-results`)
- Phase 2: `[:=> [:cat & inputs] output]` → `FunT` conversion with five-primitive leaf table
- Phase 3: Schema-wins conflict policy via merge order; pipeline regression proving Keyword-vs-Int mismatch fires end-to-end

## Deferred Items
- Registry-based discovery (`malli.core/function-schemas`, `m/=>`, `malli.experimental/defn`)
- Non-primitive Malli leaves (`:map`, `:vector`, `:maybe`, `:or`, registry refs, etc.)
- Nested `:=>` shapes
- Conflict reporting (currently silent)
- Multi-arity malli-spec admission
- JSONL Malli kinds
- `.skeptic/config.edn` Malli surface
- `:skeptic/type` Malli interpretation
