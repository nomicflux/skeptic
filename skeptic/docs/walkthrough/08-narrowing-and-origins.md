# Narrowing And Origins

> *Snapshot of state as of 2026-05-06.*

Narrowing is the reason `double-or-zero` passes. The declared input allows nil,
but the multiplication runs only after `(some? n)` has refined the branch-local
Type of `n`.

## Start With The Local

Admission gives the parameter `n` this Type:

```text
n : Maybe[Int]
```

At the top of the function body, that Type is correct. The caller may pass nil
or an Int. Multiplication cannot safely use the maybe Type directly because nil
is still possible.

The body then tests:

```clojure
(some? n)
```

This test is the point where narrowing begins.

## Test To Assumption

Skeptic reads the test and produces an assumption. For `(some? n)`, the
assumption says that the root local `n` satisfies the `some?` predicate on the
positive branch.

That assumption is not itself a new Type. It is a fact waiting to be applied to
the Type of the value it mentions.

On the negative branch, the assumption is inverted. The same test that proves
"n is present" in the then branch proves "n is not present" in the else branch.

## Origins Connect Facts To Values

The assumption has to find the value it refines. That is the job of origins. A
parameter has a root origin. A map lookup can have a path origin. A branch result
can remember the test that produced it.

For `double-or-zero`, the origin is simple:

```text
root origin: n
current Type: Maybe[Int]
branch fact: some?(n)
```

Because the origin points to `n`, Skeptic can refine the local environment for
the then branch.

## Applying The Assumption

The predicate partition for `some?` knows how to split a maybe Type:

```text
Maybe[Int] under positive some? -> Int
Maybe[Int] under negative some? -> nil
```

The then branch receives the first result. The local environment inside the then
branch has:

```text
n : Int
```

That is the Type used when annotation reaches:

```clojure
(* 2 n)
```

The multiplication sees an Int literal and an Int local, not a maybe local.

## The Else Branch

The else branch returns:

```clojure
0
```

The negative branch fact can refine `n` toward nil, but the branch does not use
`n` in the return expression. The branch result is the literal `0`, which has an
Int-shaped Type.

The full function body therefore has an Int result from the then branch and an
Int result from the else branch. The output check against declared Int succeeds.

## Why This Does Not Save `classify`

`classify` has tests too:

```clojure
(zero? n)
(even? n)
```

Those tests can describe branches, but they do not remove the fallback branch.
An odd non-zero Int still reaches `"odd"`. Since `"odd"` remains reachable,
annotation keeps the string alternative in the body result.

The difference between the examples is precise:

```text
double-or-zero:
  test proves the value used in multiplication is non-nil
  output remains Int

classify:
  tests do not cover all Int inputs
  fallback string remains reachable
```

## Map Paths And Branch Origins

The same assumption machinery also works beyond simple locals. If a test proves
something about a value pulled from a map key, the origin records that path. If a
branch result depends on a test, the origin can keep the branch relationship
available for later refinement.

That is why assumptions, origins, and Types are separate. A fact such as
"present" is reusable, but the way it changes a Type depends on the value route
it applies to.

## Simplifying Branch Facts

Branch expressions can macroexpand into nested lets and ifs. Skeptic simplifies
the resulting assumptions so the useful branch fact reaches the local
environment. For example, a boolean expression can produce a disjunction whose
other parts are refuted by the current branch. Simplification carries the
surviving fact forward.

This is still local branch reasoning. It prepares facts for Type refinement; it
does not replace the cast engine.

## Source Pointers

- `skeptic/analysis/origin.clj:test->assumption` - converts tests to assumptions.
- `skeptic/analysis/origin.clj:apply-assumption-to-root-type` - applies an assumption to a Type.
- `skeptic/analysis/narrowing.clj:partition-type-for-predicate` - partitions a Type by predicate.
- `skeptic/analysis/origin.clj:simplify-assumptions` - simplifies branch facts.
- `skeptic/analysis/origin.clj:region-conjuncts` - derives facts for branch regions.
- `skeptic/analysis/origin.clj:branch-local-envs` - builds branch-local environments.
