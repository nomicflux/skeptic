# Contributor Surfaces

The reader has now walked the checker from source to output. The final question
is operational: when changing Skeptic, where should a contributor start?

> **Snapshot:** state of Skeptic as of 2026-05-06.

## Prerequisites

Most prior spokes. This is a practical map for readers who already understand
the pipeline. If a term in this spoke feels unfamiliar, return through the hub's
Contributor path.

## Where this fits

Twelfth and final on the Contributor path. Diagnose-finding readers usually do
not need this spoke unless the diagnosis turns into an implementation task.

## Extension Points

*Figure: extension points overlaid on the pipeline.*

```mermaid
flowchart LR
  admission[Admission source] --> annotation[Annotation rule]
  annotation --> cast[Cast dispatch rule]
  cast --> projection[Blame and path projection]
  projection --> output[Display and JSON rendering]
```

The diagram is intentionally small. Most changes touch one of these points, then
one or two adjacent points for display, tests, or composition.

The reader should start from the observable failure, not from a favorite
namespace. A missing declaration is an admission problem. A wrong inferred Type
is usually annotation or narrowing. A wrong compatibility decision is cast
dispatch. An unclear message is projection or output.

## Adding A Type Kind

Start in the Type domain. Add the record and predicate in the Type namespace,
then decide which downstream phases can encounter it. If annotation can produce
the Type, add the annotator path. If admission can produce it, add the bridge
path. If it can be checked against other Types, add cast dispatch and display.

The reader should not begin in output. Output can only display distinctions that
survived admission, annotation, and casting.

Checklist:

| Question | Likely edit area |
|---|---|
| How is the Type constructed? | `analysis/types.clj` and builders. |
| How is the Type normalized? | Type operations. |
| Can source code infer it? | Annotation. |
| Can declarations admit it? | Bridge/admission. |
| Can it be compared? | Cast dispatch and sub-rule. |
| Can users read it? | Render and output. |

## Adding A Dispatch Rule

Start with the source/target pair. Ask where it belongs in the dispatch ladder:
before unions, after maybe, as a structural collection rule, or as a leaf rule.
Then implement the rule and give any child casts visible path segments. Tests
should cover the direct pair and at least one structural composition, because
most real failures happen inside a function, union, map, or collection.

The priority choice is part of behavior. A rule placed too low may never run
because a union, maybe, wrapper, or leaf case already handled the pair. A rule
placed too high may bypass the structure that would have produced useful child
paths.

## Adding An Admission Source

An admission source needs a collector, a bridge into the Type domain, provenance
for admitted Types, and a merge point with existing declarations. Once the value
is admitted, later phases should see the same Type language they already know.

That is the main design check for a new source: does it enrich the boundary
without forcing annotation or casting to learn a new external syntax?

The native descriptor path is the simplest model: collect or construct a known
declaration, attach provenance, and merge it into the declaration set. More
complex external syntaxes can still follow that shape.

## Adding Annotation Behavior

Start from the analyzer `:op` that produces the wrong inferred Type. If the node
is branchy, check whether the missing piece belongs in ordinary annotation,
closed-sum reasoning, or narrowing. If the node is an invocation, check whether
the callee's admitted Type is already precise enough before changing invoke
annotation.

For branch bugs, do not jump straight to cast. Cast can only consume the Types it
is given. If the local environment was never refined, the cast engine is right to
see the wider Type.

## Adding Output Behavior

Output should be the last step. Add a path renderer if users need a new
structural location. Add Type rendering only after the Type exists and can reach
the printer. Add JSON fields only for information already present in the finding
or report summary.

This preserves information flow. Shared program state should remain rich; output
surfaces should derive the reduced human or JSON shape at the boundary.

## A Change Walkthrough

Imagine adding support for a new collection-like Type. The contributor path is
not "add a renderer and see what happens." Start by defining the Type shape. Add
normalization if the Type has equivalent surface forms. Decide whether admission,
annotation, or both can produce it. Add cast behavior at the right priority.
Then add path and display support if failures inside the Type need user-facing
locations.

The worked example gives a smaller version of the same discipline. The string
branch in `classify` is visible at output only because admission preserved the
Keyword target, annotation preserved the string actual, cast dispatch preserved
the failing child, and projection preserved the return path. A new feature must
preserve that same chain for its own distinctions.

## Verification Shape For Changes

For production behavior, prefer a proof that exercises the real pipeline:
declaration intake when declarations matter, annotation when source shape
matters, narrowing when branch facts matter, and cast dispatch when compatibility
matters. Isolated tests are still useful, but they should not be mistaken for a
proof that the user-facing run will report the intended finding.

## Final Mental Model

Skeptic is easiest to change when the contributor preserves the pipeline shape:
external declarations become Types; source becomes annotated Types; narrowed
source Types are cast against declared target Types; failed cast leaves become
findings; output renders those findings. A change that skips one of those
handoffs may look locally plausible while failing in a real run.

The walkthrough's purpose is not to replace source reading. It gives the reader
the map needed to know which source to read first and what question to ask there.

## Pitfalls

**A new Type shows as dynamic.** The Type may have been introduced at one layer
without a route through annotation or admission. Find where the value should be
constructed, then trace whether it reaches checking.

**A cast rule never fires.** A higher-priority dispatch branch may be catching
the pair first. Re-read the ladder in [Cast Dispatch](09-cast-dispatch.md) and
place the rule where the reader-visible behavior belongs.

**A finding points at the wrong source.** The mismatch may be correct while the
provenance is not. Trace which admitted or inferred Type became the target and
which Type became the actual.

**Equality checks disagree.** Structural Type equality and ordinary Clojure
record equality answer different questions when provenance is present. Use the
semantic equality path when asking whether Types mean the same thing.

**A local test does not narrow.** The test may not produce an assumption, or the
value being tested may lack a stable origin. Trace test -> assumption -> origin
-> refined Type before changing cast code.

## Where To Look First

| Symptom | First place to inspect |
|---|---|
| Missing declaration | Admission collector and bridge. |
| Wrong inferred expression Type | Annotation for that AST `:op`. |
| Branch did not narrow | Origin and narrowing assumption path. |
| Rule surprise | Cast dispatch ladder. |
| Unhelpful path text | Blame projection and path rendering. |
| Tooling needs a field | Porcelain output after the report contains it. |

## Worked Example Here

`classify` is a good debugging seed for output-cast changes: it has one declared
expectation, one inferred mismatch, and one visible return path. `double-or-zero`
is a good seed for narrowing changes: it should stay clean because `(some? n)`
refines the maybe-typed local before multiplication.

## Source Pointers

- `skeptic/analysis/cast.clj:dispatch-cast` - cast rule priority.
- `skeptic/analysis/annotate.clj:annotate-dispatch` - annotation rule priority.
- `skeptic/typed_decls.clj:merge-type-dicts` - admission merge point.
- `skeptic/provenance.clj:merge-provenances` - provenance source ranking.
- `skeptic/inconsistence/path.clj:render-visible-path` - user-facing cast paths.

## Glossary Terms Introduced

- Marquee function
- Extension point
- Contributor surface

## Where To Next

- **Continue (Contributor path):** You have completed the Walkthrough.
- **Return:** [Hub](README.md)
