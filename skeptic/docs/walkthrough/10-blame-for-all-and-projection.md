# Blame For All And Projection

> *Snapshot of state as of 2026-05-06.*

This section follows the point where Skeptic has already admitted the declared
schemas, annotated the function bodies, and started comparing computed Types
against declared Types. The question now is not just whether two Types fit. The
question is how a failed comparison becomes a finding that tells a programmer
which boundary was broken and where to look first.

The worked example has one failing definition and one passing definition:

```clojure
(s/defn classify :- s/Keyword
  [n :- s/Int]
  (cond
    (zero? n) :zero
    (even? n) :even
    :else     "odd"))

(s/defn double-or-zero :- s/Int
  [n :- (s/maybe s/Int)]
  (if (some? n)
    (* 2 n)
    0))
```

`classify` is the reporting example. Its declaration promises callers a
Keyword. Its body can return two keyword values and one string value. Skeptic
has to preserve that shape long enough to say more than "Str does not fit
Keyword." It has to say that the broken promise is the output of `classify`.

`double-or-zero` is the contrast. Its argument may be nil, but the branch test
separates the non-nil case before multiplication. The then branch returns an
Int-shaped result, the else branch returns `0`, and the declared output is Int.
There is no failed boundary to project into a finding.

## The Output Boundary

For a declared function, Skeptic checks the body against the declaration after
annotation has finished. The declaration has already been admitted into the Type
domain. For `classify`, the admitted output Type is the Keyword Type produced
from `:- s/Keyword`.

The body Type comes from annotation. The `cond` body has several possible return
values, so the relevant body Type is a union-like result: one alternative for
`:zero`, one for `:even`, and one for `"odd"`. The first two alternatives fit
Keyword because exact keyword values inhabit Keyword. The string alternative
does not.

That is why this is an output problem instead of a generic mismatch. The value
shape that fails is not being passed into `classify`; it is one possible value
computed by `classify` and allowed to leave through the function's declared
return boundary.

Skeptic's production path reflects that distinction. The output-checking step
selects the declared method by arity, takes that method's declared output as the
expected Type, takes the annotated method body's output as the actual Type, and
runs an output cast report. The report is classified as output because this path
is checking a definition's result against its own declaration.

## The Cast Evidence

The cast still matters. The output-report path does not merely notice that the
function has a return annotation. It asks whether the actual body Type can be
used where the declared output Type is promised.

For `classify`, the source side of the cast is the body result. The target side
is the declared Keyword result. Because the source is a union-like Type, cast
checking compares each source alternative with the same target:

```text
:zero  -> Keyword   passes
:even  -> Keyword   passes
"odd"  -> Keyword   fails
```

The successful alternatives explain why the whole body is not simply wrong. They
also explain why they are not the reported problem. A report that led the reader
to `:zero` or `:even` would point at correct branches. The useful evidence is
that the set of possible outputs contains at least one member that cannot cross
the declared output boundary.

Current reporting keeps that source-union failure as the diagnostic evidence for
the output report. The failed branch is the evidence; the output report is the
boundary. Together they say that the declared return Type does not accept every
possible result of the body.

## From Evidence To Finding

The output cast returns two pieces the report needs to keep distinct. The root
summary describes the boundary check as a whole: actual body result on one side,
declared Keyword result on the other. The diagnostics describe why that boundary
check failed.

For `classify`, the diagnostic evidence is the source-union result. That matters
because the body did not compute one fixed value. It computed a set of possible
results, and the declared output Type must accept all of them. When one member
of the source union fails, the report must still talk about the declared output
boundary, not only about an isolated string literal.

The output-report wrapper then adds the fact that this comparison came from a
definition result. It stores the report as an output report, keeps the method
body as the expression under inspection, and carries the cast summary and
diagnostics forward. When the output summary is built, it uses the declared
return Type from the root summary as the expectation and the failed diagnostic
as the reason that expectation was not met.

