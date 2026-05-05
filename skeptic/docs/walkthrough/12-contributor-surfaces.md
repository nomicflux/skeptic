# Contributor Surfaces and Pitfalls

> *Snapshot of state as of 2026-05-05.*

This is the reference spoke for contributors. It names the entry
points for adding new things, lists five common pitfalls and what
goes wrong, and gives a short decision tree for when something
unexpected happens.

## Prerequisites

Most of the prior spokes — particularly [03](03-type-domain.md),
[04](04-provenance.md), [06](06-annotation-pass.md),
[09](09-cast-dispatch.md), and
[10](10-blame-for-all-and-projection.md). This is a reference
spoke to consult while making changes; it does not introduce new
concepts.

## Where this fits

Twelfth (last) on the Contributor path. After this, the Walkthrough
is done. Diagnose-finding readers do not visit this spoke.

## Where to add a new Type kind

Adding a Type kind crosses six places:

1. **The record + predicate** in `skeptic/analysis/types.clj`. Define
   the `defrecord` with `:prov` first; extend `proto/SemanticType`
   on it; add a `<kind>-type?` predicate; add the per-record tag
   keyword for tagged-map dispatch (`semantic-tag` returns it).
2. **Constructors and helpers** in
   `skeptic/analysis/type_ops.clj` and possibly
   `skeptic/analysis/type_algebra.clj`. Any non-trivial builder
   (normalization, deduplication, simplification) lives here.
