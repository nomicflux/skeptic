# Cast Dispatch

> *Snapshot of state as of 2026-05-06.*

Checking asks a directional question: can an actual Type be used where an
expected Type is required? Cast dispatch chooses the rule that answers that
question for the current source and target pair.

## The Two Casts In The Worked Example

`classify` reaches an output cast:

```text
actual:   body result containing :zero, :even, "odd"
expected: declared Keyword output
```

`double-or-zero` reaches call and output checks after narrowing:

```text
actual argument to *: n as Int
expected argument:    numeric input from native declaration

actual output:        Int
expected output:      declared Int
```

The first definition fails because one body alternative cannot satisfy Keyword.
The second definition passes because the branch-local Type of `n` has already
lost the nil case before multiplication.

## Cast Result Shape

A cast returns a result map with enough information for later reporting:

```text
ok?
rule
blame side and polarity
source Type
target Type
children
reason on failure
path segments when structural children add them
```

The boolean answer matters, but it is not the whole result. The rule and
children explain how the answer was reached. The source and target Types are
what output can later render.

## The Ordered Rule Choice

Dispatch is ordered from special cases to structural cases to leaf
compatibility:

```text
bottom source
exact Type match
quantified boundary
abstract variable or sealed dynamic
target dynamic
union and intersection
conditional branches
placeholder or recursive reference
maybe
wrappers
function
map
vector, seq, set
leaf
```

Earlier cases protect invariants that would be lost if a later structural rule
opened the Type too soon. Quantified boundaries and sealed dynamic values are
handled before broad dynamic acceptance. Unions are handled before leaf checks
so their members can be considered one by one.

## Source Union In `classify`

The `classify` body result is the source side, and it has multiple possible
members. A source union must satisfy the target for every member. The output cast
therefore becomes:

```text
member :zero  against Keyword -> success
member :even  against Keyword -> success
member "odd"  against Keyword -> failure
```

The parent source-union result fails because at least one source member fails.
The successful members remain meaningful: they show that the problem is not the
whole conditional. The failing member shows why the declared output is not
satisfied.

## Target Union Contrast

A target union works the other way. If the expected Type is "Int or String,"
the source only needs to fit one branch. A source Int can satisfy that target by
choosing the Int branch.

That contrast is why the cast rule must know which side is the union. Source
union means all possible actual values must be acceptable. Target union means
the expected boundary accepts any one of several shapes.

## Function Casts And Polarity

Function casts recurse into methods. Outputs are checked in the same direction
as the original function boundary. Inputs flip polarity because callers provide
arguments.

That flip is the basis for caller-side argument findings. A string flowing into
an Int parameter is not the same boundary as a string flowing out of
`classify`. The Type mismatch can look similar, but the cast is under a
different structural side.

## Paths From Structural Children

Structural rules can attach path segments to child results. A function input can
carry an argument index. A map check can carry a field-oriented path. A vector
check can carry an index.

For the current `classify` output report, the production path is output report
kind plus source-union diagnostic evidence. It is not a rendered function-range
path. A different failure inside a nested map output could carry a visible field
path because the failed diagnostic would include structural path segments.

## Leaf Compatibility

The leaf rule is the final compatibility check. In the failing `classify`
member, the actual Type is String-shaped and the expected Type is Keyword-shaped.
There is no structural child to inspect. The leaf fails.

The parent source-union result then carries that failure upward. The report
receives the failed cast evidence produced by dispatch, including the actual and
expected Types needed for rendering.

## Source Pointers

- `skeptic/analysis/cast.clj:check-cast` - normalizes and runs the cast.
- `skeptic/analysis/cast.clj:dispatch-cast` - chooses the cast rule.
- `skeptic/analysis/cast/support.clj:cast-ok` - builds successful results.
- `skeptic/analysis/cast/support.clj:cast-fail` - builds failed results.
- `skeptic/analysis/cast/support.clj:aggregate-children` - combines structural children.
- `skeptic/analysis/bridge/render.clj:polarity->side` - maps polarity to blame side.
