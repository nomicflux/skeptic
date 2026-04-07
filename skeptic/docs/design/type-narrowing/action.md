# Type Narrowing — Implementation Plan

**Status:** Approved  
**Stage 4 (Action):** This file is the Step 10 pass-off artifact. The sections
**Stage 4 — Step 1** through **Step 10** record the workflow that produced the
plan below. (Steps 1–2 and 4–6 were synthesized first; **Step 3** and **Step 7**
were confirmed in chat on 2026-04-06.)

**Sources:** [intention.md](intention.md), [creation.md](creation.md),
[formation.md](formation.md).

---

## Stage 4 — Step 1: Seed

Re-read approved `formation.md` (and `creation.md` / `intention.md` for
constraints). Per step, identify what must be specified at implementation
granularity: namespaces, public functions, assumption data shapes, edits to
`annotate-node` dispatch, test file layout, and order of work so earlier phases
unblock later ones.

**Outcome:** Six implementation phases (narrowing core → origin → calls/tests →
annotate let/case → map algebra → fn? verification), with acceptance gates.

---

## Stage 4 — Step 2: Exploration

Surveyed existing code paths:

- `skeptic.analysis.annotate` — `annotate-node` `case`, `annotate-if`, `annotate-let`,
  `annotate-invoke`, `annotate-static-call`; pattern for special calls in
  `calls.clj`.
- `skeptic.analysis.origin` — `test->assumption`, `branch-local-envs`,
  `apply-assumption-to-root-type`, `same-assumption?`, `assumption-truth`,
  `effective-entry`.
- `skeptic.analysis.map-ops` — `merge-map-types`, `map-get-type`, descriptors.
- `skeptic.analysis.value-check` — `refine-type-by-contains-key`,
  `leaf-overlap?`.
- `skeptic.typed-decls` / `skeptic.schema.collect` — callable entries and `FunT`.

**Outcome:** New code fits existing style (plain `case`, no multimethods); map
algebra is a **subnamespace** `skeptic.analysis.map-ops.algebra`, not more logic
crammed into `map_ops.clj`.

---

## Stage 4 — Step 3: Understanding

*User answers (2026-04-06); Step 4+ must conform.*

| Question | Resolution |
|----------|-------------|
| Coding conventions / style? | **Existing project style, including global rules** (e.g. workspace `AGENTS.md`, Cursor/user rules): type-domain-first, no re-exports, match `annotate` / `origin` / `calls` patterns. |
| Testing strategy? | **Same as established Skeptic practice:** every narrowing scenario worth shipping must get **new coverage in `test/skeptic/test_examples.clj`** (or the project’s canonical examples surface used for integration), and the suite must **`lein test` green including `test/skeptic/checking/pipeline_test.clj`** at minimum for the change. Unit tests under `test/skeptic/analysis/` remain appropriate for pure narrowing helpers. |
| Build / deploy pipeline? | **Confirmed:** Leiningen only; no new dependencies; `clj-kondo --lint` on touched trees before merge. |
| Performance requirements? | **No special rules.** Correctness first; profiling later if needed. |
| Error handling / type-checking? | **No exceptions as a type-checking strategy.** Do not throw to “handle” user code during narrowing or cast checking; follow **`docs/blame-for-all.md`** — only flag what is provably inconsistent; otherwise remain conservative (`Dyn`, no assumption). (Distinguish: internal `IllegalArgumentException` for true programmer errors in Skeptic itself may still exist where the codebase already uses them; do not add new throws for ordinary untyped or ambiguous user forms.) |

---

## Stage 4 — Step 4: Expansion

Implementation sketch for early review (maps to phases under Step 6):

**`narrowing.clj`**

- Internal: `pred-info` map `{:pred keyword :class Class?}`; registry from `pred` →
  `(fn [type] → :matches|:does-not-match|:unknown)` on **normalized leaf** types.
- `classify-leaf-for-predicate?` — dispatch on `(:pred pred-info)` only; for
  `:instance?` use `(:class pred-info)`.
- `partition-type-for-predicate` — structural walk: `Dyn`/`PlaceholderT` →
  preserve; `UnionT` → partition members, re-`union-type` non-empty sides;
  `MaybeT` → split nil vs inner per `nil?`/`some?`/other preds per creation.md;
  leaf → single branch or `BottomT` when empty.
