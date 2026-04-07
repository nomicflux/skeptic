# Type Narrowing — Creation

**Status:** Approved
**Date:** 2026-04-06
**Previous stage:** [intention.md](intention.md)

---

## Summary

The type narrowing system is built from three conceptual layers: Recognition
(detecting narrowing-relevant AST patterns), Propagation (threading narrowing
facts through the type environment via assumptions and aliases), and Application
(refining a type given accumulated assumptions). The core entities are Assumptions
(four kinds), Type Filters (a data-driven predicate registry behind an
encapsulated API), a Type Partitioner (splitting types into positive/negative
halves), Alias origin sharing (for macro gensym chains), Map Type Algebra
(type-level assoc/dissoc/update/merge), and Case Branch Analysis (value
discrimination with type joining).

## Conceptual Model

### Three Layers

**Layer A: Recognition.** The analysis walks the AST. At each `:if` node,
`test->assumption` examines the test expression and produces an Assumption (or nil
if the test is unrecognized). At each `:case` node, branch values produce
value-equality assumptions. At each `:invoke` of `assoc`/`dissoc`/`update`/`merge`,
the call produces a refined map type.

**Layer B: Propagation.** Assumptions are added to the context's `:assumptions`
vector when entering a branch. Alias origin sharing ensures that narrowing a gensym
narrows the original variable too. `annotate-let` detects aliases (bare `:local`
init) and gives the binding the same root origin. Assumptions accumulate through
nested conditionals — the else branch of one `:if` carries into the test of the
next, giving `cond`-style progressive narrowing for free.

**Layer C: Application.** When a local is referenced (`annotate-local`),
`effective-entry` computes its type by applying all active assumptions to its root
origin. Each assumption kind has an application function. For `:type-predicate`,
the application delegates to a Type Filter and a Type Partitioner.

## Entities

### Assumption

A fact proven by a conditional test, used to refine types in branch environments.

**Properties:** kind, root (the local it applies to), polarity (true = positive /
then-branch, false = negative / else-branch), plus kind-specific data.

**Four kinds:**

#### `:truthy-local`

