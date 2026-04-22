# `Blame for All` Algorithm Reference

This document is a working reference for the runtime algorithm described in
Amal Ahmed, Robert Bruce Findler, Jeremy G. Siek, and Philip Wadler,
_Blame for All_ (POPL 2011).

Scope:

- focus on the operational algorithm in the polymorphic blame calculus
- explain how casts between `?` and `forall` are reduced
- explain how sealing enforces parametricity
- summarize the blame and subtyping results that justify the algorithm

Non-goals:

- reproduce every proof from the paper
- describe a concrete implementation language or runtime layout
- cover proof-only machinery such as static casts in full detail

## 1. Problem the algorithm solves

The paper extends gradual typing with parametric polymorphism.

The hard case is not ordinary casts like `I -> ?` or `? -> I`. The hard case is
casts such as:

- `? => forall X. X -> X`
- `forall X. X -> X => ?`

Naive type substitution is not enough. If the runtime simply substitutes a
concrete type for `X`, it can forget that a value used to be polymorphic, which
breaks parametricity. The algorithm therefore adds two runtime ideas:

- local type bindings, written `nu X:=A. t`
- sealed dynamic values, written `v : X => ?`

Together they let the runtime remember that a value came from an abstract type
variable and must not be inspected as if it were an ordinary dynamic value.

## 2. Runtime model

The polymorphic blame calculus extends the ordinary blame calculus with:

- type variables `X`
- quantified types `forall X. B`
- type abstraction `Lambda X. t`
- type application `t A`
- local type bindings `nu X:=A. t`

The important value forms are:

- constants
- lambdas
- grounded dynamic values `v : G => ?`
- polymorphic values `Lambda X. v`

In the polymorphic system, ground types are:

- base types such as `I` and `B`
- the dynamic function shape `? -> ?`
- type variables `X`

That last case is the key extension. When the ground is a type variable, the
value is sealed.

## 3. Compatibility before reduction

The calculus only attempts casts between compatible types.

The simply typed cases remain:

- every type is compatible with itself
- every type is compatible with `?`, and `?` is compatible with every type
- function compatibility is contravariant in the domain and covariant in the
  range

Polymorphism adds two asymmetric rules:

1. `A` is compatible with `forall X. B` when `A` is compatible with `B` and
   `X` does not appear free in `A`.
2. `forall X. A` is compatible with `B` when `A[X := ?]` is compatible with
   `B`.

The asymmetry matters. Casting _to_ a quantified type preserves the abstract
variable. Casting _from_ a quantified type instantiates that variable with `?`.

## 4. Core reduction algorithm

Evaluation is call-by-value, and unlike the standard polymorphic lambda
calculus it also reduces underneath type abstractions. That matters because a
term such as `Lambda X. blame r` must not get stuck as an apparent value.

### 4.1 Standard blame-calculus casts

The base cast machine is the same one used before polymorphism:

1. Function cast:
   casting `v : A -> B =>p A' -> B'` produces a wrapper function.
   The wrapper casts the argument from `A'` to `A`, applies `v`, then casts the
   result from `B` to `B'`.
   The argument-side cast flips blame polarity because function domains are
   contravariant.
2. Same-ground cast:
   a cast from ground type `G` to the same `G` is the identity.
3. Cast to dynamic:
   `v : A =>p ?` factors through a compatible ground type `G`, yielding
   `v : A =>p G => ?`.
4. Cast from dynamic:
   if a dynamic value is actually grounded at `G`, then `v : G => ? =>p A`
   collapses to `v : G =>p A` when `G` is compatible with `A`.
5. Conflict:
   if that ground `G` is not compatible with `A`, the cast fails with blame
   label `p`.
6. Dynamic type tests:
   `(v : G => ?) is G` returns true, and `(v : H => ?) is G` returns false
   when `H` and `G` differ.
7. Abort:
   blame in any evaluation position aborts the whole term.

Those rules already explain ordinary gradual typing. The rest of the algorithm
adds the machinery needed for `forall`.

### 4.2 Type application becomes a local runtime binding

The standard polymorphic beta rule would substitute a concrete type directly:

`(Lambda X. v) A  ->  v[X := A]`

The paper does not do that. Instead it reduces to:

`(Lambda X. v) A  ->  nu X:=A. v`

This preserves the fact that `X` is abstract at runtime. The local binding acts
as a scoped reminder that values grounded at `X` are still abstract, even if
`X` currently stands for a concrete type such as `I`.

The binding then pushes inward through values:

