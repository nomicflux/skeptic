# Conditional Nested Sum Narrowing Implementation Status

## Pre-state baseline

For the repro `nested-conditional-repro`:
1. `(:k x-val)` annotated by `annotate-keyword-invoke` produces a node with
   `:origin {:kind :map-key-lookup :root <:root x-val> :key-query :x}`.
2. `let [{:keys [k]} (:x x)]` desugars; `k`'s init is `(:k <gensym>)` where
   `<gensym>`'s entry origin is the inner `:map-key-lookup` from step 1.
3. `annotate-keyword-invoke` for `(:k <gensym>)` calls
   `map-key-lookup-origin`. `projection-root-local` walks `prior-local-alias`
   (LOCAL→LOCAL only), reaches `<gensym>` (its init is `:keyword-invoke`,
   not `:local`), and `local-root-origin` returns a fresh `:root` keyed by
   `<gensym>`. The `:x` queried earlier is LOST.
4. `k`'s entry `:origin` is therefore `:map-key-lookup` rooted at `<gensym>`
   with `:key-query :k` — NOT rooted at `x` with path `[:x :k]`.
5. Guard `(= k "b")` reaches `equality-value-assumption`; target `k` is a
   local-node; `local-root-origin` returns the `:root` origin keyed by
   `k`'s symbol. The assumption is `:value-equality` rooted at `k`. It
   refines `k` only; `x` is untouched.
6. `cond->>` then-branch threads `x` into `(nested-conditional-f x)`; the
   call site sees `x : NestedConditionalX` (un-narrowed), not Y. Cast fails.

## Phase 1

Test run: `lein test :only skeptic.checking.pipeline.contracts-test/nested-conditional-destructured-discriminator-narrowing`

Exit status: FAILED (as expected — red test)

Cast-site error message (head):
```
has inferred type incompatible with the expected type:

(conditional {:k "a"} {:k "b"})

The expected type corresponds to:

{:k "b"}

Path:

[:x]
```

The fixture is well-formed and analysis completes. The test fails at the call site `(nested-conditional-f G__21931)`, where the inferred type for the threaded `x` remains the full `NestedConditionalX` conditional union (both branches) instead of narrowing to `NestedConditionalY` ({:x NestedConditionalB}) based on the guard `(= k "b")`.

## Phase 2

### 2A — Path-shaped projection origins

**Deviation from plan:** `local-root-origin` was left unchanged. The plan described adding a `:map-key-lookup` pass-through there, but audit showed all consumers of `local-root-origin` need only `:root`-kind output; chain-extension is fully handled inside `map-key-lookup-origin` by reading `node-origin` on the target and conjoining to `:path` when it is already `:map-key-lookup`.

`origin.clj` — `origin-type` `:map-key-lookup` case now folds `(reduce amo/map-get-type root-type (:path origin))`.

`annotate/map_projection.clj` — `map-key-lookup-origin` reads `aapi/node-origin` on the target; if already `:map-key-lookup`, conjoins the new key-query onto `:path`; otherwise calls `projection-root-origin` and starts a fresh 1-element path.

### 2B — Call-site verification and tests

**invoke.clj / jvm.clj:** No edits required. Neither file reads `:key-query`; both call `map-key-lookup-origin` with a key-query arg and do not inspect the returned origin shape.

**`:key-query` grep:** Zero hits across `src/` and `test/`.

**New tests in `test/skeptic/analysis/origin_test.clj`:**

- `chained-keyword-invoke-yields-path-origin` — analyzes `(:k (:x x))`, finds the outer `:keyword-invoke` node, asserts origin is `:map-key-lookup` with `:root` sym `x` and 2-element path `[:x :k]`.
- `destructured-projection-binding-origin` — analyzes `(let [{:keys [k]} (:x x)] k)`, finds the `k` local with `:static-call` binding-init, asserts origin is `:map-key-lookup` with 1-element path `[:k]` and that `origin-type` resolves to `s/Str`.
- `origin-type-folds-path` — builds a `:map-key-lookup` origin manually with a 2-element path `[:x :k]` against `{:x {:k s/Str}}`, asserts `origin-type` returns `s/Str`.

**Full suite result:** 507+ tests, 1 failure — `nested-conditional-destructured-discriminator-narrowing` (Phase 1 target, still RED as expected).

**clj-kondo:** 0 errors, 0 warnings.

### 2X — Destructure seq-coercion shim recognition

