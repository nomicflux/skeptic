# Type Narrowing — Intention

**Status:** Approved
**Date:** 2026-04-06
**Previous stage:** N/A

---

## Summary

Skeptic must narrow types through conditional branches so that provably safe code
is not falsely blamed. When a conditional test proves a type fact — nil absence,
ground type membership, exact value, key presence — the analysis must reflect that
fact in the branch's type environment. Failing to narrow is incorrect: it leads to
blaming code that the blame calculus proves is sound.

## Problem Statement

Skeptic produces false positive cast warnings because it does not narrow types
through conditional branches. When code checks for nil, tests a type predicate,
matches on exact values, or modifies map keys, Skeptic ignores the information
those checks provide. Idiomatic Clojure patterns — `when`, `some->`, `cond`,
`case`, `and`/`or`, `assoc`/`dissoc`/`merge` — generate spurious warnings that
erode trust in the tool.

The governing principle is from `blame-for-all.md`: Skeptic only flags what it can
*prove* is inconsistent, and must not blame code that is provably well-typed.
Narrowing makes types more precise in branches where the precision is proven by the
conditional test. This is not a heuristic — it is required for correctness.

## Goals (ordered by priority)

### 1. Nil narrowing across all conditional forms

When a variable is tested for truthiness or nil, strip `MaybeT` in the truthy
branch and preserve it in the falsy branch. This must work through macro-expanded
gensym chains.

**Surface forms:** `when`, `if`, `when-some`, `some->`, `some->>`, `if-let`,
`when-let`, `and`, `or`, `if (nil? x)`, `if (some? x)`.

**AST patterns:** All expand to combinations of `:if`, `:let`, and `:invoke` ops.
Narrowing must propagate through `let` binding aliases so that gensyms introduced
by macro expansion correctly narrow the original variable.

### 2. Type predicate narrowing for union discrimination

When a core type predicate is used as a conditional test on a union-typed variable,
narrow the union to matching members in the truthy branch and remove them in the
falsy branch.

**Predicates:** `string?`, `keyword?`, `integer?`, `number?`, `boolean?`,
`symbol?`, `map?`, `vector?`, `set?`, `seq?`, `fn?`, `nil?`, `some?`, `instance?`.

**Accumulation:** Narrowing accumulates through nested conditionals. In
`(cond (string? x) ... (keyword? x) ... :else ...)`, the `:else` branch knows `x`
is neither string nor keyword.

### 3. `case` and `condp =` value discrimination

Implement proper handling for the `:case` AST op so branch types are joined (not
`Dyn`). In each branch, narrow the test variable to `ValueT` when the original type
is a union. Support `condp =` similarly.

### 4. Map key presence narrowing (extend existing)

Extend the existing `contains?` narrowing. Also recognize keyword-as-function
invocations (`(:k m)`) as evidence of key presence.

### 5. Map mutation type refinement

`assoc`, `dissoc`, `update`, and `merge` produce refined map types that reflect the
key change. When bound in `let`, the refined type propagates to subsequent code.

- `assoc` — adds/overrides a key with the type of the value argument
- `dissoc` — removes a key
- `update` — changes a key's value type to the return type of the function applied
- `merge` — overlays entries from right-hand `MapT` arguments; only refines when
  the argument has known `MapT` type; `Dyn` arguments produce `Dyn` result

### 6. `s/defn` satisfies `fn?`

Schema-defined functions (`s/defn`) should be typed as `FunT`, and the `fn?`
predicate should recognize `FunT` as a match during type predicate narrowing.

## Non-goals

- **Interprocedural narrowing.** Narrowing is local to a single function body. It
  does not propagate across function call boundaries.
- **Narrowing `Dyn`.** `Dyn` means "no type information available." Type predicates
  do not narrow `Dyn`. Union types are the representation for "one of several known
  possibilities" and those get narrowed.
- **User-defined type guard predicates.** Only the fixed set of core predicates
  listed above. Custom predicates that act as type guards are out of scope.
- **Nested container narrowing.** `(if (string? (:name x)) ...)` does not narrow
  the type of the `:name` value inside `x`'s map type. Only direct local variables
  are narrowed.