- `nu X:=A. c -> c`
- `nu X:=A. (lambda y:B. t) -> lambda y:B[X:=A]. (nu X:=A. t)`
- `nu X:=A. (Lambda Y. v) -> Lambda Y. (nu X:=A. v)` when that does not capture
  variables

This is why bindings are local and immobile. They do not float outward through
the whole program.

### 4.3 Sealing values of abstract type variables

When a value of abstract type `X` is cast to `?`, the result is a grounded
dynamic value whose ground is exactly `X`:

- `v : X => ?`

This is a sealed value. It behaves like an opaque token rather than an ordinary
dynamic value.

Two extra rules enforce that opacity:

1. Tamper-proof `is` tests:
   `(v : X => ?) is G` does not return true or false. It raises the global
   blame label `p_is`.
2. Tamper-proof binder crossing:
   `nu X:=A. (v : X => ?)` raises the global blame label `p_nu`.

The first rule stops programs from inspecting a sealed value and branching on
its hidden representation. The second stops a sealed value from escaping the
scope of the binder that gives meaning to the seal.

If the binder crosses a grounded value with a different ground, the binder is
pushed inward instead:

- `nu X:=A. (v : G => ?) -> (nu X:=A. v) : G => ?` when `G != X`

### 4.4 Casting to a quantified type: generalization

The `GENERALIZE` rule handles casts whose target is `forall X. B`:

- `v : A =>p forall X. B  ->  Lambda X. (v : A =>p B)`

with the freshness condition that `X` does not occur free in `A`.

Operationally, the runtime does not try to invent one particular witness for
`X`. It returns a polymorphic value that waits for an actual type application.
Only when the resulting function is instantiated does the runtime create a
local binding `nu X:=A`.

That is what preserves abstraction. A cast into a polymorphic type becomes a
value that can later seal its arguments and results relative to the chosen `X`.

### 4.5 Casting from a quantified type: instantiate with `?`

The `INSTANTIATE` rule handles casts whose source is `forall X. A`:

- `v : forall X. A =>p B  ->  (v ?) : A[X := ?] =>p B`

with a side condition that avoids overlapping with the simpler `GROUND` and
`GENERALIZE` rules.

This is the paper's most surprising algorithmic choice. The runtime always
instantiates the polymorphic source with `?`, even when the target type `B` is
more precise than `?`.

The justification is the Jack-of-All-Trades principle:

- if instantiating `X` with some concrete type `C` yields an answer, then
  instantiating `X` with `?` also yields an answer
- instantiating with `?` can be more permissive than instantiating with `C`,
  but it never loses successful executions

So `?` is used as the universal operational witness for eliminating `forall`
during a cast.

## 5. End-to-end algorithm as an implementation recipe

If you wanted to implement the paper's runtime behavior directly, the reduction
strategy is:

1. Evaluate the term to a value using call-by-value evaluation contexts.
2. When a type application reaches `Lambda X. v`, reduce it to `nu X:=A. v`
   instead of substituting `A` for `X`.
3. Push `nu` inward through values until it disappears or reaches a sealed
   value.
4. When reducing casts, use the ordinary blame-calculus rules first:
   function wrapping, ground identity, factoring through `?`, collapse, and
   conflict.
5. Treat type variables as valid ground types, so casts from `X` to `?` create
   sealed values.
6. Reject any attempt to inspect a sealed value with `is`.
7. Reject any attempt to move a sealed `X`-grounded value through `nu X:=A`.
8. For `A => forall X. B`, reduce by `GENERALIZE`.
9. For `forall X. A => B`, reduce by `INSTANTIATE` using `?`.
10. Propagate blame immediately through the enclosing evaluation context.

The two places where the algorithm differs most from a naive implementation are:

- it preserves polymorphic abstraction at runtime with `nu`
- it treats abstract values as sealed dynamic values, not ordinary dynamic ones

## 6. Worked examples from the paper

### 6.1 Parametric identity succeeds

Casting `id? = <lambda x. x>` to `forall X. X -> X` works:

1. generalize to a polymorphic value
2. instantiate at some concrete type, which produces `nu X:=I`
3. seal the argument as it crosses into the abstract position
4. unseal it again through a matching collapse
5. return the original value

So the result behaves like the true identity function.

### 6.2 Non-parametric integer increment becomes undefined

Casting `inc? = <lambda x. x + 1>` to `forall X. X -> X` does not let the
function masquerade as parametric.