3. **The cast dispatch** in `skeptic/analysis/cast.clj` —
   `dispatch-cast`. Add a branch in the right priority slot. See
   [spoke 09](09-cast-dispatch.md#in-depth-how-to-add-a-new-dispatch-rule).
4. **The cast implementation**, possibly a new file under
   `skeptic.analysis.cast.*`. Pick the sub-namespace by what the
   rule does (branching, collection, function, map, quantified).
5. **Display** in `skeptic/analysis/bridge/render.clj`. Add a
   `render-type-form*` branch and (for JSONL) a `type->json-data*`
   branch. Otherwise the new kind renders as `<unknown>` in the
   user's findings.
6. **Tests** in `test/skeptic/analysis/`. Test both the leaf cases
   (this kind alone) and the structural composition (this kind
   inside a `UnionT`, inside a `MapT` value position, etc.).

The largest gotcha is step 5: a Type kind that the cast engine
handles correctly but the renderer doesn't will produce findings
with garbled actual/expected fields.

## Where to add a new dispatch rule

Adding a dispatch rule to the existing kinds is a smaller change.
Identical pointer set as the in-depth section in
[spoke 09](09-cast-dispatch.md#in-depth-how-to-add-a-new-dispatch-rule);
condensed here as a checklist.

- Add the branch to `dispatch-cast` in
  `skeptic/analysis/cast.clj`. Priority order: bottom-source first;
  exact-match second; quantified above abstract above target-dyn
  above unions above maybe above wrappers above structural
  collections above leaf.
- Implement the rule in the right `cast/*.clj` sub-namespace.
- Add cast-result metadata in `cast/support.clj` if needed.
- Update `inconsistence/path.clj` if the rule introduces a new
  visible path segment.
- Tests: leaf, structural composition.

## Where to add a new admission source

The native source's pattern is the simplest example to copy.

- One **collector namespace** that walks the input data and
  produces admission entries (`<source>-collect.clj`-style).
- One **bridge namespace** that converts each entry to a Type with
  the appropriate provenance source (`analysis/<source>/bridge.clj`-style).
- A **plug-in point** in `skeptic.typed-decls/merge-type-dicts` (or
  the namespace-dict assembly in `pipeline.clj`) that calls the new
  collector and bridge.
- A **rank choice** in `provenance.clj`'s source-rank map. Where
  does this source sit between `:type-override` (0) and
  `:inferred` (4)?

A new admission source is rare but not impossible — Malli was added
this way, alongside Plumatic, when Malli support became experimental.

## Pitfalls

Five concrete failure modes contributors hit. Each names the cause
and the fix; each has a `path/file.clj:fn-name` pointer to the
corner of the codebase where the cause lives.

### Pitfall 1 — "I added a Type, but findings show `Dyn` for it."

**Likely cause.** The new Type kind has no annotator branch in
`annotate-dispatch`, so it falls through to the generic `at/Dyn`
annotation when constructed downstream of an analyzer node.

**Fix.** Locate the construction site in the annotation pass and
emit the new Type from there. The annotator that should produce the
new Type needs to be aware of it; the cast engine seeing it doesn't
help if annotation never produces it. Pointer:
`skeptic/analysis/annotate.clj:annotate-dispatch`.

### Pitfall 2 — "My new dispatch rule never fires."

**Likely cause.** The `cond` priority in `dispatch-cast` puts a
higher branch (`:exact`, `:target-dyn`, structural collection)
first, which short-circuits your rule.

**Fix.** Move the new branch to the right priority slot. Quantified
rules come above abstract rules, abstract above target-dyn, and so
on down the ladder. The order is load-bearing — see
[spoke 09](09-cast-dispatch.md#the-dispatch-ladder). Pointer:
`skeptic/analysis/cast.clj:dispatch-cast`.

### Pitfall 3 — "My finding has the wrong `:source`."

**Likely cause.** A combinator built a composite Type from
constituents and used the wrong anchor provenance (or none). The
composite ended up with a different `:source` from what the caller
intended.

**Fix.** Pass an explicit anchor prov to the combinator. The
container owns its identity; do not derive it from the items. The
combinators are listed in [spoke 04](04-provenance.md#combinator-anchor-provenance)
and require an anchor first parameter:
`av/join`, `amo/merge-map-types`, `amoa/merge-types`,
`coll/concat-output-type`. Pointers:
`skeptic/analysis/value.clj:join`,
`skeptic/analysis/map_ops.clj:merge-map-types`.

### Pitfall 4 — "My Type passes equality with `=` but fails with `at/type=?`."

**Likely cause.** Almost the opposite — `=` fails but `type=?`
passes, because `=` on defrecords compares `:prov` and `type=?`
doesn't.

**Fix.** In tests, use `at/type=?`. In production code, do not rely
on `=` for shape comparison. Equality of Types means *shape
equality*, not equal `:prov`. The provenance is metadata about
where the Type came from; two Types of identical shape but
different `:prov` should compare equal for shape-based decisions.
Pointer: `skeptic/analysis/types.clj:type=?`.

### Pitfall 5 — "I changed an existing function and tests pass but the `lein skeptic` self-check now fails."

**Likely cause.** Schema annotations on the changed function have
drifted from its body, or downstream consumers' inferred types now
disagree with declared. Tests don't catch type-level mismatches —
that's why Skeptic exists.

**Fix.** Read the finding (using the
[Diagnose-finding reading path](README.md#diagnose-finding-path-60-min))
and *tighten* either the producer or the consumer — never widen
with `s/Any` or `(s/maybe …)` to silence the finding. Type-checker
findings are correctness mismatches; "tests pass" is not a reason
to soften a schema.

## Where to look first when something looks wrong

A short decision tree for unexpected behaviour:

- **Finding's `:source` is `:schema` and you didn't expect that.**
  → Check `prov/merge-provenances` rank. The cast root merged a
  source-side and target-side provenance; the lower rank wins. If
  the target was schema-declared, that's why.
- **No finding at all when you expected one.**
  → Check whether the structural rule called `aggregate-children`
  with all children. A missed child means a missed leaf. Also check
  whether the failing leaf's `actual_type` is `Dyn` — if so,
  upstream inference produced a `Dyn` and the cast couldn't tell.
- **Cast result has `:residual-dynamic` rule.**
  → A placeholder didn't resolve. The schema is recursively
  self-referential and the cycle wasn't broken; check
  `bridge.localize/localize-value` and the `seen-vars` set.
- **Type is `Dyn` when you expected something concrete.**
  → Check the annotator for the relevant `:op`. The annotator
  produced `Dyn` because the underlying analyzer node didn't carry
  enough information for inference, or because the sub-namespace
  annotator didn't recognize the form's shape.
- **Self-check fails after a refactor that "just renamed things".**
  → Provenance qualified-syms drifted; the dict's
  `:qualified-sym` no longer matches the renamed symbols. The dict
  itself may be stale; rebuild from a clean state.

## Marquee functions

| Function              | File                              | Role                                         |
|-----------------------|-----------------------------------|----------------------------------------------|
| `dispatch-cast`       | `skeptic/analysis/cast.clj`        | The rule-priority site.                       |
| `annotate-dispatch`   | `skeptic/analysis/annotate.clj`    | The `:op`-priority site.                      |
| `merge-type-dicts`    | `skeptic/typed_decls.clj`           | The admission-rank site.                      |
| `merge-provenances`   | `skeptic/provenance.clj`            | The source-rank site.                         |

## Worked example here

The pitfalls reference `classify` and `double-or-zero` only when
their shapes happen to illustrate a pitfall. For example, pitfall 3
mentions `classify`'s output as a single-source case where anchor
provenance doesn't matter — the declared `GroundT Keyword` carries
`:schema` and no combinator builds a composite, so anchor provenance
is moot. To exercise pitfall 3 in earnest, the example would need a
combined inferred + declared union, which the worked example
doesn't naturally produce.

## Where to next

- **The Walkthrough is complete.** Welcome back.
- **Return:** [Hub](README.md)