- **`core.match` pattern matching.** Different AST shape, separate project.
- **Negative types.** We do not invent "not-string" types. In the else branch of a
  predicate test, we remove matching members from a union. If the variable was not a
  union, the else branch type is unchanged (unless it was `MaybeT`, in which case
  nil is removed by a `some?`/truthiness test).

## Success Criteria

1. The analysis produces provably correct narrowed types in each branch of every
   listed conditional form, and only flags casts that are provably inconsistent.
   This is the `blame-for-all.md` standard.
2. Code using `when`, `when-some`, `some->`, `some->>`, `if-let`, `when-let`,
   `and`, `or`, `cond`, `condp`, `case` with nil checks produces no false positive
   nil-related cast warnings.
3. Code using type predicates on union-typed values produces narrowed types in each
   branch — no false positive "incompatible cast" when the branch guarantees the
   type.
4. `(case x :a (f x) :b (g x))` infers a proper joined type from all branches,
   not `Dyn`.
5. `(let [m (assoc m :k 1)] (:k m))` knows `:k` is present and `Int`.
6. `(let [m (merge m {:k 1})] (:k m))` knows `:k` is present and `Int`.
7. All existing tests continue to pass.
8. New tests cover each narrowing form and each assumption kind.

## Scope Boundaries

- All work operates in the **type domain**, not the schema domain (per AGENTS.md).
- Work at the **AST `:op` level** after macro expansion by tools.analyzer.jvm.
- **Extends** the existing assumption/origin machinery; does not replace it.
- **Backward compatible** with current analysis behavior.
- **Single pass preferred**; second pass only if mechanically required for
  correctness.

## Key Assumptions

- The tools.analyzer.jvm AST shapes for these macro expansions are stable across
  Clojure 1.11.x.
- `MaybeT` is the canonical representation of "might be nil." Stripping it in a
  truthy branch is sound.
- Union member removal is sound when a type predicate definitively excludes a
  member.
- `Dyn` remains opaque — it is not treated as "all possible types."

## Accepted Trade-offs

1. **Soundness is the governing principle, not a trade-off.** Per
   `blame-for-all.md`: only flag what can be proven inconsistent. Narrowing is
   required for correctness — it reflects information the conditional test actually
   proves.
2. **Alias tracking must be correct.** Whatever mechanism is needed to propagate
   narrowing through macro-expanded gensym chains must be built. Correctness over
   implementation simplicity.
3. **All listed forms must be analyzed correctly.** This is the specification, not a
   trade-off between breadth and depth.
4. **`merge` only refines with known `MapT` arguments.** When the right-hand
   argument is `Dyn`, the result is `Dyn`. Conservative and correct.
5. **`Dyn` remains opaque.** Type predicates do not narrow `Dyn`. `Dyn` means "no
   type information," not "could be anything."

## Glossary

- **`MaybeT`** — Type domain representation of "value or nil." `(MaybeT inner)`
  means the value is either `nil` or satisfies `inner`.
- **`UnionT`** — Type domain representation of "one of several known types."
  `(UnionT #{A B C})` means the value is one of A, B, or C.
- **`ValueT`** — Type domain representation of an exact value.
  `(ValueT :keyword :a)` means the value is exactly `:a`.
- **`MapT`** — Type domain representation of a map with known key-value entries.
- **`FunT`** — Type domain representation of a function with known method
  signatures.
- **`GroundT`** — Type domain representation of a base type (`:int`, `:str`,
  `:keyword`, `:symbol`, `:bool`, or a class).
- **`Dyn`** — Type domain representation of "no type information available."
- **`BottomT`** — Type domain representation of an uninhabited type (unreachable
  code).
- **Narrowing** — Making a type more precise in a branch where a conditional test
  has proven additional type information.
- **Assumption** — A fact proven by a conditional test, used to refine types in
  branch environments. Existing kinds: `:truthy-local`, `:contains-key`.
- **Alias** — A `let` binding whose init is a bare local reference to another
  variable. Used to track macro gensym chains.
- **Blame** — Per `blame-for-all.md`, the mechanism that identifies which side of a
  type boundary is responsible for a type inconsistency.