**Root cause:** `clojure.core/destructure` emits a seq-coercion `:if` shim that rebinds the same gensym with init shape `(if (seq? <g>) (PHM/create (seq <g>)) <g>)`. `binding-env-entry` previously called `branch-origin` on this `:if`, producing `:branch` origin and overwriting the gensym's prior `:root`/`:map-key-lookup` origin. All downstream bindings (`:as` alias, nested `get` calls) then lost their provenance chain.

**AST table for `(let [{{:keys [k]} :x :as x} input] [x k])`:**

| # | sym | init `:op` | annotated origin (post-2X) |
|---|---|---|---|
| 0 | `map__N1` | `:local` (refers to `input`) | `:root` sym=`input` |
| 1 | `map__N1` | `:if` (outer shim) | `:root` sym=`input` (shim recognized, prior preserved) |
| 2 | `x` | `:local` (refers to `map__N1`) | `:root` sym=`input` (alias chains through) |
| 3 | `map__N2` | `:static-call` `(get map__N1 :x …)` | `:map-key-lookup` root=`input` path=`[:x]` |
| 4 | `map__N2` | `:if` (inner shim) | `:map-key-lookup` root=`input` path=`[:x]` (shim recognized) |
| 5 | `k` | `:static-call` `(get map__N2 :k …)` | `:map-key-lookup` root=`input` path=`[:x :k]` |

**Fix location:** `src/skeptic/analysis/annotate/control.clj`

- `destructure-shim?` (new, ≤10 lines): recognizes `:if` init where test is 1-arg `seq?` call, else branch is same-sym local, and binding rebinds that same sym.
- `shim-prior-origin` (new, ≤5 lines): looks up the prior gensym entry in `env` and returns its `:origin`.
- `binding-env-entry`: now receives `env` as first arg; inserts `shim-prior-origin` into the origin resolution `or` chain, after `binding-alias-origin` and before `fallback-origin`.
- `binding-alias-origin`: fixed to use `(:sym upstream-origin)` instead of `(:form init)` so alias chains preserve the original root sym through gensym indirection.
- `annotate-let-binding` and `loop-one-binding`: updated to pass `env` to `binding-env-entry`.

**Tests:**
- `destructured-projection-binding-origin` — updated: now asserts 2-element path `[:x :k]` and root sym `x` (was asserting buggy 1-element path `[:k]`).
- `destructure-as-alias-preserves-root-origin` (new) — `(let [{{:keys [k]} :x :as x} input] x)`: body `x` local has `:root` origin with sym `input`.
- `nested-destructure-double-shim-yields-full-path` (new) — `(let [{{{:keys [k]} :inner} :x :as x} input] k)`: `k` local has `:map-key-lookup` origin rooted at `input` with 3-element path `[:x :inner :k]`.

**Full suite result:** 513 tests, 1 failure — `nested-conditional-destructured-discriminator-narrowing` (Phase 1 target, still RED as expected).

**clj-kondo:** 0 errors, 0 warnings.

## Phase 3

### Extract conditional-branch narrowing helpers with drop-discriminator option

**Goal:** Extract a general-purpose conditional-branch narrowing helper that takes an explicit `{:drop-discriminator? bool}` option. Refactor the two existing case-only wrappers to call the new helpers. Behavior of existing case payload-narrowing MUST be unchanged.

**New helpers in `src/skeptic/analysis/annotate/match.clj`:**

- `narrow-conditional-by-discriminator` (15 lines: docstring + signature + let + if/union): Pick branches whose pred matches each literal in `lits` against discriminator key `kw`. Returns a union of selected branch types. With opts `{:drop-discriminator? true}`, drops the discriminator key from each picked branch.
  
- `narrow-conditional-default` (16 lines: docstring + signature + let + if/union): Default-branch counterpart. Returns the union of branch types whose preds did NOT match any of `lits`. With `{:drop-discriminator? true}`, drops the discriminator key from each picked branch.

**Refactored wrappers (both now 1-liners):**

- `case-conditional-narrow-for-lits`: now calls `narrow-conditional-by-discriminator` with `{:drop-discriminator? true}`. Public signature unchanged.
  
- `case-conditional-default-narrow`: now calls `narrow-conditional-default` with `{:drop-discriminator? true}`. Public signature unchanged.

**New unit tests in `test/skeptic/analysis/annotate/match_test.clj`:**

- `narrow-conditional-by-discriminator-drop-test` — exercises the helper with `{:drop-discriminator? true}`; asserts discriminator key is removed from returned branch type.
  
- `narrow-conditional-by-discriminator-keep-test` — exercises the helper with `{:drop-discriminator? false}`; asserts discriminator key is retained in returned branch type.
  
