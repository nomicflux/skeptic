# Pipeline Tour

> *Snapshot of state as of 2026-05-05.*

A run of `lein skeptic` walks every namespace through the same fixed
sequence of phases. This spoke names the phases and traces the worked
example from on-disk source to the final finding, end to end.

## Prerequisites

Working Clojure (`s/defn`, project layout). Familiarity with the idea
that a type-checker has phases. No Skeptic-specific vocabulary required;
this spoke introduces the rest. If any of these are unfamiliar, the
[hub README's reading paths](README.md#reading-paths) point to the right
earlier spoke.

## Where this fits

First spoke on every reading path. After this, Contributors continue to
[Three Domains (02)](02-three-domains.md); Gist readers can jump
straight to [Cast Dispatch (09)](09-cast-dispatch.md) once they have the
hub and this spoke. Diagnose-finding readers also start here, for the
phase names.

## A run end-to-end

Skeptic runs in seven phases. Here is the worked example, run on disk,
end to end.

The user runs `lein skeptic` in a project containing
`skeptic.walkthrough.example`. The plugin discovers the namespace from
the project's source paths, hands it to the checker, and the checker
loads it under a profile that disables Plumatic's runtime validation
(*phase 1, discovery, covered below*).

Once loaded, the checker collects every var's `:schema` metadata,
every var's `:malli/schema` metadata, and merges those with built-in
native function descriptors and any `:type-overrides` from
`.skeptic/config.edn`. Each annotated symbol becomes a Type in a
single declaration dictionary keyed by qualified symbol — `classify`
gets a `FunT` whose method has input `GroundT Int` and output
`GroundT Keyword`; `double-or-zero` gets a `FunT` whose method has
input `MaybeT[GroundT Int]` and output `GroundT Int`. (*Phase 2,
admission, covered in [spoke 05](05-admission-paths.md).*)

The checker then walks every top-level form through `tools.analyzer`,
producing an analyzer AST, and walks the AST attaching a Type to every
node. Inside `classify`'s body the `cond` desugars to a chain of `if`s;
each leaf gets a Type (`ValueT(:zero)`, `ValueT(:even)`, `GroundT Str`)
and the chain is joined into a `UnionT`. Inside `double-or-zero` the
`(some? n)` test produces an assumption that, in the then-branch,
narrows `n` from `MaybeT[GroundT Int]` to `GroundT Int`. (*Phase 3,
annotation, covered in [spoke 06](06-annotation-pass.md), with
narrowing in [spoke 08](08-narrowing-and-origins.md).*)

For each function the checker then casts inferred types against
declared types — call sites against declared inputs, function bodies
against declared outputs. `classify`'s body is a `UnionT` whose `Str`
member fails the cast against `GroundT Keyword`. `double-or-zero`'s
body is `GroundT Int` (joined from the two arms) and fits the
declared `GroundT Int`. (*Phase 4, checking, covered in
[spoke 09](09-cast-dispatch.md).*)

The failed cast produces a cast-result tree. The checker walks the tree,
collects the failing leaves, picks a primary diagnostic, computes a
visible path, and packages the whole thing into a finding map.
(*Phase 5, blame projection, covered in
[spoke 10](10-blame-for-all-and-projection.md).*)

The finding is then printed: by default as ANSI-coloured text grouped
per namespace; with `-p`, as a JSON object on its own line. A
namespace-error-summary and a run-summary line close the run.
(*Phase 6, output rendering, covered in
[spoke 11](11-user-facing-surfaces.md).*)

## The seven phases

Each phase has one or two marquee functions, listed at the bottom of
this spoke. The descriptions below are deliberately shallow — every
phase has its own spoke.

### Phase 1: namespace discovery

The Leiningen plugin parses CLI flags and selects a profile, then hands
control to `skeptic.core` which discovers namespaces from the project's
source paths. The discovery step records non-blocking namespace load
failures as `ns-discovery-warning`s for later output but otherwise
produces a flat sequence of namespaces ready for checking.

### Phase 2: declaration admission

Per-namespace, every declared schema (Plumatic), every Malli
`:malli/schema`, every native descriptor, and every `:type-overrides`
entry becomes a Type in a single declaration dictionary. The dictionary
is bare Types only — no `:typings`, `:output-type`, `:arglists`, or
similar wrappers. A parallel provenance map records each entry's
declaration-level origin. The marquee function is `namespace-dict` in
`skeptic/checking/pipeline.clj`.

### Phase 3: source analysis & annotation

For each top-level form, the checker calls
`clojure.tools.analyzer.jvm/analyze`, then walks the AST with the
declaration dict in scope, attaching a Type to every node. The walk is
*first-order*: it never invents a quantified Type. Quantified Types
enter only through admission (or as user-supplied `:skeptic/type`
overrides). Spoke 06 covers the dispatch on `:op`; spokes 07 and 08
cover the refinements layered on top.

### Phase 4: checking (cast)

For each annotated form, the checker compares inferred Types against
declared Types — function-body output against declared output, each
call's argument types against the callee's declared input types. The
unit of work is a *cast*: a directional check returning a
`CastResult` with `:ok?`, a rule, blame side and polarity, source and
target types, child results, and a structural path. Failed casts
become inputs to phase 5.

### Phase 5: blame projection

The cast-result tree is walked; failing leaves are collected and
ranked; a primary diagnostic is selected; the structural path is
projected to a visible string (`"return value"`, `"argument 1 → field
:foo"`); the resulting record is packaged with the namespace, source
location, blame side, blame polarity, rule, actual and expected Types,
and any cast-side error messages. The output is a finding-shaped map
ready for the printers.

### Phase 6: output rendering

Two output modes share the same lifecycle map of phase callbacks
(`:run-start`, `:discovery-warn`, `:ns-start`, `:finding`,
`:form-debug`, `:ns-end`, `:run-end`). The default `output.text/printer`
emits ANSI-coloured human-readable blocks. The porcelain
`output.porcelain/printer` (selected by `-p`) emits one JSON record
per event onto stdout. Both honour `--explain-full`, `-c / --show-context`,
and `-v / --verbose`. The exit code is `0` if no findings were emitted,
`1` otherwise.

### Phase 7: namespace summary

After all namespaces have been checked, both printers emit a
per-namespace inconsistency count (worst-offending first) and a run
summary. In porcelain mode the summary is a single
`namespace-error-summary` record followed by a `run-summary` record,
the last line of the run.

## Where the worked example shows up

| Phase                          | What happens to `classify`                                                                       | What happens to `double-or-zero`                                                       |
|--------------------------------|--------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| 1: discovery                   | namespace loaded                                                                                  | namespace loaded                                                                       |
| 2: admission                   | dict entry: `FunT[FnMethodT[GroundT Int → GroundT Keyword]]`, prov `:schema`                     | dict entry: `FunT[FnMethodT[MaybeT[GroundT Int] → GroundT Int]]`, prov `:schema`       |
| 3: annotation                  | `cond` body is annotated; each arm gets a leaf Type; the body's joined Type is a `UnionT` of two `ValueT`s and one `GroundT Str` | `(if (some? n) ...)` is annotated; the then-branch narrows `n` to `GroundT Int`        |
| 4: checking                    | output cast: `UnionT` against `GroundT Keyword` — fails on the `GroundT Str` member               | input cast at `(* 2 n)`: `GroundT Int` against `GroundT Int` — succeeds                |
| 5: blame projection            | failing leaf identified: `GroundT Str` vs. `GroundT Keyword`, blame side `:term`, path `"return value"` | nothing to project — no failed cast                                                    |
| 6: output rendering            | text-mode: a finding block under `Namespace: skeptic.walkthrough.example`; JSONL: one `finding` record | nothing emitted                                                                        |

This table is the first time the worked example crosses every phase. It
is referenced from spokes 05, 06, 09, and 10 when those spokes drill
into a specific phase.

## How findings are emitted

Findings are emitted incrementally as each namespace is checked, not
batched at the end of the run. A namespace can produce zero, one, or
many findings; an exception inside a namespace produces an
`exception` record (in porcelain) or an exception-style block
(in text). The `run-summary` record (porcelain) or
`No inconsistencies found` line (text) is always last and indicates
whether the run found any inconsistencies. The exit code follows the
same logic: `0` if no inconsistencies, `1` otherwise.

For the rendering details — text format, JSONL field shapes, ANSI
colours, suppression flags — see [spoke 11](11-user-facing-surfaces.md).

### In-depth: project state vs. per-namespace

***Skip if reading the Gist path.***

Skeptic builds a project-wide state — `project-state` in
`skeptic/checking/pipeline.clj` — once per run, before any namespace is
checked. The state holds the merged declaration dict across all
namespaces, the merged provenance map, and accessor summaries for every
recognized map-keyed function, all derived in one pass. Each namespace
is then checked using this single shared dict.

Why one shared dict instead of per-namespace dicts? Two reasons.
First, vars resolved across namespace boundaries (Skeptic recognizing
`(other-ns/some-fn …)` as a call to a typed function) need that
function's Type already admitted. Second, `ConditionalT` discriminator
enrichment (the second pass that fills the `[pred type discriminator]`
triple from accessor summaries — see [spoke 03](03-type-domain.md))
draws on accessor summaries from any namespace. A per-namespace dict
would force two passes over the project even before checking begins.

A consequence: the order in which namespaces are admitted matters for
provenance only when two namespaces declare the same qualified symbol,
which is rare in real projects.

## Marquee functions

| Function              | File                                | Role                                                          |
|-----------------------|-------------------------------------|---------------------------------------------------------------|
| `check-namespace`     | `skeptic/checking/pipeline.clj`     | Per-namespace entry; runs every later phase.                  |
| `project-state`       | `skeptic/checking/pipeline.clj`     | Builds the project-wide dict and accessor summaries once.     |
| `namespace-dict`      | `skeptic/checking/pipeline.clj`     | Phase 2 (admission): merges schema/malli/native results.      |
| `check-resolved-form` | `skeptic/checking/pipeline.clj`     | Phase 4 (checking): runs the cast against declared types.    |
| `match-s-exprs`       | `skeptic/checking/pipeline.clj`     | Builds input cast results for one call site.                  |

## Where to next

- **Continue (Contributor path):** [Three Domains (02)](02-three-domains.md)
- **Continue (Gist path):** [Three Domains (02)](02-three-domains.md)
- **Diagnose-finding path:** continue to [Cast Dispatch (09)](09-cast-dispatch.md)
- **Return:** [Hub](README.md)
