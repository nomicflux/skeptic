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

## Phase 4

## Phase 5

## Phase 6
