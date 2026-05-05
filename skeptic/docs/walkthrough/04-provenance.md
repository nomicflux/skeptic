# Provenance

> *Snapshot of state as of 2026-05-05.*

Every Type carries a `:prov` field that records where the Type came
from. Provenance is what makes a finding's `:source` field meaningful
and what governs ranked merging at admission. This spoke fixes the
contract.

## Prerequisites

[Spoke 03 (Type Domain)](03-type-domain.md). Comfort with the idea
that a value can carry metadata about where it came from.

## Where this fits

Fourth on the Contributor path. Provenance is what makes a finding's
`:source` field meaningful in [spoke 11](11-user-facing-surfaces.md).
It also explains how cast results remember which side of a cast came
from a declared schema and which from inference.
[Spoke 05](05-admission-paths.md) builds on this by showing how each
admission path stamps its source onto the Types it produces.

## Every Type carries `:prov`

Every Type record's first field is `:prov`. Every positional
constructor — `at/->GroundT`, `at/->MapT`, `at/->FunT`, `at/->FnMethodT`,
`at/->MaybeT`, `at/->UnionT`, all of them — takes a `Provenance` as
its first argument. There is no Type record without a `:prov`.

A `Provenance` is a small record:

```clojure
(prov/make-provenance
  {:source       :schema           ;; one of the five named sources
   :qualified-sym 'my-ns/foo       ;; the symbol the value came from, if any
   :declared-in   'my-ns           ;; the namespace the declaration lived in
   :var-meta     {:doc "..."}      ;; original metadata, when relevant
   :refs         [...]})           ;; cross-references for display
```

The reader is `prov/of`, and it is *strict*: if you call
`(prov/of t)` on a value that lacks a `:prov` field (or has it set to
`nil`), `prov/of` throws. There is no `prov/unknown` sentinel and no
fallback. The strictness is deliberate — it rejects code that
fabricates a Type without a real provenance at the earliest possible
point.

A consequence: when you build a Type, you must supply a real
`Provenance`. The conventions for *which* provenance to supply are
the rest of this spoke.

## The five sources

Every `Provenance` has one of exactly five `:source` values. Each
corresponds to a way Skeptic learned about the Type.

**`:schema`** — the Type was admitted from a Plumatic Schema. Created
by `skeptic.analysis.bridge/schema->type` and the helpers it calls.
Almost every Type in a real run has `:schema` provenance, because
most projects use Plumatic.

**`:malli`** — the Type was admitted from a Malli `:malli/schema`
metadata value. Created by `skeptic.analysis.malli-spec.bridge/malli-spec->type`.

**`:native`** — the Type was admitted from a built-in Skeptic native
function descriptor. Created by `skeptic.analysis.native-fns/static-call-native-info`
and friends. The native dict covers `clojure.core` arithmetic and
collection functions whose Plumatic schemas are not always
informative.

**`:type-override`** — the Type was admitted from a `:type-overrides`
entry in `.skeptic/config.edn`, or from `^{:skeptic/type T}` metadata
on an expression. Created by `skeptic.config` and consumed by
`skeptic.typed-decls` (and by `apply-type-override` in the annotation
pass).

**`:inferred`** — the Type was produced by the analyzer rather than
admitted. Created by `prov/inferred` (with the analyzer ctx) and by
the constructors in `skeptic.analysis.value` and
`skeptic.analysis.type-ops`. Every Type the annotation pass attaches
to a node carries `:inferred` provenance.

The `:source` value is the *only* place a finding's `:source` field
comes from. When [spoke 11](11-user-facing-surfaces.md) renders a
finding showing "[source: schema]" or "[source: inferred]," that
keyword is the same value that lives on the blamed Type's `:prov`.

## Source rank and `merge-provenances`

When two Types meet — at a union construction, at a cast result root,
at dict admission with a key collision — Skeptic computes a single
provenance for the result via `prov/merge-provenances`. The merge is
governed by a total order over `:source` values:

```text
:type-override (rank 0)
:malli         (rank 1)
:schema        (rank 2)
:native        (rank 3)
:inferred      (rank 4)
```

The lower-rank source wins. The intuition is "explicit user intent
beats library declaration beats library inference." A
`:type-override` is the user telling Skeptic to forget what it
inferred; `:schema` and `:malli` are declarations the user wrote;
`:native` is Skeptic's built-in knowledge; `:inferred` is whatever
the analyzer produced.

For finding attribution, the rank matters because cast roots merge
the source and target Types' provenances: the finding then carries
the lower-rank (more explicit) source. A cast of `:inferred` source
against `:schema` target produces a finding marked `[source:
schema]`, telling the user the constraint they violated came from a
declared schema.

## Combinator anchor provenance

When a combinator builds a *composite* Type — a union of members, an
intersection, a merged map, a joined seq — the result carries an
**anchor provenance** supplied by the *caller*, not derived from the
constituents. Constituents keep their own provs on themselves; the
composite owns its own.

Concretely, the combinators that take an anchor are:

| Combinator                   | Anchor parameter            | Builds                      |
|------------------------------|-----------------------------|-----------------------------|
| `av/join anchor-prov types`  | first arg                   | union from a sequence       |
| `amo/merge-map-types anchor-prov types` | first arg        | merged `MapT`               |
| `amoa/merge-types anchor-prov types`    | first arg        | shape-driven combination    |
| `coll/concat-output-type anchor-prov args` | first arg     | `concat`-style output       |