- `narrow-conditional-default-keep-test` — exercises the default sibling with `{:drop-discriminator? false}`; asserts discriminator key is retained.

**Verification:**

- All 5 match tests pass (2 pre-existing + 3 new).
- `lein test :only skeptic.checking.pipeline.contracts-test/handles-ab-case-routing` — PASS.
- `lein test :only skeptic.checking.pipeline.contracts-test/type-narrowing-examples` — PASS.
- Full suite: 516 tests, 1 failure — `nested-conditional-destructured-discriminator-narrowing` (Phase 1 target, still RED as expected).
- `clj-kondo --lint src/skeptic/analysis/annotate/match.clj` — 0 errors, 0 warnings.
- `clj-kondo --lint test/skeptic/analysis/annotate/match_test.clj` — 0 errors, 0 warnings.

## Phase 4

### 4A — `map_ops.clj` and `match.clj` (landed with Phase 4 commit)

**`src/skeptic/analysis/map_ops.clj`** — added helpers:
- `as-type` (coerces to type value)
- `refine-map-path-map`, `refine-map-path-union`, `refine-map-path-maybe`, `refine-map-path-conditional` (decomposed refinement cases)
- `refine-map-path-by-values` (public; forward-declared, mutually recursive via `requiring-resolve`)

**`src/skeptic/analysis/annotate/match.clj`** — `narrow-conditional-by-discriminator` and `narrow-conditional-default` parameter changed `kw → path` (vector of key-queries). New private helpers: `path-elem-key`, `path->test-map`, `path-predicate-matches-lit?`. Case wrappers updated to wrap `kw-query` in `[kw-query]`.

New tests in `map_ops_test.clj` (4) and `match_test.clj` (3 updated + 1 new).

### 4B — `origin.clj` (same commit)

**`src/skeptic/analysis/origin.clj`** edits:
- `invertible-assumption?` — added `:path-value-equality` to invertible set.
- `same-assumption?` — added `:path-value-equality` case comparing `:path` and `:values`.
- `same-assumption-proposition?` — same `:path-value-equality` case.
- `apply-assumption-to-root-type` — added `:path-value-equality` case delegating to `amo/refine-map-path-by-values`.
- `path-value-equality-assumption` (new private helper, 8 lines) — detects `:map-key-lookup` origin and produces `:path-value-equality` assumption.
- `equality-value-assumption` — rewired to call `path-value-equality-assumption` first; falls back to `:value-equality` via `local-root-origin`.

New tests in `test/skeptic/analysis/origin_test.clj`:
- `equality-value-assumption-path-shape` — verifies that `(= k "b")` where `k` is a destructured projection-local produces a `:path-value-equality` assumption with root `x`, path `[:x :k]`, values `["b"]`.
- `apply-path-value-equality-refines-root` — manually builds a `:path-value-equality` assumption, calls `apply-assumption-to-root-type`, asserts `[:x :k]` slot narrows to `(s/eq "b")`.
- `branch-local-envs-refines-x-via-nested-equality` — verifies that `origin-type` with a `:path-value-equality` assumption on `input` refines `x`'s type at path `[:x :k]` to `(s/eq "b")`.

### Phase 1 deftest result

`skeptic.checking.pipeline.contracts-test/nested-conditional-destructured-discriminator-narrowing` — **PASSES** (constraint-4 milestone GREEN).

### Full-suite result

524 tests, 2430 assertions, 0 failures, 0 errors.

### clj-kondo

0 errors, 0 warnings.

## Phase 5

### 5A — Classifier accessor-summary recognizer

Replaced `case-discriminant-expr` and the multi-branch
`case-discriminant-projection-path` (pipeline.clj) with a single helper
`discriminant-projection-path` that reads `(:origin discriminant)`
directly. Root origins carry `:sym` (not `:form`); the prior helper
had a `:form`/`:sym` field bug. Because `annotate-local`
(`base.clj:40-52`) propagates the binding entry's origin to every
`:local` reference site, no env-walking and no `:binding-init`
recovery are needed.

`predicate_descriptor.clj` reduced to a namespace shell. Phase 5B
fills its body.

Six inline unit tests added in `accessor_summary_test.clj` using
`atst/analyze-form` + `pipeline/analyzed-def-entry`:
direct-keyword-invoke, static-get, destructured (Phase 2X
inheritance), plain-get-via-inline, different-classifier-name, and
non-classifier-still-recognized-as-accessor.