- `apply-truthy-local` — `de-maybe-type` then filter `(ValueT … false)` from unions.
- `partition-type-for-values` — positive: intersect with `ValueT` / union of
  value literals; negative: drop matching members; `Dyn` unchanged.

**`origin.clj`**

- `apply-assumption-to-root-type`: dispatch `:type-predicate`, `:value-equality`;
  `:truthy-local` → `apply-truthy-local`.
- `same-assumption?`: branch on `:kind` to compare `:key`, `:pred`/`:class`,
  `:values` (vector equality or sorted set).
- `assumption-truth`: for new kinds, use base type + classifier to return
  `:true`/`:false` when provable, else `:unknown` (for `:branch` origins).

**`calls.clj`**

- Sets of resolved symbols for each core pred; `type-predicate-info` from
  `fn-node`; `assoc-call?` etc. mirroring `merge-call?`.

**`annotate.clj`**

- `annotate-let`: if `init` is `:local`, `env-entry` `:origin` =
  `(root-origin (:form init) normalized-type)` so `:sym` is the aliased name.
- `annotate-case`: annotate test once; loop `tests`/`thens` with
  `refine-locals-for-assumption` + extra assumption vec; default branch with
  negative value assumptions; `type-join*`; add `:case` to `annotate-node`.

**`map-ops/algebra.clj` + `annotate-invoke`**

- Pure functions on `MapT` entries; callers pass literal key from AST; `update`
  uses `FunT` first method arity-1 output or `Dyn`.

**Examples + pipeline**

- Each feature slice: add defs/forms in `test/skeptic/test_examples.clj` (or the
  file the project uses for checked examples) and assert via
  `checking/analyze-source-exprs` or existing helpers; **pipeline tests must stay
  green** (`test/skeptic/checking/pipeline_test.clj`).

*(Full phase list and acceptance text: **Step 6** and **Compiled specification**
below.)*

---

## Stage 4 — Step 5: Limits

- **Module layout:** One `narrowing.clj`; split only if file size hurts review.
- **No multimethods** for assumptions (formation).
- **Map algebra:** literals only for keys at refine sites; non-`MapT` / `Dyn` args →
  `Dyn` result where formation requires.
- **Testing gate (user):** No phase “done” without **new `test_examples` coverage**
  for the behavior and **`pipeline_test` passing** (full `lein test` required for
  merge).
- **Deferred:** `condp =` only if macro expansion is not already covered; `RT`
  static paths for assoc/dissoc only after empirical AST check.

---

## Stage 4 — Step 6: Harmonizing

Coherent phased plan (dependency order):

