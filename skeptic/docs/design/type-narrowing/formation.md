# Type Narrowing — Formation

**Status:** Approved
**Date:** 2026-04-06
**Previous stage:** [creation.md](creation.md)

---

## Summary

The architecture extends Skeptic’s existing single-pass annotation pipeline: new
logic lives in `skeptic.analysis.narrowing` (type filters and partitioning behind
a stable API), `skeptic.analysis.map-ops.algebra` (type-level map operations),
and targeted edits to `annotate.clj`, `origin.clj`, and `calls.clj`. The
`:case` op gets a dedicated `annotate-case` handler (multi-branch, not shared
with `annotate-if`). No new multimethods; dispatch stays on `case` and plain
functions. Second pass is not required unless implementation discovers a concrete
need.

## Tech Stack

- **Language:** Clojure 1.11.1 (unchanged)
- **AST:** `clojure.tools.analyzer.jvm` 1.2.3 (unchanged)
- **Type domain:** Existing `skeptic.analysis.types` / `type-ops` (unchanged
  representation)

## Architecture Overview

Analysis remains: `analyze-form` → `annotate-ast` → `annotate-node` recursive
walk with a context map `{:locals … :assumptions … :dict … :ns …}`.

**Narrowing** is implemented by:

1. **Recognizing** test patterns and producing **assumptions** (`origin.clj`).
2. **Splitting** branch environments (`branch-local-envs`, existing).
3. **Applying** assumptions when resolving locals (`effective-entry` →
   `apply-assumption-to-root-type`, extended).
4. **Refining** map result types for specific invokes (`annotate-invoke` +
   `map-ops.algebra`).

**Value discrimination** for `case*` uses the JVM analyzer’s `:case` node:
`:test`, paired `:tests` / `:thens` (`:case-test` / `:case-then` children), and
`:default`.

## Component Inventory

| Component | Location | Responsibility |
|-----------|----------|----------------|
| Annotator | `skeptic.analysis.annotate` | Dispatch `:if`, `:let`, `:invoke`, **`:case`** |
| Origin / assumptions | `skeptic.analysis.origin` | `test->assumption`, new kinds, `apply-assumption-to-root-type` |
| Call detection | `skeptic.analysis.calls` | Predicate / keyword-invoke / assoc / dissoc / update / merge (mirror `get-call?` style) |
| Narrowing core | `skeptic.analysis.narrowing` | Encapsulated predicate → classification; partition type for then/else |
| Map algebra | `skeptic.analysis.map-ops.algebra` | `assoc-type`, `dissoc-type`, `update-type`; `merge` composes existing `merge-map-types` where applicable |
| Value check | `skeptic.analysis.value-check` | Keep `refine-type-by-contains-key`; extend if keyword-invoke needs shared helpers |
| Type ops | `skeptic.analysis.type-ops` | Reuse `union-type`, `de-maybe-type`, `normalize-type` |

**Not new top-level namespaces:** Avoid `skeptic.analysis.narrowing.*` unless
file size forces a split later.

## Data Flow (Textual)

**`:if`:** Annotate test → `test->assumption` → `branch-local-envs` → annotate
then/else with refined `locals` / `assumptions` → `type-join*`.

**`:case`:** Annotate `:test` → for each `(case-test, case-then)` pair, build
`:value-equality` assumption for matched const(s), annotate `:then` under that
assumption → annotate `:default` with assumptions excluding all matched values →
`type-join*` over all branches.

**`:let`:** Fold bindings; when binding init is a bare `:local` with
`:root` origin, give the new symbol the **same** `root-origin` as that local
(alias). Do not persist extra alias metadata beyond the binding scope.

**`:invoke`:** After existing `get` / `merge` / `contains?` branches, if
`assoc`/`dissoc`/`update`/`merge` detected and arguments satisfy const-key
constraints, compute output type via `map-ops.algebra` (or `merge-map-types`
for `merge` when all args are `MapT`).

## Integration Boundaries

- **Schema / bridge:** No change to primary cast pipeline contract; narrowing
  only affects inferred types on the AST.
- **Cast kernel:** Unchanged; consumers see more precise `:type` on nodes.
- **`typed-decls` / `s/defn`:** Ensure defs for `s/defn` expose `FunT` so
  `fn?` narrowing can match (implementation detail in Action phase).

## `:case` AST (tools.analyzer.jvm)

Reference: [tools.analyzer.jvm quickref](https://clojure.github.io/tools.analyzer.jvm/spec/quickref.html).

- `:op` `:case`
- `:test` — expression under test
- `:tests` — vector of `:case-test` (each has `:test` → typically `:const`)
- `:thens` — vector of `:case-then` (each has `:then` — branch body)
- `:default` — default branch
- `:children` — `[:test :tests :thens :default]`

`annotate-case` is **independent** of `annotate-if` (different shape); both use
assumptions and `branch-local-envs` / `effective-entry` where applicable.

## Chosen Direction (Limits)

- **Single new analysis namespace:** `skeptic.analysis.narrowing` (one file
  unless it grows unwieldy).
- **Map algebra subnamespace:** `skeptic.analysis.map-ops.algebra` (user
  requirement: not stuffing `map_ops.clj`).
- **No multimethods** for assumption application; extend `case` in
  `apply-assumption-to-root-type`.
- **Predicate registry:** Data-oriented, **encapsulated** behind a small public
  API (e.g. `classify-leaf-for-pred?`, `partition-type-for-predicate`) so
  internals can become general functions later without changing callers.
- **Second pass:** Only if a concrete soundness or ordering bug requires it;
  default is single pass.

## Mapping from Creation Concepts to Components

| Creation concept | Formation home |
|------------------|----------------|
| Assumption kinds | `origin.clj` (+ narrowing helpers) |
| Type filter / partitioner | `narrowing.clj` |
| Test patterns | `test->assumption` in `origin.clj` + `calls.clj` detectors |
| Alias (shared root) | `annotate-let` in `annotate.clj` |
| Map type algebra | `map-ops/algebra.clj` |
| Case branch analysis | `annotate-case` in `annotate.clj` |

## Architectural Trade-offs

- **Centralize narrowing in `narrowing.clj`** vs. bloating `origin.clj` — chosen:
  keep `origin` as orchestration; heavy type logic in `narrowing.clj`.
- **`annotate-case` complexity** — multi-branch vs. binary `if`; chosen:
  explicit loop over test/then pairs with assumption per branch.
- **`update` typing** — requires resolving the update function’s return type
  from its `FunT` when available; if `Dyn`, result for that key is `Dyn` or
  conservative join per Action spec.

## Resilience / Error Handling

- Unrecognized test shapes → no assumption (current behavior).
- Non-`MapT` first argument to `assoc`/`dissoc`/`update`/`merge` → `Dyn` result
  (align with intention).
- `merge` with any `Dyn` map argument → `Dyn` (intention).

## Deployment / Build

- No new dependencies.
- `lein test` remains the verification gate.

## Open Questions for Stage 4 (Action)

- Exact signatures and ordering of new `narrowing` functions.
- Whether `static-call` paths need parallel branches for `assoc`/`dissoc` on
  `RT` (mirror `static-get-call?`).
- Where `s/defn` gets `FunT` in the dict (collect vs. bridge).
- Test file layout: extend `origin_test.clj`, `annotate_test.clj`, new
  `narrowing_test.clj`, `map_ops/algebra_test.clj`.

---

**Review:** Approve this formation document before Stage 4 (Action).
