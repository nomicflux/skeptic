# Malli `:enum` Implementation Status

## Phase 1 — Bridge recognizes `:enum` (complete)

- Edited `skeptic/src/skeptic/analysis/malli_spec/bridge.clj`:
  - Added `enum-shape?` predicate.
  - Added `enum-values` helper (strips optional properties map).
  - Added `enum-shape?` branch in `form->type` routing to `(ato/union-type prov (mapv #(ato/exact-value-type prov %) (enum-values form)))`.
- Created `skeptic/test/skeptic/analysis/malli_spec/bridge_enum_test.clj` with four deftests:
  - `enum-with-two-keyword-members`
  - `enum-with-single-member-short-circuits`
  - `enum-with-properties-ignores-properties`
  - `enum-with-heterogeneous-members`
- `lein test`: 467 tests / 2079 assertions / 0 failures / 0 errors.
- `clj-kondo --lint src test`: 0 errors / 0 warnings.

## Phase 2 — End-to-end: fixtures, pipeline regression, typed-decls (complete)

- Edited `skeptic/test/skeptic/test_examples/malli_contracts.clj`:
  - Added `enum-output-success` (success case: declared `[:enum :ok :bad]`, body returns `:ok`).
  - Added `enum-input-flows-to-string` (failure case: declared `[:=> [:cat [:enum :ok :bad]] :string]`, body `[x] x`).
- Created `skeptic/test/skeptic/checking/pipeline/malli_enum_test.clj`:
  - `enum-output-success-passes` — empty errors on success fixture.
  - `enum-input-flows-to-string-fails` — pipeline emits at least one error for the failure fixture.
- Edited `skeptic/test/skeptic/typed_decls/malli_test.clj`:
  - Added `ato` require.
  - Added `desc->type-enum-returns-union-of-exact-values`.
  - Added `desc->type-enum-in-=>-output`.
- `lein test`: 471 tests / 2084 assertions / 0 failures / 0 errors.
- `clj-kondo --lint src test`: 0 errors / 0 warnings.

### Fixture direction note

Initial Phase 2 plan proposed a bad fixture `enum-output-bad [_x] :not-an-enum-member` — body inferred as `GroundT(Kw)`, declared output `[:enum :ok :bad]` → `UnionT(ValueT(Kw,:ok),ValueT(Kw,:bad))`. The pipeline emitted 0 results on this. Per `test/skeptic/inconsistence/report_test.clj:369`, Schema's `check-cast GroundT target=enum-of-same-ground` is asserted OK — consistent with the compatibility calculus in `docs/blame-for-all.md`. This is not a gap in the Malli bridge; it is the documented cast semantics shared with Schema.

The fixture was re-shaped to the direction Schema catches (line 378: `check-cast enum target=narrower-ground` NOT OK): `enum-input-flows-to-string` has source=enum-union, target=`:string`, detected via source-union failure on every keyword-valued member.

## Phase 3 — Reference doc update (complete)

- Edited `skeptic/docs/malli-reference.md`:
  - Added `[:enum & values]` bullet to the handled forms in the conversion-runner list, mirroring the Schema-side behavior at `src/skeptic/analysis/bridge.clj:386-387`.
  - Removed `:enum` from the stubbed-now list and extended the "outside" clause to include `:enum`.
- `lein test`: 471 / 2084 / 0 / 0.
- `clj-kondo --lint src test`: 0 / 0.

Remaining Malli stubs unchanged: `:map`, `:tuple`, `:vector`, `:sequential`, `:set`, `:fn`, `:and`, refs, refinement leaves, `:->`, `:function`, `:catn`, repetition operators.

## Phase 3 addendum — ns-load bug fix

- Edited `skeptic/src/skeptic/malli_spec/collect.clj` `ns-malli-spec-results`: added `(require ns)` before `(the-ns ns)`. Without this, calling the fn on a not-yet-loaded ns threw `No namespace ... found`, surfaced when the three-ns smoke selector ran `skeptic.typed-decls.malli-test/typed-ns-malli-results-entries` in isolation.
- Smoke: `lein test :only skeptic.analysis.malli-spec.bridge-enum-test skeptic.checking.pipeline.malli-enum-test skeptic.typed-decls.malli-test` — 12 / 25 / 0 / 0.
- Full: 471 / 2084 / 0 / 0. Lint clean.