1. **`skeptic.analysis.narrowing`** — registry behind small public API; tests.
2. **`origin.clj`** — wire assumptions + `assumption-truth` + `same-assumption?`.
3. **`calls.clj` + `test->assumption`** — detectors and new test shapes.
4. **`annotate.clj`** — alias `root-origin` (original `:sym`), `annotate-case`.
5. **`map-ops/algebra.clj` + invoke/static-call`** — assoc/dissoc/update/merge typing.
6. **`fn?` / `s/defn`** — verify `FunT` in dict; classifier matches `FunT`.

---

## Stage 4 — Step 7: Confirmation

Checklist for implementer / reviewer before coding:

- [x] Assumption shapes and `same-assumption?` rules agree for all four kinds.
- [x] Alias origin uses the **original** symbol’s `:sym` in `{:kind :root …}`,
  not the gensym (see Phase 4 detail below).
- [x] `:case` handler matches JVM analyzer `:case` / `:case-test` / `:case-then`
  shape (formation + quickref).
- [x] `merge` and non-`MapT` arguments fall back to `Dyn` per intention.
- [x] No `Dyn` narrowing; union / `MaybeT` / value refinement only where proven.
- [x] **Step 3 testing:** Each shipped scenario has **`test_examples`** coverage and
  **`pipeline_test` passes** (full `lein test`).
- [x] **Step 3 blame:** Narrowing/checking does **not** use exceptions to drive
  type conclusions; conservative fallbacks only per `blame-for-all.md`.

**User sign-off:** Confirmed in chat (2026-04-06). **Document status** set to
Approved with Step 10.

---

## Stage 4 — Step 8: Trade-offs

| Trade-off | Decision |
|-----------|----------|
| Encapsulated predicate table vs. scattered `case` | Table behind `narrowing` public API (creation); callers stay stable. |
| `annotate-case` vs. reusing `annotate-if` | Independent handler (formation); shared assumption machinery only. |
| `update` key type when fn is `Dyn` | New value type `Dyn` for that key (sound). |
| Duplicating small class checks vs. requiring `value-check` from `narrowing` | Prefer no new cycles; duplicate minimal numeric/class checks if needed. |
| **Integration test cost** (`test_examples` + `pipeline_test` every feature) vs. faster iteration with unit-only | **Accepted:** user requires examples + pipeline at minimum; accept slower per-feature merge cost for confidence. |

---

## Stage 4 — Step 9: Touch-ups

- Plan is self-contained: prior stage links at top; glossary and assumption table
  included.
- Open items listed at end for runtime discovery.
- **Correction:** Stage 4 was initially delivered without walking Steps 1–10 in
  separate chat turns; this revision embeds the ten-step record so the artifact
  matches the concept-to-implementation skill.
- **Step 7 confirmed** in chat; Step 8 row added for testing trade-off; document
  approved for implementation handoff.

---

## Stage 4 — Step 10: Pass-off

This entire file is the **final Action deliverable**. Implement in phase order;
each phase’s acceptance criteria gates the next.

**Approved for implementation:** 2026-04-06 (after Step 7 confirmation). An
implementing agent should need no context beyond this file plus linked
`intention.md` / `creation.md` / `formation.md` and `docs/blame-for-all.md`.

---

# Compiled specification (reference)

The following sections are the detailed plan (Steps 4–6 expanded).

## Context

Skeptic must narrow types in conditional branches so cast checking follows the
same soundness standard as `blame-for-all.md`: only report inconsistencies that
can be proved. False positives today come from ignoring facts proved by tests
(nil, predicates, key presence, `case` discriminants, map updates).

## Architecture Overview

- **Recognition:** `skeptic.analysis.origin` / `test->assumption` (and helpers in
  `skeptic.analysis.calls`).
- **Application:** `apply-assumption-to-root-type` + new logic in
  `skeptic.analysis.narrowing` (partitioning, truthy refinement including `false`
  value removal).
- **Propagation:** `branch-local-envs`, `effective-entry`, `annotate-let` alias
  sharing of `:root` origins.
- **Multi-branch:** `annotate-case` in `skeptic.analysis.annotate` for `:case`.
- **Map results:** `skeptic.analysis.map-ops.algebra` + branches in
  `annotate-invoke` / `annotate-static-call` mirroring existing `get`/`merge`.

## Glossary

- **Assumption** — Map with `:kind`, `:root` (`{:kind :root :sym … :type …}`),
  `:polarity` (boolean: then vs else), and kind-specific keys.
- **Leaf classification** — One of `:matches`, `:does-not-match`, `:unknown` for
  a predicate vs a non-union, non-maybe leaf type.
- **Alias** — `let` binding whose init is a bare `:local`; entry uses the same
  logical root as the referenced symbol (see Phase 4).

## Assumption Shapes (contracts)

Extend `same-assumption?` / `opposite-assumption?` / `assumption-base-type` /
`assumption-truth` for new kinds. Use explicit discriminator fields so two
assumptions on the same symbol are comparable.

| Kind | Extra keys | `same-assumption?` compares |
|------|------------|-----------------------------|
| `:truthy-local` | (none beyond root/polarity) | kind, root sym, polarity |
| `:contains-key` | `:key` (keyword) | kind, root sym, `:key`, polarity |
| `:type-predicate` | `:pred` (keyword), optional `:class` for `:instance?` | kind, root sym, `:pred`, `:class`, polarity |
| `:value-equality` | `:values` (sorted vec of compile-time values for the branch) | kind, root sym, `:values`, polarity |

**Note:** Extend `same-assumption?` / `opposite-assumption?` to compare kind-specific
fields (`:key`, `:pred` + `:class`, `:values`) so new assumptions do not collide.
For `:truthy-local`, both sides lack `:key`; `(= nil nil)` is fine today.

## Phased Implementation

### Phase 1: `skeptic.analysis.narrowing`

**New file:** `src/skeptic/analysis/narrowing.clj`

**Public API (stable for callers):**

- `(classify-leaf-for-predicate? pred-info type)` → `:matches` | `:does-not-match` | `:unknown`  
  `pred-info` is `{:pred keyword, :class? Class}` (omit `:class` except for `:instance?`).

- `(partition-type-for-predicate type pred-info polarity)` → type  
  Returns the type for the **current** branch: if `polarity` is true (then), keep
  matching members; if false (else), keep non-matching members. Handles `MaybeT`,
  `UnionT`, and leaves. **`Dyn` and unknown leaves:** both branches stay `Dyn`
  where classification is `:unknown` for the whole type (per intention).

- `(apply-truthy-local type polarity)` → type  
  If `polarity` true: `de-maybe-type` then remove union members that are
  `(ValueT … false)` (boolean false only). If false: return `type` unchanged.

**Private:** predicate registry as data (maps `pred` keyword → leaf classifier
implementation). Classifiers use only `skeptic.analysis.types` predicates and
`type-ops/normalize-type`. For `number?`, include `:int` ground and class-backed
grounds assignable from `Number` / `java.lang.Number` / `schema` numeric classes
as used in `value.clj` / `class->type`.

**Dependencies:** `types`, `type-ops`, `value-check` only if sharing overlap logic
with existing `leaf-overlap?` (prefer avoiding a cycle: duplicate minimal checks
or pass in a predicate).

**Acceptance:** Unit tests in `test/skeptic/analysis/narrowing_test.clj` for
representative unions, `MaybeT`, `Dyn`, `nil?` / `some?` / `string?` / `fn?` /
`instance?`.

---

### Phase 2: `origin.clj` — apply new assumptions

**File:** `src/skeptic/analysis/origin.clj`

1. Require `skeptic.analysis.narrowing` as `an` (or `nar`).

2. Extend `apply-assumption-to-root-type`:
   - `:truthy-local` — call `an/apply-truthy-local` instead of raw
     `de-maybe-type`.
   - `:type-predicate` — call `an/partition-type-for-predicate` with
     `{:pred (:pred assumption) :class (:class assumption)}` and `(:polarity assumption)`.
   - `:value-equality` — new helper in `narrowing.clj`, e.g.
     `partition-type-for-values type values polarity`, mirroring partition rules
     (union members, `ValueT` intersection, `Dyn` preserved).

3. Extend `assumption-truth` for `:type-predicate` and `:value-equality` using
   `assumption-base-type` + classifications analogous to `:contains-key` (when the
   base type proves the test always true/false, return `:true`/`:false`;
   otherwise `:unknown`). Needed for `:branch` origins in `origin-type`.

4. Align `same-assumption?` with the assumption shape table (include `:pred`,
   `:class`, `:values` where relevant).

**Acceptance:** Extended `origin_test.clj` or `narrowing_test.clj` covering
branch-origin resolution if tests exist for opaque/branch joins.

---

### Phase 3: `calls.clj` + `test->assumption`

**File:** `src/skeptic/analysis/calls.clj`

Add detectors (same style as `contains-call?` / `get-call?`):

- `type-predicate-call?` — resolved var or symbol in a fixed set
  `#{clojure.core/string? string? …}` for each supported pred.