After instantiation, the body tries to use the sealed argument in integer
addition. That forces a cast path incompatible with the seal and produces
ordinary blame from the embedded integer operation. Operationally, the result is
the everywhere-undefined function for that polymorphic type.

### 6.3 Inspecting a sealed value is forbidden

Casting:

- `test? = <lambda x. if (x is I) then (x + 1) else x>`

to `forall X. X -> X` also fails to produce a useful parametric function.

The problem is the `is I` test. Once the argument has been sealed as `v : X => ?`,
testing it is treated as tampering and raises `p_is`.

This is what stops a cast from turning a representation-sensitive function into
an apparently parametric one.

### 6.4 Returning a sealed value out of scope is forbidden

The paper also studies casting `id?` to `forall X. X -> ?`.

Without the binder-crossing check, the result could leak a sealed value into
plain dynamic code. The `NUTAMPER` rule rejects that escape with `p_nu`.

## 7. Why blame lands on the less precise side

The dynamic algorithm is backed by three related type relations:

- positive subtype: rules out blame on the term side
- negative subtype: rules out blame on the context side
- naive subtype: the paper's precision ordering

The reason there are separate positive and negative relations is the function
cast rule. Domain casts reverse direction, so blame polarity also flips there.

The main consequences are:

- Blame Theorem:
  if a cast goes from a more precise type to a less precise type and fails, the
  less precise context gets blamed; if it goes the other way, the less precise
  term gets blamed.
- Subtyping Theorem:
  if `A` is a subtype of `B`, then a cast `A => B` cannot produce blame for
  that cast at all.

This is the paper's formal version of "well-typed programs cannot be blamed."

## 8. What is algorithmic and what is proof machinery

For understanding or reimplementing the runtime behavior, the essential pieces
are:

- ordinary blame-calculus casts
- local type bindings `nu X:=A. t`
- sealed values `v : X => ?`
- `GENERALIZE`
- `INSTANTIATE` with `?`
- tamper checks for `is` and binder crossing

Sections 10 and 11 add static casts to make the proof of the Jack-of-All-Trades
principle go through more cleanly. Those static casts are important for the
meta-theory, but they are not the core runtime idea.

## 8.1 Skeptic adaptation note

For Skeptic, the key boundary is:

- ordinary Clojure analysis still infers first-order lower-bound facts such as
  "this result includes `Int`"
- those lower bounds are enough to prove many guaranteed inconsistencies without
  any quantified machinery
- the paper's polymorphic rules only apply once a quantified type is already
  present in the semantic type layer

So an implementation that follows the paper should not synthesize `forall`
opportunistically at call sites or during ordinary cast checking. Quantified
types are separate semantic inputs to the cast engine, while ordinary inference
remains first-order.

## 9. Practical summary

The algorithm can be summarized in one sentence:

When a cast crosses a polymorphic boundary, keep type abstraction explicit at
runtime, seal any value that flows through an abstract type variable, forbid
inspection or escape of that seal, and use ordinary blame-calculus reduction
everywhere else.

That is the mechanism that lets the calculus support both gradual typing and
parametric polymorphism without losing the usual blame guarantees.

## Declaration Dict Invariants

In Skeptic, after the admission boundary (Schema via `schema->type`, MalliSpec via `malli-spec->type`), the per-namespace declaration dict holds bare Types keyed by qualified symbol. No `:typings`, `:output-type`, `:arglists`, `:accessor-summary`, or `:type` wrapper survives on a dict value.

Every Type is itself a `defrecord` whose first field is `:prov`, a `Provenance` record. `prov/of` is the strict reader; a missing `:prov` throws. There is no `prov/unknown` sentinel anywhere in the system. Ingestion boundaries supply the root Provenance (`:schema`, `:malli-spec`, `:native`, `:type-override`, `:inferred`); combinators that build composite Types take an explicit anchor Provenance and do not derive the container's identity from its items.

Domain origin (Schema / MalliSpec / native / type-override) is also carried separately as a `Provenance` record in a parallel `:provenance` map (see `skeptic.provenance`). The pipeline's `check-namespace` merges inferred provenances (from the analyzer) with the declared-source provenance map before reporting, so every finding can attach a real `:source` read from the blamed Type's `:prov`. It is read only to attach `:source` on findings; it is never used to reconstruct schemas or to branch type reasoning.

## Primary source

- Ahmed, Findler, Siek, Wadler, "Blame for All", POPL 2011:
  <https://homepages.inf.ed.ac.uk/wadler/papers/blame-for-all/blame-for-all.pdf>