The `plain-get-classifier-via-inline` deftest replaces the originally
planned "not-supported" assertion: `clojure.core/get` is inlined to
`clojure.lang.RT/get` (a `:static-call`), which `annotate-static-call`
treats identically to direct static-get — so plain `(get m :k)` is
recognized correctly. The plan's documented gap does not exist.

One follow-on edit in `jvm.clj`: the static-get origin attachment
gate widened from `(= 2 (count args))` to `(<= 2 (count args) 3)`
so the 3-arg form `(get x :k default)` also chains origins. The
default-value arg does not change the projection's provenance
(`origin` describes "this value came from looking up :k in x");
type semantics flow through unrelated code paths. This made
`static-get-with-default-yields-path-origin` (which had been failing)
pass, with no other test affected.

Test count: 524 → 530 (six new). Full suite: 530 tests, 2445
assertions, 0 failures, 0 errors. clj-kondo: 0 errors, 0 warnings.
Phase 1 deftest still GREEN.

## Phase 5B

Bridge now captures the pred-form per conditional branch. In
`conditional-branch-sources`, each `(partition 2 (rest form))` pair
becomes `{:pred-form pred :schema-source (build branch)}` instead of
just the built branch. In `conditional-import-type`, the branch-results
comprehension reads `:schema-source` for the recursive run and attaches
`:pred-form` as slot 3, producing 3-tuple ConditionalT branches
`[pred type pred-form]`.

`strip-runtime-closures` in `types.clj` updated to destructure
`[_ typ slot3]` and re-emit `[nil (strip-runtime-closures typ) slot3]`,
preserving slot 3 unchanged. `normalize-type-preserving-conditionals`
in `type_ops.clj` similarly updated to preserve slot 3 when
reconstructing ConditionalT branches.

New post-pass `enrich-conditional-descriptors` (in `pipeline.clj`)
walks every type in the dict after `preanalyzed-ns-dict` and replaces
slot 3 in ConditionalT branches with a resolved descriptor via
`predicate-form->descriptor`. The walker recurses into all type
wrappers mirroring the shape of `strip-runtime-closures`.

`predicate-form->descriptor` (new file
`src/skeptic/analysis/predicate_descriptor.clj`) recognizes
`(comp #{<lits>} <classifier-sym>)`, qualifies the classifier symbol
against `ns-sym`, resolves it in `accessor-summaries`, and returns
`{:path ... :values [...]}` when the summary is a
`:unary-map-classifier`, else `nil`.

Test count: 530 → 537 (seven new: 6 in predicate-descriptor-test, 1 in
types-test). Full suite: 537 tests, 2457 assertions, 0 failures, 0
errors. clj-kondo: 0 errors, 0 warnings. Phase 1 deftest still GREEN.

## Phase 5C

Descriptor consultation lands in
`src/skeptic/analysis/annotate/match.clj`. Two new private helpers:
`descriptor-applies?` (path equality between descriptor and query) and
`pred-matches-lit?` (when a descriptor with matching path is present,
membership-test against `:values` and bypass the runtime predicate;
otherwise fall back to `path-predicate-matches-lit?`). Both
`narrow-conditional-by-discriminator` and `narrow-conditional-default`
destructure each branch as `[pred branch-type descriptor]` and dispatch
through `pred-matches-lit?`.

Integration fixture appended to `test/skeptic/test_examples/contracts.clj`:
`NestedConditionalInTopPred` uses `(comp #{:b} nested-conditional-classify)`
as the recognized predicate shape; `NestedConditionalXTop` and
`nested-conditional-repro-top` mirror the Phase 1 repro but route through
the descriptor path.

New deftest `nested-conditional-classifier-descriptor-narrowing` in
`test/skeptic/checking/pipeline/contracts_test.clj` runs the fixture
against `ps/check-fixture` — empty result list = no inconsistencies.

Descriptor-bypass unit test added in `match_test.clj`
(`descriptor-path-bypasses-runtime-probe`): pred is `(constantly false)`,
descriptors are populated with matching values; the helper still selects
the correct branch type, proving the descriptor short-circuit fires
before the runtime probe.

Test count: 537 → 539 (one match-test, one pipeline-test). Full suite:
539 tests, 2459 assertions, 0 failures, 0 errors. clj-kondo: 0 errors,
0 warnings. Phase 1 deftest still GREEN (its preds are `(is? :b)`
lambdas — descriptor absent, runtime probe still satisfies it).

## Phase 6

End-to-end verification sweep. No code edits.

### Verification commands and outcomes