- `type-predicate-info` — from `fn-node`, return `{:pred :string?}` etc.
- `keyword-invoke-on-local?` — `:invoke` with `:fn` a `:const`/`:quote` keyword and
  first arg `:local` with map root.
- `assoc-call?`, `dissoc-call?`, `update-call?` (and `merge-call?` already
  exists).

**File:** `src/skeptic/analysis/origin.clj` — extend `test->assumption`:

- Single-arg `(pred local)` for each supported `type-predicate-call?` →
  `:type-predicate` with root from `local-root-origin`.
- `instance?` — handle JVM analyzer’s `:instance?` op if present, else
  `clojure.core/instance?` invoke with class + local.
- Keyword invoke `(:k m)` → `:contains-key` (reuse `contains-key-test-assumption`).

**Acceptance:** Tests in `origin_test.clj` or small analyze-form fixtures
asserting inferred types in then/else for `(if (string? x) …)` and keyword
invoke.

---

### Phase 4: `annotate.clj` — aliases and `:case`

**File:** `src/skeptic/analysis/annotate.clj`

**`annotate-let`:** After `annotate-binding`, when building `env-entry`:

- If annotated `init` has `:op :local` and the referenced local has a root origin,
  set the new binding’s `:origin` so **`assumption-root?` matches the original
  symbol**: use `(ao/root-origin <original-sym> <type>)` where `<original-sym>` is
  `(:form init)` (the aliased local’s name), not `(:form binding)`. That way
  assumptions built from tests on the alias still target the same `:sym` as
  assumptions on the original.

