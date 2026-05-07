# Provenance

> *Snapshot of state as of 2026-05-06.*

Provenance is how Skeptic keeps a Type connected to its origin. A Type does not
only say "Keyword" or "String." It also carries where that Type came from:
schema, Malli, native declarations, overrides, or inference.

## Why Provenance Matters In The Example

The `classify` output check compares two Types:

```text
expected: Keyword from the declared Schema output
actual:   body result from annotation
```

The expected side and the actual side can have the same semantic family as other
Types elsewhere in the program. Provenance is what keeps the declared boundary
visible after many phases have passed. When a report says an expected Type came
from a declaration, that information is not reconstructed from the final text.
It was carried through the Type values and report metadata.

## The Provenance Record

Every Type constructor receives provenance first. A ground Type is built with a
provenance value, then the ground class or display form. A maybe Type is built
with provenance, then its inner Type. A function method is built with
provenance, then inputs and output.

That convention makes provenance part of the Type's identity at the program-data
level. The strict reader for provenance fails when a Type lacks it because later
phases depend on it.

## The Five Sources

Skeptic uses five source categories:

```text
:type-override  explicit override
:malli          MalliSpec admission
:schema         Plumatic Schema admission
:native         built-in native declaration
:inferred       annotation result
```

In `classify`, the declared Keyword output is `:schema`. The string body
alternative is `:inferred`. In `double-or-zero`, the maybe input is `:schema`,
while the narrowed branch Type is computed during annotation.

These sources let a report distinguish declared expectations from computed
expression shapes. Without that distinction, an output report would still know
Keyword and String, but it would have lost which one came from the user-written
boundary.

## Source Rank During Merges

When multiple Provenance values have to become one, Skeptic chooses by rank:

```text
:type-override
:malli
:schema
:native
:inferred
```

Earlier sources win. A user override outranks a Schema declaration. A Schema
declaration outranks a native declaration and an inferred expression. The rank
preserves explicit boundaries when Types are merged or combined.

For `classify`, this is why the declared output remains important after it is
compared with an inferred body result. The output check is about satisfying a
declared boundary, not about treating every Type source as interchangeable.

## Composite Types Have Anchor Provenance

When annotation joins branch results, it builds a composite Type. That composite
has its own provenance. The members also keep their provenance.

For `classify`, the body result can be read as:

```text
body result provenance: inferred
members:
  :zero  exact keyword value
  :even  exact keyword value
  "odd"  string
```

The body Type is the actual side of the output check. The declared Keyword Type
is the expected side. The report needs both the composite actual Type and the
declared expected Type.

## Dictionary Provenance And Type Provenance

Admission builds a Type dictionary, and it also keeps declaration-level
provenance. The dictionary answers "what Type does this var have?" The
provenance answers "where did that Type enter Skeptic?"

The split matters when a var is used in several places. Every use can consult
the same admitted Type, while reports can still attribute the expected Type to
the declaration that admitted it.

## Inferred Provenance During Annotation

Annotation creates Types for literals, locals, calls, and branch results. Those
Types carry inferred provenance from the annotation context. For the literal
`"odd"`, the ground String Type is inferred because it comes from expression
analysis, not from the `s/defn` declaration.

That inferred String then becomes part of the body result. It is the actual side
that fails against the schema-derived Keyword output.

## Source Pointers

- `skeptic/provenance.clj:make-provenance` - constructs Provenance values.
- `skeptic/provenance.clj:of` - reads provenance from a Type.
- `skeptic/provenance.clj:source` - reads the source category.
- `skeptic/provenance.clj:with-ctx` - supplies inferred provenance during annotation.
- `skeptic/provenance.clj:merge-provenances` - merges Provenance by source rank.
