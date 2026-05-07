# Annotation Pass

> *Snapshot of state as of 2026-05-06.*

Admission records declared Types. Annotation computes expression Types. It walks
an analyzer tree and attaches the semantic facts that checking will consume.

## The Input To Annotation

After admission, Skeptic knows that `classify` is declared as:

```text
Int -> Keyword
```

Annotation starts from the source form and analyzer output. The analyzer tree
describes the structure of the function body: a function, a method, a parameter,
tests, branches, calls, and literals. Annotation keeps that tree and adds Type
data to nodes.

The important handoff is this: checking will later ask the annotated method body
for its output Type. Annotation is responsible for making that Type precise
enough to check.

## Annotating Literals In `classify`

The branch result values in `classify` annotate independently:

```text
:zero  -> exact value Type, inner Keyword
:even  -> exact value Type, inner Keyword
"odd"  -> String Type
```

The exact keyword values are more precise than plain Keyword. They can still
satisfy Keyword later because an exact keyword value inhabits the Keyword Type.

The string literal is also precise. Its Type is not a vague dynamic value. That
precision is why the later output cast can identify an unacceptable alternative
instead of losing the mismatch.

## Annotating Branch Results

The conditional body must represent every reachable result. For `classify`,
`zero?` and `even?` do not exhaust every Int. The fallback branch is reachable.
The body result therefore contains all three branch alternatives.

This is the first moment the later finding becomes possible:

```text
body output Type includes string
declared output Type is Keyword
```

No report is emitted yet. Annotation is only computing the actual side of the
future output comparison.

## Annotating Locals And Calls In `double-or-zero`

For `double-or-zero`, the local `n` starts with the admitted input Type:

```text
n : Maybe[Int]
```

The test `(some? n)` is an invocation node whose shape is recognized as a
predicate about `n`. Annotation uses that fact when it annotates the branches.

Inside the then branch, the local environment treats `n` as Int. The call
`(* 2 n)` receives actual argument Types for literal `2` and narrowed `n`. That
call can then be checked against native expectations for multiplication.

## Walking One Body Node By Node

Read the `classify` body from the leaves upward. The parameter local `n` starts
from the admitted input Type Int. The predicate calls `zero?` and `even?` are
checked as calls whose arguments are drawn from that local. The result
expressions then receive their own Types:

```text
then value for zero?  -> ValueT(:zero)
then value for even?  -> ValueT(:even)
else value            -> GroundT String
```

The branch node cannot choose one of those results at annotation time. It keeps
the reachable alternatives. That is the actual Type later used by the output
check.

Now read `double-or-zero` the same way. The parameter local starts as
Maybe[Int]. The test node `(some? n)` is recognized as a predicate over that
local. The then branch is annotated with a refined local environment, so the
argument node for `n` in `(* 2 n)` reads as Int. The multiplication node then
receives a numeric result Type from its native call information. The else branch
is just the literal `0`. The parent branch joins those branch results into the
function body's output Type.

This leaf-to-parent walk is the practical difference between a useful annotation
pass and a shallow source scan. The later checker receives the computed body
Types directly from annotation.

## Origin Data

Annotation also records where some values came from. A parameter local has a
root origin. A value read from a map key can have a map-key lookup origin. A
branch result can carry a branch origin.

Origins are how branch facts find the value they refine. `(some? n)` is useful
because annotation can connect the predicate to the local `n`. Without that
connection, the test would be a boolean expression with no local Type effect.

## Type Overrides During Annotation

An expression can carry Skeptic Type metadata. Annotation imports that metadata
through the Schema bridge and replaces the computed Type for the expression with
the override Type.

This is expression-local. It does not add a new dictionary entry for a var. It
changes the Type that later phases see at that expression position.

## The Annotated-Node API

Later code reads annotated nodes through helper functions. A call checker asks
for call arguments and expected argument Types. Output checking asks for method
bodies and method output Types. Report construction asks for source expression
and context.

Using the annotated-node API keeps those consumers focused on the semantic
fields they need. The raw analyzer shape is still present, but later phases do
not have to rediscover how every node stores its children.

## The Output Of Annotation

For the worked example, the important annotation outputs are:

```text
classify method body:
  possible outputs include :zero, :even, "odd"

double-or-zero method body:
  then branch uses n as Int
  else branch returns 0
  body satisfies Int
```

Those are actual Types. Checking will compare them with expected Types from
admission.

## Source Pointers

- `skeptic/analysis/annotate.clj:annotate-node` - annotates one analyzer node.
- `skeptic/analysis/annotate.clj:annotate-dispatch` - routes nodes by operation tag.
- `skeptic/analysis/annotate.clj:annotate-form-loop` - analyzes and annotates a form.
- `skeptic/analysis/annotate/api.clj:with-type` - attaches a Type to a node.
- `skeptic/analysis/annotate/api.clj:node-op` - reads a node operation tag.
- `skeptic/analysis/annotate/control.clj:annotate-if` - annotates branch control flow.
