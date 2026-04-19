# Malli Domain-Purity — Implementation Status

## Phase

Phase 1 complete. Phase 2 complete.

## Files Modified

### Source
- `src/skeptic/schema/collect.clj` — removed `dynamic-raw`, `dynamic-arg-entry`, `dynamic-arglists`, `admit-dynamic-desc`, `:dynamic` branch in `admit-declaration-from-extract`; rewrote `extract-raw-declaration` to return nil for unannotated and opaque vars
- `src/skeptic/typed_decls.clj` — restored merge order to `(merge malli-entries schema-entries)` so Schema wins over Malli on conflict
- `src/skeptic/checking/pipeline.clj` — extended `ignored-body-def?` to check var metadata `:skeptic/ignore-body` and `:skeptic/opaque` directly, so body-skip works for unannotated and opaque vars absent from dict

### Tests updated (dynamic-branch contract replaced with domain-pure contract)
- `test/skeptic/typed_decls_test.clj` — rewrote `typed-ns-entries-build-callable-dynamic-and-varargs-entries` → `typed-ns-entries-annotated-present-unannotated-absent`
- `test/skeptic/analysis/normalize_test.clj` — rewrote `declaration-index-contract-test` to use annotated vars and assert unannotated vars absent
- `test/skeptic/schema/collect_test.clj` — rewrote `ns-schemas-reads-auto-resolved-keywords-in-target-ns` → `ns-schemas-only-contains-annotated-vars`
- `test/skeptic/checking/pipeline/fixture_flags_test.clj` — updated `opaque-fixtures` to assert `opaque-fn` absent from dict rather than present with Dyn output-type

### Status doc (new)
- `docs/current-plans/malli-domain-purity_IMPLEMENTATION_STATUS.md`

## Test Counts

291 tests, 1587 assertions, 0 failures, 0 errors.

## Phase 1 Honesty Proof

Temporarily reverted `typed_decls.clj` to `(merge schema-entries malli-entries)` and ran `lein test`.
Result: **0 failures, 0 errors** — no existing test covers the Schema-wins-over-Malli conflict case.
The conflict test is a Phase 2 deliverable. The merge order is correct per the AGENTS.md spec
("Schema wins via merge order": last writer wins in `merge`, so schema-entries must be the last argument).
Re-applied `(merge malli-entries schema-entries)` after recording this result.

## Phase 1 Architecture Proof

When an unannotated callable in a checked namespace is referenced at a call site, the following happens:

1. `typed-ns-entries` calls `collect/ns-schema-results`, which calls `extract-raw-declaration` for every
   var in the namespace. For the unannotated var, `(:schema m)` is nil, so `extract-raw-declaration`
   returns nil. The `if-let` in `ns-schema-results` skips it. The var has no entry in `schema-entries`.
   `mcollect/ns-malli-spec-results` likewise skips it (no `:malli/schema` metadata). So `typed-ns-entries`
   returns a map with no entry for that var.

2. At the call site, `skeptic.checking.pipeline` passes `dict` (built from `typed-ns-entries`) into
   `annotate-var-like` in `src/skeptic/analysis/annotate/base.clj:39-43`. `annotate-var-like` calls
   `ac/lookup-entry dict ns node`. Since the var has no entry in `dict`, `lookup-entry` returns nil.

3. The `or` at `base.clj:42-43` falls through to `{:type at/Dyn}`. The node is annotated with `Dyn`.
   All call-site type checks against that node use `Dyn`, which casts successfully against anything.
   No false positives are reported for calls to unannotated functions.

---

## Phase 2: Regression Test for Schema-Wins-Over-Malli Conflict

### Files Modified

#### New
- `test/skeptic/test_examples/conflict.clj` — fixture with `dual-annotated-fn` carrying both `^{:schema (s/=> s/Int s/Int)}` and `^{:malli/schema [:=> [:cat :string] :string]}`

#### Updated
- `test/skeptic/typed_decls_test.clj` — added `schema-wins-on-malli-conflict` deftest

#### Status doc
- `docs/current-plans/malli-domain-purity_IMPLEMENTATION_STATUS.md` — Phase 2 entry

### Test Counts

292 tests, 1589 assertions, 0 failures, 0 errors.

### Phase 2 Honesty Proof

Temporarily reverted merge order in `typed_decls.clj` from `(merge malli-entries schema-entries)` to `(merge schema-entries malli-entries)`. Ran `lein test :only skeptic.typed-decls-test/schema-wins-on-malli-conflict`.

**Result: 2 failures** — the test correctly failed because with the reverted merge order, Malli's `Str → Str` type overrides Schema's `Int → Int` type. The assertion expected `(T (s/=> s/Int s/Int))` but received the Malli-derived `Str → Str` type instead.

Error messages:
```
FAIL in (schema-wins-on-malli-conflict) (typed_decls_test.clj:81)
expected: (= (T (s/=> s/Int s/Int)) (:type dual-fn))
  actual: (not (= ... Str/Str type ... Int/Int type ...))

FAIL in (schema-wins-on-malli-conflict) (typed_decls_test.clj:82)
expected: (not= (T (s/=> s/Str s/Str)) (:type dual-fn))
  actual: (not (not= ... Str/Str type ...))
```

Re-applied correct merge order `(merge malli-entries schema-entries)`. Test now passes: **0 failures, 0 errors**.

This proves the merge order implementation is correct and the test properly validates the conflict-resolution behavior.
