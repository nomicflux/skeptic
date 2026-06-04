# Admission Paths

> *Snapshot of state as of 2026-05-06.*

Admission is the boundary where user-facing declarations become Skeptic Types.
It answers the question "what did this namespace declare?" before annotation
answers "what do the expressions compute?"

## The Shape Produced By Admission

For each namespace, admission produces a dictionary keyed by qualified symbol.
The values are Types.

For the worked example, the useful entries are:

```text
skeptic.walkthrough.example/classify
  inputs:  Int
  output:  Keyword

skeptic.walkthrough.example/double-or-zero
  inputs:  Maybe[Int]
  output:  Int
```

That dictionary is the contract later phases consume. Checking does not return
to the `s/defn` form to ask what output `classify` declared. It asks the
dictionary for the admitted function Type and selects the method matching the
body arity.

## Plumatic Schema Admission

The Schema collector reads declarations such as `s/defn`, extracts input and
output schemas, and records descriptor data. The Type conversion step imports
those schemas.

For `classify`, the declaration contributes:

```text
input schema:  s/Int
output schema: s/Keyword
```

The bridge imports `s/Int` as an Int Type and `s/Keyword` as a Keyword Type.
Those become one function method. The method is attached to the qualified symbol
for `classify`.

For `double-or-zero`, the input schema is:

```text
(s/maybe s/Int)
```

The bridge imports this as a maybe Type containing Int. That shape is already
ready for narrowing later. Annotation receives the maybe Type directly instead
of reopening the surface `s/maybe` form.

## MalliSpec Admission

MalliSpec follows the same destination: a Type dictionary entry. A form like:

```clojure
[:=> [:cat :int] :string]
```

is admitted as a function Type. `:cat` supplies the input list, and the final
entry supplies the output. Leaf Malli shapes such as `:int`, `:string`,
`:keyword`, and `:boolean` admit to ground Types.

After this import, the cast engine sees a function Type. Ordinary function casts
then use the same rules regardless of whether the method came from MalliSpec or
Schema.

## Native Declarations

Native declarations provide expected Types for functions the project did not
declare. Arithmetic, predicates, collection helpers, and common core functions
can be checked because Skeptic has native Type data for them.

`double-or-zero` depends on this. The expression `(* 2 n)` is checked as a call.
The expected argument Types come from the native declaration for multiplication.
The actual argument Types come from annotation: literal `2` and narrowed `n`.

## Type Overrides

Overrides are explicit replacements for a symbol's Type. They can be supplied by
configuration or metadata. They enter with override provenance, so they outrank
ordinary Schema declarations in source ranking.

Use an override to describe a boundary Skeptic cannot otherwise know. An
override should still be a real contract. If an override widens everything to a
dynamic Type, later phases lose the ability to report useful mismatches.

## ClojureScript Admission

`.cljs` and `.cljc` sources go through the same admission contract:
declarations become Type dictionary entries keyed by qualified symbol,
with a Provenance carrying the source language. Plumatic Schema
(`s/defn`, `s/def`, `s/defschema`) and Malli (`:malli/schema` Var-meta)
on cljs vars are both admitted.

The relevant addition is on Provenance, not the Type. Every admitted
Type carries a `:lang` field on its Provenance — `:clj`, `:cljs`, or
the merged `#{:clj :cljs}`. Findings later read that field to attach
the host language to their location.

`.cljc` is admitted twice: once with the `:clj` reader-conditional
feature active and once with `:cljs`. When both passes admit the same
Type for the same qualified symbol, the entries are merged with
`:lang` widened to `#{:clj :cljs}`. When only one pass admits a symbol,
that pass's `:lang` is kept.

The output layer reads back the same field. Identical findings from
both passes of a `.cljc` Var dedup with `lang` set to the sorted JSON
array `["clj","cljs"]`. Findings unique to one pass keep `lang` as the
single keyword string.

## Merging The Sources

The dictionary is assembled from the available sources. When more than one
source has a Type for the same symbol, source rank decides which one wins.

This merge happens before checking. A call site does not choose between Schema,
Malli, native, and override data. By the time annotation and checking ask for a
var Type, the merged dictionary has one answer.

## Admission And The Worked Example Finding

The later `classify` finding depends on admission in two places:

```text
1. the admitted output Type gives the output check its expected Type
2. schema provenance marks that expected Type as a declared boundary
```

The string branch does not fail during admission. Admission has not analyzed the
body. It only records the promise. The failure appears later when annotation's
body result is compared with this admitted promise.

## Following The Dictionary Entry Into Checking

The admitted `classify` entry is reused in two different ways. When another
expression calls `classify`, the function Type supplies expected argument Types
and an expected call output. When the checker visits the `classify` definition
itself, the same function Type supplies the expected output for the method body.

The self-checking route is the one that matters for the walkthrough finding:

```text
dictionary lookup for classify
  -> select the one-argument method
  -> read method output Keyword
  -> compare annotated body output with Keyword
```

That route explains why the declaration's output slot is load-bearing. If the
admission step produced a broad dynamic output instead of Keyword, the later
body comparison would have no precise declared boundary to enforce. If the
admission step stored the raw Schema descriptor instead of a Type, the cast
engine would not have the semantic shape it needs for source-target comparison.

For `double-or-zero`, the dictionary entry affects both local annotation and
output checking. The method input Type initializes the parameter `n` as
Maybe[Int]. The method output Type supplies Int as the result boundary. The
function passes only because both sides of that entry are consumed later: the
input side feeds narrowing, and the output side validates the joined branch
result.

## Canonicalizing Boundary Inputs

The Schema bridge normalizes boundary forms before import. Maybe, either, map,
function, and named forms are made regular enough for the bridge to convert
consistently. Recursive references are kept as references instead of being
expanded forever.

That input cleanup belongs at the boundary. Once a Type is admitted, the rest of
the pipeline should work with Type values rather than recurring over raw Schema
syntax.

## Source Pointers

- `skeptic/checking/pipeline.clj:namespace-dict` - builds the admitted namespace dictionary.
- `skeptic/analysis/bridge.clj:schema->type` - imports Schema into Type.
- `skeptic/analysis/malli_spec/bridge.clj:malli-spec->type` - imports MalliSpec into Type.
- `skeptic/typed_decls.clj:merge-type-dicts` - merges admitted source dictionaries.
- `skeptic/analysis/bridge/canonicalize.clj:canonicalize-schema` - regularizes Schema inputs.
- `skeptic/analysis/bridge/render.clj:render-type-form*` - renders Type forms for display.
- `skeptic/schema/collect/cljs.clj`, `skeptic/malli_spec/collect/cljs.clj` - cljs-side declaration collectors.
- `skeptic/cljs/analyzer_driver.clj` - stateless cljs analyzer entrypoints.
- `skeptic/provenance.clj:lang` - reads the host-language field carried on every admitted Provenance.