- **Trigger:** Bare local in `:if` test position.
- **Positive effect:** Strip `MaybeT` (remove nil). Remove `(ValueT _ false)` from
  unions (remove false). Leave `(GroundT :bool)` unchanged (can't express "bool
  except false" without negative types).
- **Negative effect:** Type unchanged (value is nil or false).
- **Distinguishing case:** `(if x ...)` on
  `(UnionT #{(MaybeT Int) (ValueT Bool false)})` — positive excludes both nil and
  the false value.

#### `:type-predicate`

- **Trigger:** Core type predicate call on a local (see Test Patterns below).
- **Additional data:** Predicate identifier (e.g., `:string?`, `:nil?`, `:some?`).
  For `:instance?`, also carries the class.
- **Positive effect:** Keep type members matching the predicate; remove
  non-matching members.
- **Negative effect:** Remove type members matching the predicate; keep
  non-matching members.
- **Distinguishing case:** `(if (string? x) ...)` on `(UnionT #{GroundT:str
  GroundT:int})` — positive keeps `GroundT:str`, negative keeps `GroundT:int`.

#### `:value-equality`

- **Trigger:** `case` branch matching exact values.
- **Additional data:** The exact value(s) matched.
- **Positive effect:** Narrow to `ValueT` for the matched value(s). If multiple
  values in a test group, narrow to union of `ValueT`s.
- **Negative effect:** Remove matched value(s) from unions.
- **Distinguishing case:** `(case x :a ...)` — positive: `x` is
  `(ValueT :keyword :a)`.

#### `:contains-key`

- **Trigger:** `contains?` call or keyword-as-function invoke on a local map.
- **Additional data:** The keyword key.
- **Positive effect:** Confirm key presence — promote optional key to required in
  map type. Filter union members to those that can contain the key.
- **Negative effect:** Confirm key absence — filter union members to those that
  might not contain the key.
- **Already exists** in the codebase; extended to recognize keyword-invoke pattern.

### Type Filter

A classification function that determines whether a type matches a predicate.
Backed by a data-driven lookup table, encapsulated behind an API so the
implementation can change to general functions later.

**Input:** A predicate identifier and a leaf type (not union, not maybe).

**Output:** Three-valued classification:
- `:matches` — the type definitely satisfies the predicate
- `:does-not-match` — the type definitely does not satisfy the predicate
- `:unknown` — can't determine (e.g., `Dyn`, `PlaceholderT`)

**Predicate registry:**

| Predicate | Matches |
|-----------|---------|
| `:nil?` | The nil part of `MaybeT` |
| `:some?` | Non-nil values (inverse of `:nil?`) |
| `:string?` | `GroundT` with ground `:str` |
| `:keyword?` | `GroundT` with ground `:keyword` |
| `:integer?` | `GroundT` with ground `:int` |
| `:number?` | `GroundT` with ground `:int` or numeric class grounds (Double, Float, Long, etc.) |
| `:boolean?` | `GroundT` with ground `:bool` |
| `:symbol?` | `GroundT` with ground `:symbol` |
| `:map?` | `MapT` |
| `:vector?` | `VectorT` |
| `:set?` | `SetT` |
| `:seq?` | `SeqT` |
| `:fn?` | `FunT` (including `s/defn`-defined vars) |
| `:instance?` | `GroundT` with matching class |

### Type Partitioner

Combines a Type Filter with a type to produce positive and negative types for
branch environments.

**Input:** A type and a type filter (predicate identifier).

**Output:** `{:positive type, :negative type}`.

**Rules by type shape:**

- `Dyn` → `{:positive Dyn, :negative Dyn}` (don't narrow Dyn)
- `BottomT` → `{:positive BottomT, :negative BottomT}`
- `MaybeT inner` → Nil goes to the side where it belongs per the predicate (e.g.,
  nil matches `nil?`, doesn't match `string?`). Inner is partitioned
  independently. Results are re-wrapped in `MaybeT` on the side that gets nil.
- `UnionT members` → Partition members by filter. `:matches` members go to
  positive. `:does-not-match` members go to negative. `:unknown` members go to
  both.
- Leaf types (`GroundT`, `MapT`, `FunT`, etc.) → Filter directly. `:matches` →
  positive only, negative is `BottomT`. `:does-not-match` → negative only,
  positive is `BottomT`. `:unknown` → both.

### Alias (realized as shared root origin)

Not a separate entity — realized by giving a `let` binding the same `:root` origin
as the variable it was directly bound to.

**Detection:** In `annotate-let`, when a binding's annotated init node has
`:op :local` and has a root origin, the binding is an alias.

**Lifecycle:** Created when the binding is processed. Lives for the scope of the
`let` binding. Does not outlive the alias's scope.

**Mechanism:** Since alias and original share the same root, any assumption
targeting that root narrows both. Bidirectional: narrowing the alias (e.g., it
appears in an `if` test) creates an assumption on the shared root, which also
narrows the original. Transitive aliases (`let [a x, b a]`) work because `b`
shares origin with `a` which shares origin with `x` — all point to the same root.

### Test Pattern

A recognized AST pattern in an `:if` test position that produces an assumption.

| Pattern | AST shape | Assumption produced |
|---------|-----------|---------------------|
| Bare local | `:op :local` | `:truthy-local` |
| `(nil? x)` | `:invoke`, fn resolves to `clojure.core/nil?`, arg is `:local` | `:type-predicate {:pred :nil?}` |
| `(some? x)` | `:invoke`, fn resolves to `clojure.core/some?`, arg is `:local` | `:type-predicate {:pred :some?}` |
| `(string? x)` | `:invoke`, fn resolves to `clojure.core/string?`, arg is `:local` | `:type-predicate {:pred :string?}` |
| (other core predicates) | Same invoke pattern | `:type-predicate` with corresponding pred key |
| `(instance? Cls x)` | `:instance?` op or `:invoke` | `:type-predicate {:pred :instance?, :class Cls}` |
| `(contains? m :k)` | `:invoke` / `:static-call` with literal keyword | `:contains-key` (existing) |
| `(:k m)` | `:invoke` where fn is keyword const, arg is `:local` | `:contains-key` on the map local |

### Map Type Algebra

Type-level operations on `MapT` entries. Lives in a new subnamespace.

| Operation | Input | Output |
|-----------|-------|--------|
| `assoc-type` | `MapT`, key, value type | New `MapT` with entry added/overridden as required |
| `dissoc-type` | `MapT`, key | New `MapT` with entry removed |
| `update-type` | `MapT`, key, transform return type | New `MapT` with value type changed |
| `merge-types` | `MapT...` | Left-to-right overlay of entries |

**Edge cases:**
- `assoc` on optional key → promotes to required key
- `dissoc` removes entry whether optional or required
- Input is not `MapT` (e.g., `Dyn`) → result is `Dyn`
- Input is `MaybeT MapT` → result is `MaybeT` of the operation on inner
- `merge` with `Dyn` argument → result is `Dyn`
- All operations require literal/const key arguments to refine

### Case Branch Analysis

Conceptual model for analyzing `:case` AST ops.

**`:case` AST structure:** `:test` (expression), `:tests` (groups of `:const`
values), `:thens` (branch bodies), `:default` (default body).

**Behavior:**
1. Annotate `:test` to get its type.
2. For each test group: create `:value-equality` assumption narrowing the test
   variable to the matched values. Annotate the branch body with that assumption.
3. For the default branch: create negative `:value-equality` assumptions excluding
   all matched values.
4. Join all branch types (including default) for the result type.

## Capability Inventory

| Capability | What it does |
|------------|-------------|
| Recognize test patterns | Produce assumptions from `:if` test expressions |
| Propagate assumptions through branches | Split environments for then/else |
| Propagate narrowing through aliases | Share root origin in `let` bindings |
| Apply assumptions to types | Compute effective type of a local |
| Partition types by predicate | Split type into matching/non-matching halves |
| Handle `:case` ops | Branch typing with value discrimination |
| Refine map types through mutation | Produce refined `MapT` from assoc/dissoc/update/merge |

## Process: How Narrowing Flows

1. `annotate-node` encounters `:if`.
2. Test expression is annotated.
3. `test->assumption` recognizes the pattern → produces an Assumption.
4. `branch-local-envs` splits the environment: positive assumption for then,
   negative for else.
5. Each branch's locals are refined by `effective-entry`, which applies all
   active assumptions to each local's root origin.
6. For `:type-predicate` assumptions, application calls the Type Filter to
   classify, then the Type Partitioner to split.
7. Branch bodies are annotated with their refined environments.
8. Branch result types are joined.

For aliases: step 5 applies assumptions to the shared root, so both the alias
and the original see the same narrowing.

For `case`: steps 2–8 are analogous but with `:value-equality` assumptions per
branch and a default branch with negative assumptions.

For map mutation: `annotate-invoke` detects `assoc`/`dissoc`/`update`/`merge`,
invokes Map Type Algebra, and the refined `MapT` enters the environment when
bound in a `let`.

## Domain Glossary

- **Assumption** — A fact proven by a conditional test, with kind, root, and
  polarity.
- **Type Filter** — A classification function from (predicate, type) → matches /
  does-not-match / unknown.
- **Type Partitioner** — Splits a type into positive and negative halves given a
  type filter.
- **Alias** — A `let` binding whose init is a bare local reference. Shares root
  origin with the original.
- **Root Origin** — The original source of a local's type, tracked through
  branches and aliases.
- **Map Type Algebra** — Type-level simulation of map key operations (assoc,
  dissoc, update, merge).
- **Three-valued classification** — `:matches`, `:does-not-match`, `:unknown`.
  Used by type filters. Unknown values are preserved in both partitions.

## Dependencies Between Concepts

```
Test Pattern Recognition
    └──→ Assumption (produces)
             ├──→ Type Filter (`:type-predicate` kind uses)
             │        └──→ Type Partitioner (fed by)
             │                 └──→ narrowed types in branch environment
             └──→ apply-assumption-to-root-type
                  (`:truthy-local`, `:contains-key`, `:value-equality`)

Alias Origin Sharing
    └──→ annotate-let (detects aliases)
             └──→ shared root origin (enables assumption propagation)

Case Branch Analysis
    └──→ value-equality Assumptions (produces per branch)
             └──→ Type Partitioner (applied)

Map Type Algebra
    └──→ annotate-invoke (produces refined MapT)
             └──→ let binding propagation (refined type enters environment)
```

## Identified Gaps and Unknowns

- `annotate-case` structure depends on the exact `:case` AST shape from
  tools.analyzer.jvm — must verify in Stage 3.
- `number?` type filter must match numeric class grounds beyond `:int` — exact
  set of classes to be determined in Stage 3.
- `update-type` needs the return type of the transform function applied to the
  old value type — mechanism depends on how `annotate-invoke` resolves fn types.
- Entities are provisional — may consolidate or split as later stages require.

## Accepted Trade-offs

1. **Truthiness removes `(ValueT _ false)` from unions** but cannot narrow bare
   `(GroundT :bool)` (no negative types).
2. **Type filter registry is data-driven** but encapsulated behind an API for
   future generalization.
3. **`case` dispatch on assumption kind.** No multimethods. Four kinds in a `case`
   is manageable.
4. **No `Dyn` narrowing.** `Dyn` means "no type information" — nothing to narrow.
5. **No negative types.** In else branches, we remove matching members from unions.
   Non-union types stay unchanged in else branches (except MaybeT stripping).

## Open Questions for Stage 3

- Where does each concept live in the namespace structure?
- What is the exact `:case` AST shape from tools.analyzer.jvm?
- How does `annotate-invoke` currently resolve fn return types for `update-type`?
- What is the full set of numeric ground classes for `number?`?
