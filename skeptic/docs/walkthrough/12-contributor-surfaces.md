# Contributor Surfaces

> *Snapshot of state as of 2026-05-06.*

The earlier spokes describe the data path. This spoke turns that path into
change locations. A contributor should choose the edit point by asking which
boundary is wrong: admission, annotation, narrowing, casting, projection, or
output.

## Adding A Type Kind

A new Type kind starts with the semantic value. Add the record, constructor,
predicate, and semantic equality behavior. Then follow the Type through the
pipeline:

```text
Can admission build it?
Can annotation produce it?
Can normalization canonicalize it?
Can cast dispatch recognize it?
Can output render it?
```

If the Type can only appear through admission, the bridge may be the only
producer. If it can be inferred from source expressions, annotation needs a
producer as well. If it can be nested in a union or map, semantic equality and
normalization need to preserve its shape.

The worked example gives the smallest contrast. Keyword and maybe Types already
have all of those surfaces. A new Type kind should be able to move through the
same phases without becoming dynamic or disappearing inside a composite.

## Adding A Cast Rule

A cast rule belongs in the dispatcher at the point where its source and target
pair becomes more specific than later rules. The slot is part of behavior.

For example, source-union handling must run before leaf compatibility. Otherwise
`classify` would compare the whole union as if it were a leaf and would lose the
per-member check. Quantified and sealed dynamic rules must run before broad
dynamic acceptance, because their job is to protect an abstraction boundary.

After choosing the dispatcher slot, implement the rule so it returns ordinary
cast results. Structural rules should produce child results. Failure results
should carry a rule, reason, polarity, source Type, target Type, and any path
segments needed by diagnostics.

## Adding Annotation Behavior

Annotation changes belong where the analyzer node shape is understood. A new
literal-like expression is different from a new call behavior, and both differ
from a branch behavior.

The annotation path should answer:

```text
Which child nodes are annotated first?
What Type does this node compute from child Types?
Does this node create or preserve origin data?
Does this node affect call metadata?
```

For `double-or-zero`, the key annotation behavior is branch-local refinement. A
change that lost the origin of `n` would make the then branch see maybe Int
instead of Int, and the multiplication check would become less precise.

## Adding An Admission Source

An admission source needs a collector, a bridge, provenance, and merge behavior.
The collector finds declarations. The bridge imports the source language into
Type. Provenance records where the Type came from. Merge behavior decides how
the source interacts with existing declarations.

The new source stops at the admission boundary. After admission, annotation and
checking see Type values. That is the same boundary Schema and MalliSpec use.

## Fixing A Finding In The Wrong Place

When a finding points to the wrong boundary, inspect the report construction
path before changing cast compatibility. An output problem comes from the
definition-output check. An input problem comes from call argument checking.
Visible path segments come from failed diagnostics with structural paths.

For the `classify` class of output report, the production facts are output report
kind and source-union diagnostic evidence. Adding a display-only path to make the
message look nicer would not fix the underlying boundary if the report was built
from the wrong check.

## Fixing A Missing Finding

A missing finding usually means one of three things happened earlier than
output:

```text
the expected Type was not admitted
the actual Type became too broad during annotation
the cast rule treated a failing child as successful
```

Trace in that order. For a `classify`-like bug, first confirm the declared
Keyword output is in the dictionary. Then confirm the body Type still contains
the string branch. Then confirm the source-union cast fails when one member
fails.

## Fixing A Vague Finding

A vague finding usually means the Type reaching output is dynamic or the
renderer folded away structure. Trace the actual Type before changing the output
message. If annotation already produced dynamic, output cannot render the
specific branch that was lost.

For `double-or-zero`, a vague nullable warning would point toward narrowing: did
the `(some? n)` assumption attach to the local origin, and did the branch-local
environment refine `n` before the multiplication was annotated?

## Production Path Beats Shortcut Proof

Helper-level tests are useful, but a checker bug that spans admission,
annotation, checking, and report construction must be proven through the
production path. Namespace checking exercises source forms, declaration
collection, analyzer output, project state, cast checks, and reports together.

If a helper says a Type comparison works but a namespace report is still wrong,
trust the namespace path as the failing path. Then locate which handoff changed
between the helper and production.

## Source Pointers

- `skeptic/analysis/cast.clj:dispatch-cast` - cast rule priority.
- `skeptic/analysis/annotate.clj:annotate-dispatch` - annotation routing.
- `skeptic/typed_decls.clj:merge-type-dicts` - admission merge behavior.
- `skeptic/provenance.clj:merge-provenances` - provenance source ranking.
- `skeptic/analysis/types.clj:type=?` - semantic Type equality.
- `skeptic/checking/pipeline.clj:check-namespace` - production namespace path.
