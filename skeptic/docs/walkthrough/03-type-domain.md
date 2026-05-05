# The Type Domain

> *Snapshot of state as of 2026-05-05.*

The Type domain is Skeptic's internal language of types. Every value
the cast engine reasons about is a Type record. This spoke catalogues
the 24 record kinds, then explains the eight you will encounter most
often in real code.

## Prerequisites

[Spokes 01](01-pipeline-tour.md) and [02](02-three-domains.md). Comfort
with `defrecord` and Clojure's protocol/`instance?` mechanism. If any
of these are unfamiliar, the [hub README's reading paths](README.md#reading-paths)
point to the right earlier spoke.

## Where this fits

Third on the Contributor path. After this spoke, the reader can read any
function in the Type domain (`type-ops.clj`, `type-algebra.clj`, the
cast namespaces) and recognize what each value is.
[Spoke 04](04-provenance.md) builds on this by explaining how Provenance
threads through every Type.

## What a Type is

In Skeptic, a Type is a `defrecord` whose first field is `:prov` (a
`Provenance` record — see [spoke 04](04-provenance.md)) and whose
remaining fields encode the type's shape. The records are defined in
`skeptic/analysis/types.clj`. Every constructor takes `prov` first;
`prov/of` is the strict reader and throws if a record's `:prov` is
missing or `nil`.

A protocol marker, `proto/SemanticType`, distinguishes Type records
from arbitrary maps. The universal predicate is
`at/semantic-type-value?`. Equality between Types ignores `:prov`
recursively via `at/type=?`; ordinary `=` does not.

## The 24 Type kinds, at a glance

The records group into six families. Each row gives the constructor
name, what the kind represents, and a one-line example of what kind of
value it might wrap.

| Family       | Kind                | Represents                                                          | Example                                  |
|--------------|---------------------|---------------------------------------------------------------------|------------------------------------------|
| Leaves       | `DynT`              | The gradual `?`; cast to it always succeeds.                        | `Dyn`                                    |
| Leaves       | `BottomT`           | The empty type; produced by contradicted assumptions.               | `Bottom`                                 |
| Leaves       | `GroundT`           | A primitive named type.                                             | `GroundT Int`, `GroundT Str`             |
| Leaves       | `NumericDynT`       | Gradual numeric supertype; matches numbers without committing.      | `NumericDyn`                             |
| Leaves       | `ValueT`            | A specific value, of a specific Type.                               | `ValueT(:zero) : Keyword`                |
| Leaves       | `RefinementT`       | A value matched by a predicate (e.g., `pos?`).                       | `RefinementT(pos?) : Int`                |
| Leaves       | `AdapterLeafT`      | An internal leaf used by certain adapter values at the boundary.     | (rare in user-facing code)               |
| Leaves       | `OptionalKeyT`      | A wrapper marking an optional key in a map schema.                  | `OptionalKey(:k) : Int`                  |
| Collections  | `MapT`              | A map type with key→value entries (and optional rest).              | `MapT {:k Int}`                          |
| Collections  | `VectorT`           | A vector with an optional tuple prefix and element Type.            | `VectorT [Int Int Str…]`                 |
| Collections  | `SetT`              | A set whose elements have a Type.                                    | `SetT Int`                               |
| Collections  | `SeqT`              | A seq/list whose elements have a Type.                               | `SeqT Str`                               |
| Function     | `FnMethodT`         | One arity of a function: input list + output Type.                  | `FnMethodT [Int] → Int`                  |
| Function     | `FunT`              | A function — a collection of `FnMethodT` arities.                    | `FunT [FnMethodT[Int → Int], …]`         |
| Branching    | `MaybeT`            | `T` or `nil`.                                                        | `MaybeT[Int]`                            |
| Branching    | `UnionT`            | A type whose inhabitants are alternatives.                           | `UnionT[Int, Str]`                       |
| Branching    | `IntersectionT`     | A type whose inhabitants satisfy every member.                       | `IntersectionT[A, B]`                    |
| Branching    | `ConditionalT`      | A type that depends on which branch of a discriminator was taken.    | `Cond[(zero? n) → ValueT(:zero), …]`     |
| References   | `VarT`              | A reference to another declared symbol (resolved at use).            | `VarT 'my-ns/foo`                        |
| References   | `PlaceholderT`      | A placeholder used during recursive admission.                       | `Placeholder`                            |
| References   | `InfCycleT`         | A guarded marker for self-referential schemas.                       | `InfCycle`                               |
| Quantified   | `TypeVarT`          | A bound type variable inside a `ForallT` body.                       | `TypeVar X`                              |
| Quantified   | `ForallT`           | `forall X. T` — a polymorphic type.                                  | `Forall[X. X → X]`                       |
| Quantified   | `SealedDynT`        | A value cast from `X` into Dyn; tamper-protected.                    | `SealedDyn(X)`                           |

The leaves are the building blocks; the collection, function, and
branching kinds compose them; the reference kinds break recursion; the
quantified kinds appear only at admission and at runtime under cast
([spoke 10](10-blame-for-all-and-projection.md)).

## The eight you'll see most

These eight account for the overwhelming majority of types in any real
Skeptic run. Each subsection gives constructor signature plus a tiny
worked-example value drawn from `classify` or `double-or-zero`.

### `GroundT` — primitive named types

```clojure
(at/->GroundT prov tag display-symbol)
```

`tag` is a small keyword (`:int`, `:string`, `:keyword`, `:bool`,
`:symbol`) or a class symbol; `display-symbol` is the Clojure-side
display name (`'Int`, `'Str`, `'Keyword`, …). Every primitive in
admitted Schemas — `s/Int`, `s/Str`, `s/Keyword`, `s/Bool` — becomes
a `GroundT`. The declared output of `classify` is
`GroundT prov :keyword 'Keyword`.

### `MaybeT` — `T` or `nil`

```clojure
(at/->MaybeT prov inner-type)
```

`MaybeT` represents a value that can be `nil`. It comes from
`s/maybe`, `[:maybe …]`, optional-key map values, and unions that
include `nil`. `double-or-zero`'s argument is
`MaybeT[GroundT Int]`. The cast engine treats `MaybeT` specially in
the dispatch ladder ([spoke 09](09-cast-dispatch.md)): a cast from
`MaybeT[T]` to a non-nil target splits into "the nil case" (asks
whether the target accepts `nil`) and "the inner case" (a recursive
cast of `T`).

### `UnionT` — alternatives

```clojure
(at/->UnionT prov members)
```

`UnionT` represents a value whose Type is one of `members`. Members
are deduplicated by shape (using `at/type=?`) and never empty.
`classify`'s body, before any cast, is roughly
`UnionT[ValueT(:zero), ValueT(:even), GroundT Str]` — three leaf
alternatives joined from the cond's three arms. A cast against a
target with a `UnionT` source recurses on each member.

### `MapT` — keyed maps

```clojure
(at/->MapT prov entries) ;; entries is a map of key-Type → value-Type
```

`MapT` represents a Clojure map with declared key and value Types.
Entries can be exact-key (a `ValueT`-keyed entry) or broad-key
(a non-`ValueT`-keyed entry, e.g., `{Keyword Int}`). The cast engine
matches entries by key compatibility, with a separate algebra for
broad-key candidate selection (in-depth in
[spoke 09](09-cast-dispatch.md)).

### `FunT` (with `FnMethodT`) — functions

```clojure
(at/->FunT prov methods)         ;; methods: vector of FnMethodT
(at/->FnMethodT prov inputs out) ;; inputs: vector of Type; out: Type
```

A `FunT` is a collection of `FnMethodT`s, one per arity. Skeptic
selects the matching method for a call site via `at/select-method`,
which considers fixed-arity and variadic arities. `classify`'s
declared Type is `FunT[FnMethodT[GroundT Int → GroundT Keyword]]`
— one arity, one input, one output.

### `ValueT` — singleton-valued leaves

```clojure
(at/->ValueT prov inner value)
```

`ValueT` represents a specific value of a specific inner Type. The
keyword literal `:zero` annotates as
`ValueT prov (GroundT prov :keyword 'Keyword) :zero`. `ValueT`s are
*not* the same as `RefinementT`: a `ValueT` is a single value; a
`RefinementT` is a set of values matched by a predicate. `ValueT`s
are how Skeptic recognizes that `(zero? n) :zero` returns a keyword
specifically equal to `:zero`, which matters for closed-sum
exhaustiveness ([spoke 07](07-closed-sum-exhaustiveness.md)).

### `ConditionalT` — branch-discriminated types

```clojure
(at/->ConditionalT prov branches)
;; each branch: [predicate type discriminator]
```

`ConditionalT` represents "the type of this value depends on which
branch you took." Each branch is a triple of a predicate form, the
type produced when that predicate held, and a *discriminator* — a
description of which observable was tested. `ConditionalT` is built
during annotation when an `if` produces a value whose Type is
different in each arm. The third position (discriminator) is filled
during a second pass — see the in-depth section below.

### `SealedDynT` — sealed dynamic values

```clojure
(at/->SealedDynT prov ground-type-var)
```

`SealedDynT` is the runtime artefact of casting a value of an abstract
type variable into Dyn. It carries the binder name and is
tamper-protected: inspecting it via predicates (`is`-like operations)
raises `:is-tamper`; smuggling it across the binder's scope raises
`:nu-tamper`. Sealed values appear only when the cast engine traverses
quantified types — see [spoke 10](10-blame-for-all-and-projection.md).

## Equality and deduplication

Two Types are `=` only if their `:prov` fields are also `=`. That is
almost never what you want. The Type-shape equality you want is
`at/type=?`, which strips `:prov` recursively before comparing:

```clojure
(at/type=? (at/->GroundT prov-a :int 'Int)
           (at/->GroundT prov-b :int 'Int))
;; => true
```

Use `at/type=?` in tests and in any code that asks "is this the same
type, structurally?" The canonical deduplicator over a sequence of
Types is `at/dedup-types`, which preserves the first occurrence of
each shape. Union construction uses `at/dedup-types` so that
`UnionT[Int, Int, Int]` collapses to `UnionT[Int]`.

The provenance-stripping rule is also why `=` between two Types of the
same shape but different `:prov` returns `false`. This is intentional:
the cast engine's leaf-equality check uses `at/type=?` and is unaffected
by where the two Types came from; the rendering layer reads `:prov` to
attribute findings.

## Normalization

Many code paths assume Types arrive in *canonical form*: union members
deduplicated, `MaybeT[BottomT]` collapsed to `BottomT`,
`UnionT[T]` collapsed to `T`, and so on. `ato/normalize` (in
`skeptic/analysis/type_ops.clj`) is the canonical way to bring a Type
into canonical form. The cast entry point (`check-cast`) normalizes
its source and target before dispatching; narrowing entry points
normalize before refining; the display layer normalizes before
rendering.

Inside the cast dispatcher, normalization runs *once* per cast. The
structural rules (function, map, vector, set, …) compose child casts
without re-normalizing because their inputs are already normal by
construction.

### In-depth: the protocol marker and tagged-map predicates

***Skip if reading the Gist path.***

`proto/SemanticType` is a tag protocol — it has no methods; its only
purpose is to mark a record as "a Type." Each Type record extends the
protocol via `extend-type` in `skeptic/analysis/types.clj`. The
universal predicate `at/semantic-type-value?` works by `instance?` of
the protocol marker.

A second mechanism exists for use cases where importing every record
class is awkward: each record carries a per-record tag keyword
(`::ground-type`, `::maybe-type`, …) under a known key, and a
top-level type-tag — `:skeptic.analysis.types/semantic-type` — is set
to a deterministic value on every record. This lets downstream code
match "is this a Type and which kind?" without protocol dispatch.
The pattern is used inside `bridge/render.clj` and inside output
serialization in [spoke 11](11-user-facing-surfaces.md), where JSON
output keys depend on the record kind.

### In-depth: ConditionalT and the discriminator slot

***Skip if reading the Gist path.***

A `ConditionalT` branch is a triple `[predicate type discriminator]`.
The first two positions are filled during ordinary annotation: when
an `if` produces a value whose Type differs across arms, each arm
contributes a `[predicate type nil]` triple. The third position
(the discriminator) is filled by a *second pass*,
`enrich-conditional-type` in `skeptic/checking/pipeline.clj`, after
accessor summaries have been collected for the whole project.

The discriminator captures *what observable* the predicate was
testing — a local, a map-key lookup, a class-test on a keyword.
Downstream narrowing in [spoke 08](08-narrowing-and-origins.md) uses
the discriminator to decide which assumption (if any) about a local
should refine that local's Type when the conditional is matched.

The two-pass design exists because accessor summaries are a project-
wide property: a `(:k m)` lookup against a map declared in another
namespace can only be recognized after that namespace's declarations
are admitted. The first pass attaches predicates and inferred
types; the second pass back-fills discriminator descriptors with the
project-wide accessor information now in scope.

## Marquee functions

| Function                  | File                              | Role                                                                |
|---------------------------|-----------------------------------|---------------------------------------------------------------------|
| `at/->MaybeT`             | `skeptic/analysis/types.clj`      | Representative constructor; signature shows the prov-first pattern. |
| `at/type=?`               | `skeptic/analysis/types.clj`      | Provenance-stripping equality.                                      |
| `at/dedup-types`          | `skeptic/analysis/types.clj`      | The canonical deduplicator over a sequence of Types.                 |
| `ato/normalize`           | `skeptic/analysis/type_ops.clj`   | Canonical form before dispatch / equality / display.                |
| `at/select-method`        | `skeptic/analysis/types.clj`      | Picks the matching `FnMethodT` for a call's arity.                   |
| `at/semantic-type-value?` | `skeptic/analysis/types.clj`      | Universal "is this a Type?" predicate.                               |

## Worked example here

`classify`'s body, fully annotated, is roughly:

```text
UnionT[ValueT(:zero) : GroundT Keyword,
       ValueT(:even) : GroundT Keyword,
       GroundT Str]
```

A union of three leaves. The first two are `ValueT`s wrapping the
literal keywords `:zero` and `:even`; the third is the result of the
`:else` arm, a plain `GroundT Str`. The declared output is
`GroundT Keyword`. Casting the union against the keyword fails on
the third member ([spoke 09](09-cast-dispatch.md)).

`double-or-zero`'s argument is `MaybeT[GroundT Int]`. Inside the
then-branch (after narrowing in [spoke 08](08-narrowing-and-origins.md))
it becomes `GroundT Int`; the body is `(* 2 n)`, also `GroundT Int`,
so the cast against the declared `GroundT Int` output succeeds.

## Where to next

- **Continue (Contributor path):** [Provenance (04)](04-provenance.md)
- **Continue (Gist path):** [Cast Dispatch (09)](09-cast-dispatch.md) — marquee only
- **Return:** [Hub](README.md)
