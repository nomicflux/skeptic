# Handoff: Principled Narrowing for `s/conditional` Schemas in `case` Branches

## Status
Previous agent failed to produce a correct plan. Read this fully before proposing anything.

## What was added (already in the codebase)

`test/skeptic/test_examples.clj` — at the bottom, after `abcde-maps-bad`:
```clojure
(s/defschema ConditionalAorB
  (s/conditional
    #(= :a (:route %)) HasA
    #(= :b (:route %)) HasB))

(s/defschema WithRouting
  {:route (s/enum :a :ab)
   :val   ConditionalAorB})

(s/defn handles-a :- HasA [x :- HasA] x)
(s/defn handles-b :- HasB [x :- HasB] x)

(s/defn handles-ab :- ConditionalAorB
  [x :- HasAOrB]
  (case (:route x)
    :a (handles-a x)
    :b (handles-b x)))
```

`test/skeptic/checking/pipeline_test.clj` — after `nested-conditional-contract-cond-thread`:
```clojure
(deftest handles-ab-case-routing
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/handles-a
     'skeptic.test-examples/handles-b
     'skeptic.test-examples/handles-ab)))
```

`handles-a` and `handles-b` already pass. Only `handles-ab` fails.

## The failing test

```
lein test :only skeptic.checking.pipeline-test/handles-ab-case-routing
```

Error: in `(case (:route x) :a (handles-a x) :b (handles-b x))`, the checker reports
that `x` (typed `union(HasA, HasB)`) is incompatible with `handles-a` (expects `HasA`)
and `handles-b` (expects `HasB`).

## Root cause

**`annotate-case`** (`src/skeptic/analysis/annotate.clj` lines 572–617):

```clojure
root (ao/local-root-origin test-node)
```

`test-node` is the annotated discriminant `(:route x)`. `local-root-origin` returns non-nil
only when the node IS a direct local variable. `(:route x)` is a keyword invocation, so
`root = nil`. No assumption is created. `x` stays typed as `union(HasA, HasB)` in all branches.

But even fixing that has a deeper blocker:

**`s/conditional` predicates are discarded at import time.**

`src/skeptic/analysis/bridge.clj` line 101:
```clojure
(sb/conditional-schema? schema)
(ato/union-type (map (comp import-schema-type second) (:preds-and-schemas schema)))
```

`(:preds-and-schemas schema)` contains `[[pred-fn SchemaA] [pred-fn SchemaB] ...]`.
Only the schemas survive. The predicate functions (`#(= :a (:route %))`, `#(contains? % :a)`,
etc.) are thrown away. The internal type for both `HasAOrB` and `ConditionalAorB` becomes
`union(HasA-type, HasB-type)` — identical and predicate-blind.

## The class of problem

Any `s/conditional` schema used in a `case` or `if` branch should be narrowable using its
predicates. The test represents this class; fixing ONLY this test is wrong.

## What was tried and why it failed

When discriminant is `(kw local)` and case value is `val` (a keyword), create a
`:contains-key val` assumption on `local`. Concretely: `(:route x) = :a` → assume
`(contains? x :a)`. This worked for the test only because `HasAOrB`'s branches happen to
use keys `:a` and `:b` — the same strings as the case values. It is unsound in general:
`(:route x) = :a` has no logical connection to `(contains? x :a)`.

## Key files to read before designing

| File | What to look at |
|------|----------------|
| `src/skeptic/analysis/bridge.clj` line 101 | Where predicates are discarded |
| `src/skeptic/analysis/annotate.clj` lines 572–617 | `annotate-case` |
| `src/skeptic/analysis/origin.clj` lines 98–116, 224–312 | Assumption system: `apply-assumption-to-root-type`, `test->assumption`, `local-root-origin` |
| `src/skeptic/analysis/narrowing.clj` | `partition-type-for-predicate`, `partition-type-for-values` |
| `src/skeptic/analysis/value_check.clj` lines 36–91 | `refine-type-by-contains-key` |
| `src/skeptic/analysis/types.clj` | All type record definitions |
| `src/skeptic/analysis/type-ops.clj` | `union-type`, `normalize-type` |
| `docs/blame-for-all.md` | Theoretical grounding for Skeptic's type system |

## What the new agent must do

1. Read all key files above before proposing anything.
2. Design a principled approach to preserve and use `s/conditional` predicates for narrowing.
3. The fix must handle the general class, not just this specific example.
4. Do NOT patch `annotate-case` in isolation — the predicate information must come from somewhere principled.
5. Verify with `lein test :only skeptic.checking.pipeline-test/handles-ab-case-routing`
   and full `lein test :only skeptic.checking.pipeline-test` (no regressions).