```
lein test :only skeptic.checking.pipeline.contracts-test/nested-conditional-destructured-discriminator-narrowing
  → 1 test, 1 assertion, 0 failures
lein test :only skeptic.checking.pipeline.contracts-test/nested-conditional-classifier-descriptor-narrowing
  → 1 test, 1 assertion, 0 failures
lein test (focused) origin / annotate.match / map-ops / predicate-descriptor / types / accessor-summary / pipeline.contracts
  → 72 tests, 302 assertions, 0 failures
lein test (whole suite)
  → 539 tests, 2459 assertions, 0 failures, 0 errors
clj-kondo --lint .
  → linting took 2033ms, errors: 0, warnings: 0
lein with-profile +skeptic-plugin skeptic
  → No inconsistencies found
```

The plan included a pre-emptive `mkdir target/trampolines` + TMPDIR
override copied from the input doc. Both were dropped: writing into
Leiningen's managed `target/` is wrong, and the plugin gate runs cleanly
with no temp-dir override at all.

### Fixtures and deftests added across phases

Fixtures (in `test/skeptic/test_examples/contracts.clj`):
- `nested-conditional-repro` (Phase 1) — destructured-discriminator narrowing via `(is? :b)` lambda preds (runtime-probe path).
- `nested-conditional-repro-top` (Phase 5C) — same shape with `(comp #{:b} nested-conditional-classify)` preds (descriptor path).

Pipeline deftests (in `test/skeptic/checking/pipeline/contracts_test.clj`):
- `nested-conditional-destructured-discriminator-narrowing` (Phase 1)
- `nested-conditional-classifier-descriptor-narrowing` (Phase 5C)

Unit-test files added or substantially extended:
- `test/skeptic/analysis/origin_test.clj` (Phases 2 / 2X / 4)
- `test/skeptic/analysis/annotate/match_test.clj` (Phases 3 / 4 / 5C)
- `test/skeptic/analysis/map_ops_test.clj` (Phase 4)
- `test/skeptic/analysis/predicate_descriptor_test.clj` (Phase 5B, new file)
- `test/skeptic/analysis/types_test.clj` (Phase 5B)
- `test/skeptic/checking/pipeline/accessor_summary_test.clj` (Phase 5A, new file)

### Design summary

The end-state composition is four landed mechanisms working in series:

1. **Path-shaped projection origins** (Phase 2 + 2X). Every `(:k v)`,
   `(get v :k)`, and destructured projection-binding accumulates a
   `:map-key-lookup` origin carrying the original `:root` and a vector
   `:path` of key-queries from root toward leaf. Destructure-emitted
   seq-coercion `:if` shims are recognized so per-level gensym rebinds
   preserve the chain.
2. **Path-equality assumptions** (Phase 4). When the equality target of
   `(= local lit)` carries a `:map-key-lookup` origin,
   `equality-value-assumption` produces a `:path-value-equality`
   assumption rooted at that projection's root, with the projection's
   path. `apply-assumption-to-root-type` calls
   `refine-map-path-by-values`, which reduces over the path through
   map / union / maybe / conditional shapes and refines the appropriate
   slot.
3. **Preserving conditional-branch selection** (Phase 3 + 4 generalization).
   `narrow-conditional-by-discriminator` and `narrow-conditional-default`
   accept a key-query path (not just a top-level kw) and an explicit
   `{:drop-discriminator? bool}` option. Direct-`case` payload narrowing
   continues to drop the discriminator via thin wrappers; nested-path
   refinement keeps it.
4. **Classifier descriptors** (Phase 5A + 5B + 5C). `accessor-summary-from-body`
   recognizes `(s/defn classify [m] (case ... ))` shaped functions and
   produces a `:unary-map-classifier` summary with `:path` and `:cases`.
   The bridge captures the literal pred form per ConditionalT branch;
   a post-pass enriches each branch's slot 3 with a static descriptor
   `{:path ... :values ...}` resolved against the accessor summaries
   when the pred is `(comp #{lits} classifier-sym)`. At narrowing time,
   `pred-matches-lit?` consults the descriptor first and falls back to
   the runtime predicate only when the descriptor is absent or its path
   does not apply.

The repro succeeds because (1) gives the destructured `k` an
`:map-key-lookup` origin with path `[:x :k]` rooted at `x`; (2) turns
`(= k "b")` into a `:path-value-equality` assumption that refines `x`
at slot `[:x :k]`; (3) and (4) make sure the conditional inside `:x` of
the input schema is narrowed to the `:b`-branch type without losing the
`:k` key, producing a refined `x` compatible with the `nested-conditional-f`
parameter type.
