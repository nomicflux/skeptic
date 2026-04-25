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

## Phase 3

## Phase 4

## Phase 5

## Phase 6