Why anchor-from-the-caller and not anchor-derived-from-items? Because
the *container* has its own identity. A union built at a particular
analyzer node owns its own location; it doesn't inherit identity from
the random mix of declared and inferred members thrown into it. The
combinator's job is to compute the *shape* of the composite. The
combinator's caller knows *where the composite lives* and supplies
the anchor.

A bug pattern this rule prevents: a union built from one declared
member and one inferred member would otherwise have ambiguous
provenance. With anchor-supplied provenance, the union's provenance
is whatever the construction site says; its members' individual
provenances remain unchanged on themselves.

*Figure: A `UnionT` whose three members were admitted from different sources; the composite's anchor provenance comes from the caller.*

```mermaid
flowchart LR
  subgraph members [Members keep their own prov]
    m1[GroundT Int<br/>:prov schema]
    m2[GroundT Str<br/>:prov inferred]
    m3[ValueT(:k)<br/>:prov native]
  end
  caller([Caller's anchor prov])
  union[UnionT<br/>:prov ← anchor]
  m1 --> union
  m2 --> union
  m3 --> union
  caller --> union
```

## The provenance map vs. the dict

After admission ([spoke 05](05-admission-paths.md)), a per-namespace
`namespace-dict` produces *two* maps keyed by qualified symbol:

- the **declaration dict** `{qualified-sym → Type}` — what the cast
  engine reads;
- the **provenance map** `{qualified-sym → Provenance}` — what the
  finding renderer reads.

The Type in the declaration dict already carries its own `:prov` (it
must — every Type does). So why a parallel map?

The answer is that the provenance map records the *declaration-level*
origin: which file, which namespace, which `:source`. The Type's own
`:prov` records the *construction-level* origin of that particular
Type record, which can be the same or finer-grained. They agree at
the dict's top level — admission stamps both with the same source —
and diverge only when downstream code rebuilds a Type without
re-fetching the dict.

For finding rendering, Skeptic reads the parallel map for `:source`
on output: it's the declaration-level statement. For type reasoning,
the cast engine reads the Type's own `:prov`, because the cast engine
needs to know about the *Type instance* in front of it, not the
declaration whence it came.

### In-depth: with-ctx and `prov/inferred`

***Skip if reading the Gist path.***

Inside the analyzer, the Provenance for *inferred* values comes from
the analyzer ctx. The pattern is:

```clojure
;; somewhere inside an annotator
(let [p (prov/with-ctx ctx)]   ;; reads the ctx-bound provenance
  (at/->GroundT p :int 'Int))  ;; uses it as the new Type's prov
```

`prov/with-ctx` reads a known key from the ctx (the analyzer's local
state) and returns the Provenance Skeptic should attach to any Type
constructed under that ctx. New inferred Types use the ctx
provenance, threading the analyzer's identity through every produced
node.

`prov/inferred` is a thin convenience for the common case of building
an `:inferred` provenance from a node's source location. The
analyzer ctx is what `prov/inferred` reads from; it's also what
`prov/with-ctx` returns. Together they ensure every inferred Type
made during annotation knows which form it came from, which in turn
gives the finding renderer enough information to point at the right
source line.

The contract for new annotation code: never construct a Type with a
synthetic provenance. Always read the ctx via `prov/with-ctx` (or
read the source-position-derived provenance from the parent node).
If you find yourself reaching for a sentinel "inferred" prov outside
the ctx machinery, you have stepped outside the analyzer's
provenance discipline.

## Marquee functions

| Function                 | File                       | Role                                                                  |
|--------------------------|----------------------------|-----------------------------------------------------------------------|
| `prov/make-provenance`   | `skeptic/provenance.clj`   | Canonical constructor with source assertion.                           |
| `prov/of`                | `skeptic/provenance.clj`   | Strict reader; throws if a Type lacks `:prov`.                        |
| `prov/source`            | `skeptic/provenance.clj`   | Reads the named source from a Provenance.                             |
| `prov/with-ctx`          | `skeptic/provenance.clj`   | Reads the analyzer-ctx provenance (used by inferred-Type construction). |
| `prov/merge-provenances` | `skeptic/provenance.clj`   | Source-rank-based merge; the lower rank wins.                         |

## Worked example here

`classify`'s declared output Type is
`GroundT prov-schema :keyword 'Keyword`, where `prov-schema` carries
`:source :schema` and `:qualified-sym 'skeptic.walkthrough.example/classify`.
The inferred body Type is a `UnionT` whose `:prov` carries
`:source :inferred` (and a source-location reference to the cond
form).

When the cast root is built, `merge-provenances` reduces the source
and target's provs: `:schema` (rank 2) beats `:inferred` (rank 4),
so the cast root carries `:schema`. The eventual finding's `:source`
field is `schema`. That's why Skeptic reports the failure as
"declared by classify's schema": the responsibility for the
constraint lies with the declaration, not the inferred body.

## Where to next

- **Continue (Contributor path):** [Admission Paths (05)](05-admission-paths.md)
- **Return:** [Hub](README.md)
