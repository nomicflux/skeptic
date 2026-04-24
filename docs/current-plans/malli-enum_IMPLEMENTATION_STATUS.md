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