That is the concrete movement from algorithm to finding:

```text
declared output Type
  compared with annotated body Type
  producing source-union failure evidence
  wrapped as an output report for the method body
  summarized against the declared return Type
```

## Output Reports And Visible Paths

Skeptic can render visible structural paths such as map fields, vector indexes,
function arguments, and function ranges. Those paths matter when the failed
diagnostic carries the corresponding path segments.

For `classify`, output-ness comes from the check that produced the report. The
comparison is the definition's annotated result against its admitted declared
result, and the resulting map is stored as `:report-kind :output`. The diagnostic
evidence is the failed source-union comparison described above.

Visible paths are added when the failed diagnostic has structural path segments.
A map-field failure can render a field path. A vector failure can render an
index. A function-input failure can render an argument position. In the
`classify` case, the output boundary and the failed source-union evidence are
the facts that carry the report.

## Why Argument Failures Point Elsewhere

The same actual and expected Type shapes would mean something different at a
call site. If a caller supplied `"odd"` to a parameter declared as Int, the
broken boundary would be the function input. The report should lead the reader
to the supplied argument or the value flowing into that call, not to the callee's
return body.

Skeptic handles that through a separate input-report path. For a call, the
checker matches expected argument Types against actual argument Types. Each
failed argument comparison becomes an input error group. The final input report
keeps the call expression as the reported expression and the supplied argument
as the focus.

That is why blame is more than a nicer error label. It preserves which side of
the program boundary produced the bad flow:

| Boundary being checked | Actual value comes from | Report should lead to |
|---|---|---|
| `classify` output | the function body | the definition's return behavior |
| a call argument | the caller's expression | the supplied argument or its source |

The Type mismatch can look similar in both cases. The responsible boundary is
different.

## Why `double-or-zero` Has Nothing To Project

`double-or-zero` starts from a maybe-typed argument, so it would be easy to
expect a nullable problem. The branch test is what prevents one. In the then
branch, `(some? n)` lets annotation treat `n` as non-nil before `(* 2 n)` is
checked. In the else branch, the literal `0` already satisfies the declared
return Type.

The important consequence is that the output comparison succeeds. The annotated
body result fits the declared Int result, so the output-report path has no
failed cast evidence to summarize. There is no diagnostic leaf, no primary
finding, and no user-facing projection step.

Projection is therefore not a reporting pass over every expression Skeptic sees.
It only becomes visible after a boundary check fails.

## What Polymorphic Boundaries Add

The title of this spoke includes Blame For All because Skeptic also has to deal
with quantified boundaries. Those boundaries introduce a harder version of the
same reader question. With `classify`, the promise is concrete: this function
returns Keyword. With a quantified value, the promise is universal: this value
must behave correctly no matter which type is chosen for the abstract variable.

Skeptic protects that promise by sealing values that cross through abstract
boundaries and later checking whether the seal is used consistently. A normal
return mismatch asks, "Which declared output did this value fail to satisfy?" A
polymorphic-boundary failure asks, "Which universal promise allowed this value
to cross, and where did a later use show that the promise was unsafe?"

The reporting shape is the same: preserve the boundary that created the
obligation, the value shape that violated it, and the side of the program to
inspect first.

## Source Pointers

- `skeptic/checking/pipeline.clj:def-output-results` - builds output reports by comparing declared output Types with annotated method output Types.
- `skeptic/inconsistence/report.clj:output-cast-report` - runs the output cast and packages the cast metadata.
- `skeptic/analysis/cast/branch.clj:check-union-cast` - checks each source-union member against the declared target.
- `skeptic/analysis/cast/result.clj:leaf-diagnostics` - projects failed cast evidence into report diagnostics.
- `skeptic/inconsistence/report.clj:report-summary` - chooses the user-facing summary fields for output and input reports.
- `skeptic/inconsistence/path.clj:render-visible-path` - renders visible structural paths when diagnostics carry them.