**`annotate-case`:**

1. Annotate `:test` with current ctx.
2. Collect matched values from each `:case-test` (unwrap `:const` / literal).
3. For each index `i`, build `:value-equality` assumption on
   `local-root-origin` of test if test is `:local`, else skip narrowing for that
   branch (conservative).
4. For branch `i`, conj assumption with `:values` for that branch’s test group;
   annotate `(:then (nth thens i))` with extended assumptions (helper:
   `with-extra-assumptions` / `refine-locals-for-assumption` only — no fake
   `else` node).
5. Default branch: assumptions excluding all matched values (coordinate with
   `partition-type-for-values`).

6. Join branch types with `av/type-join*`.

7. Attach `:origin` consistent with `annotate-if` where useful.

**Dispatch:** Add `:case` to `annotate-node` `case`.

**Acceptance:** `annotate_test.clj` — `(case x :a 1 :b 2)` with typed `x` union;
result type is join of Int, not `Dyn`.

---

### Phase 5: `map-ops.algebra` + invoke typing

**New file:** `src/skeptic/analysis/map_ops/algebra.clj` (namespace
`skeptic.analysis.map-ops.algebra`)

**Functions:**

- `(assoc-type map-type key-literal value-type)` → type  
- `(dissoc-type map-type key-literal)` → type  
- `(update-type map-type key-literal update-fn-type)` → type  
  Resolve arity-1 method output from `update-fn-type` (`FunT`); if unknown, use
  `Dyn` for that key’s new value type.

**Rules:** If `map-type` is not `MapT`, return `Dyn`. Keys must be literal
keywords (reuse `calls/literal-map-key?` + `literal-node-value` at call site).

**Merge:** Reuse `skeptic.analysis.map-ops/merge-map-types` for `(merge m1 m2 …)`
when every arg is `MapT`; if any arg is not `MapT`, return `Dyn` (intention).

**Files:**

- `annotate-invoke` — after `get`/`merge`/`contains?`, add branches for
  `assoc`/`dissoc`/`update`/`merge` using algebra + existing `merge-map-types`.
- `annotate-static-call` — if compiler emits `RT` calls for assoc/dissoc (verify
  with sample AST), add parallel detection like `static-get-call?`.

**Acceptance:** `test/skeptic/analysis/map_ops/algebra_test.clj` + pipeline or
annotate tests for `(let [m (assoc m :k 1)] (:k m))`.

---

### Phase 6: `fn?` and `s/defn`

**Investigation:** `skeptic.schema.collect` + `skeptic.typed-decls` — confirm
`s/defn` vars already get `FunT` via `callable-desc->typed-entry`. If any path
emits a non-`FunT` for callable schema, fix that path.

**Narrowing:** Ensure `classify-leaf-for-predicate?` for `:fn?` returns `:matches`
for `FunT`.

**Acceptance:** One test with `s/defn` in dict and `(if (fn? f) …)` narrowing.

---

## Testing Strategy

- **Examples (required):** For each narrowing behavior shipped, add or extend
  **`test/skeptic/test_examples.clj`** (or the project’s standard examples module)
  so the checking pipeline exercises real forms.
- **Pipeline (required):** **`test/skeptic/checking/pipeline_test.clj` must pass**
  as part of merge criteria (user: “at the very least”).
- **Unit:** `narrowing_test`, `map_ops/algebra_test`, focused `origin_test` /
  `annotate_test` additions where logic is pure and examples would be heavy.
- **Integration:** Full **`lein test`** must stay green before merge.

## Build

- No new dependencies.
- `lein test` is the gate.
- Run `clj-kondo --lint` on touched dirs before merge.

## Open Items

- Exact `annotate-case` helper for “assumptions only” (extract from
  `branch-local-envs` or new `with-extra-assumptions`).
- Whether `RT` static paths exist for `assoc`/`dissoc` in analyzed bytecode —
  verify empirically in Phase 5.
- `condp =` — if macro-expands to shapes already covered by `test->assumption`,
  no extra work; otherwise defer or add pattern in Phase 3.
- Transitive alias chains — confirm single-hop `root-origin` on alias is enough
  for nested `let` (creation says shared root propagates).

---

**Status:** Approved (2026-04-06).
