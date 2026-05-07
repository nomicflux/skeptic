# Closed-Sum Exhaustiveness

> *Snapshot of state as of 2026-05-06.*

Closed-sum reasoning is how Skeptic decides that a branch covers every possible
case of a finite Type. It only applies when the Type itself provides a complete
set of alternatives.

## The Problem It Solves

Branch annotation needs to know which result expressions are reachable. Suppose
a value can only be true or false:

```clojure
(case b
  true  :yes
  false :no)
```

If `b` is boolean-shaped, the two arms cover the whole space. A default result,
if present only as a fallback artifact, would not contribute a new reachable
body Type.

Now compare `classify`:

```clojure
(cond
  (zero? n) :zero
  (even? n) :even
  :else     "odd")
```

The input `n` is Int. Integers are not a finite list of values. The explicit
tests do not cover every integer. The fallback branch is reachable, so `"odd"`
must remain in the body result.

## Alternatives

Skeptic asks a Type for alternatives. A closed Type returns a list. An open Type
returns no list for exhaustiveness purposes.

```text
exact value :a       -> [:a]
boolean             -> [true false]
maybe exact value   -> [value nil]
union of closed     -> all member alternatives
integer             -> open
dynamic             -> open
placeholder         -> open
```

This is why maybe does not automatically mean exhaustive in every useful sense.
`Maybe[Int]` contains nil and every integer. The nil side is finite, but the Int
side is open.

## Coverage

Coverage asks whether the covered alternatives account for the whole closed
sum. For a boolean, covering true and false is enough. For a union of exact
keywords, covering every keyword member is enough. For Int, no finite list of
ordinary branch values covers the type.

The answer is intentionally conservative. If the Type cannot be enumerated,
Skeptic keeps the fallback reachable.

## How This Affects `classify`

`classify` needs the fallback branch to remain visible:

```text
input Type:  Int
tests:       zero?, even?
fallback:    "odd"
```

Neither `zero?` nor `even?` partitions Int into a fully enumerated set of return
values. The fallback is not dead. Annotation therefore includes the string branch
in the body Type. The output check later has to compare that body Type with the
declared Keyword output.

If closed-sum reasoning incorrectly removed the fallback, the output finding
would disappear. Annotation would hand output checking a body Type made only of
keyword values, and the string branch would no longer reach the cast that is
supposed to reject it.

The mistake this prevents is subtle. Seeing two numeric predicates beside an
`:else` branch can make the source look partitioned, but the Type of `n` has not
become a closed set. There are infinitely many odd nonzero integers, and they
all still flow to `"odd"`.

## A Step-By-Step Contrast

For the boolean `case`, the walk is finite:

```text
start Type:     Bool
available arms: true, false
covered values: true, false
remaining:      none
fallback:       unreachable for Type computation
```

For `classify`, the walk is open:

```text
start Type:     Int
recognized arms: zero?, even?
covered values:  not an enumerable closed set
remaining:       still Int-shaped values
fallback:        reachable and contributes String
```

That second walk is why the output problem survives. The cast step later sees a
source Type that still includes the fallback result. The keyword branches do
their job; the string branch is the member that makes the declared Keyword
boundary fail.

## How This Differs From Narrowing

Closed-sum exhaustiveness and narrowing are related, but they answer different
questions. Exhaustiveness asks whether all alternatives have been covered.
Narrowing asks what Type remains on a particular branch after a test.

`double-or-zero` uses narrowing. `(some? n)` refines `n` inside one branch. It
does not require proving that every possible Int has been enumerated. It only
needs the branch fact that this value is not nil.

## Boolean Formula Coverage

Some branch facts combine through boolean forms. Skeptic has a bounded formula
coverage helper for recognized propositions. It can decide coverage over a small
finite set of boolean atoms.

This still depends on a finite proposition space. It is not a way to enumerate
all integers or all strings.

## Where Exhaustiveness Hands Off

Closed-sum reasoning only decides branch reachability. Once that decision has
been made, the next phases carry the result:

```text
closed-sum reasoning: fallback remains reachable
annotation result:    body includes "odd"
cast dispatch:        source union must fit Keyword
projection:           output report carries failed source-union evidence
```

The handoff matters because no later phase redoes exhaustiveness from the raw
source. If the branch result entering checking is precise, the cast can explain
the mismatch. If the branch result entering checking has already lost the
fallback, the output report has nothing specific to reject.

## Source Pointers

- `skeptic/analysis/sum_types.clj:sum-alternatives` - computes finite alternatives.
- `skeptic/analysis/sum_types.clj:exhausted-by-types?` - checks Type coverage.
- `skeptic/analysis/sum_types.clj:exhausted-by-values?` - checks value coverage.
- `skeptic/analysis/sum_types.clj:sum-type?` - recognizes closed sums.
- `skeptic/analysis/sum_types.clj:formulas-cover?` - checks bounded boolean coverage.
