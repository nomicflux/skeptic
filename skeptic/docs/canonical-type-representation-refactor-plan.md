# Canonical Type Hot Path Refactor Plan

## Goal

Reduce the remaining type-normalization hotspot by finishing the boundary split that the codebase has already started:

- semantic types stay canonical and internal
- Schema-domain and other foreign values are handled only at explicit boundary entrypoints
- hot type-only code stops paying recursive `localize-value` cost on every `normalize-type` call

This is not a greenfield redesign. The current code already completed most of the earlier schema-boundary cleanup, so this plan targets the remaining mixed-value path precisely.

## Current Derivation

The current checking path is:

1. `src/skeptic/core.clj` calls `skeptic.checking.pipeline/check-ns`
2. `src/skeptic/checking/pipeline.clj` calls `analyze-source-exprs`
3. `analyze-source-exprs` calls `skeptic.analysis.annotate/annotate-form-loop`
4. `annotate-form-loop` calls `annotate-ast`
5. `annotate-ast` calls `annotate-node`
6. hot annotation branches call `skeptic.analysis.type-ops/normalize-type`

Representative hot callers:

- `src/skeptic/analysis/annotate.clj`
- `src/skeptic/analysis/calls.clj`
- `src/skeptic/analysis/origin.clj`
- `src/skeptic/analysis/type_algebra.clj`
- `src/skeptic/analysis/value.clj`
- `src/skeptic/analysis/bridge/render.clj`
- `src/skeptic/analysis/cast/*.clj`

The key remaining cost is in:

- `src/skeptic/analysis/type_ops.clj`
- `src/skeptic/analysis/bridge/localize.clj`

Today `normalize-type` immediately calls `localize-value`, which means type-only callers still pay a full mixed-domain coercion pass before the actual normalization logic runs.

## What Changed Since The Earlier Plan

The earlier plan is no longer an accurate description of the codebase.

The important current facts are:

- semantic types already have a canonical internal representation in `src/skeptic/analysis/types.clj`
- semantic type predicates are already canonical-map based
- the old `types.clj` class-name hotspot is mostly gone
- `same-class-name?` is now only used for the `clojure.lang.Var$Unbound` case in `bridge/localize.clj`
- schema-facing logic is already concentrated in:
  - `src/skeptic/analysis/bridge.clj`
  - `src/skeptic/analysis/bridge/canonicalize.clj`
  - `src/skeptic/analysis/bridge/localize.clj`
  - `src/skeptic/analysis/schema/*.clj`
- there is no current `src/skeptic/analysis/schema.clj`

So the remaining problem is narrower:

- type-only code still routes through the boundary-localization machinery too often
- canonical semantic values are still recursively rebuilt in `localize-value*`
- some type-only helpers still do extra localize-then-normalize work

## Target State

The target state is:

- boundary entrypoints accept raw Schema-domain values, Vars, and other foreign values
- boundary entrypoints may call `localize-value` and schema canonicalization
- internal type-only code accepts canonical semantic types or native literal/container values only
- canonical semantic types fast-path through normalization without re-localization
- map/type helpers do not silently reopen the mixed Schema boundary on hot paths

## Type States

### Boundary inputs

These are allowed only at explicit boundaries:

- raw Plumatic Schema values
- custom schema-base values from `schema_base.clj`
- Vars and unbound Vars
- foreign values that still need localization

### Canonical internal semantic values

These are the source of truth inside analysis and checking:

- maps tagged by `:skeptic.analysis.types/semantic-type`
- constructed by helpers in `src/skeptic/analysis/types.clj`

### Derived output values

These are not canonical shared state:

- rendered type forms
- report payloads
- display strings

## Core Design Rule

`normalize-type` must stop meaning “run the entire mixed-value boundary adapter.”

Instead it should mean:

- if given a canonical semantic type, return canonical semantic type
- if given a native type-like value that the type-only layer intentionally allows, convert it directly
- if given a true foreign boundary value, reject it or require the caller to use an explicit boundary adapter

The boundary adapter remains available, but it should be named and used as a boundary step, not as the default hot-path prelude for all internal type work.

## Current Files And Roles

## Type-only hot-path files

These should remain or become canonical-semantic-only on their hot paths:

- `src/skeptic/analysis/types.clj`
- `src/skeptic/analysis/type_ops.clj`
- `src/skeptic/analysis/type_algebra.clj`
- `src/skeptic/analysis/value.clj`
- `src/skeptic/analysis/calls.clj`
- `src/skeptic/analysis/origin.clj`
- `src/skeptic/analysis/annotate.clj`
- `src/skeptic/analysis/map_ops.clj`
- `src/skeptic/analysis/cast.clj`
- `src/skeptic/analysis/cast/support.clj`
- `src/skeptic/analysis/cast/map.clj`
- `src/skeptic/checking/pipeline.clj`
- `src/skeptic/inconsistence/report.clj`
- `src/skeptic/analysis/bridge/render.clj`

## Boundary files

These are allowed to handle Schema-domain or foreign values:

- `src/skeptic/analysis/bridge.clj`
- `src/skeptic/analysis/bridge/canonicalize.clj`
- `src/skeptic/analysis/bridge/localize.clj`
- `src/skeptic/analysis/bridge/algebra.clj`
- `src/skeptic/analysis/schema/*.clj`
- `src/skeptic/typed_decls.clj`
- `src/skeptic/schema/collect.clj`

## Main Refactor

## Step 1: Add explicit semantic tag accessors in `types.clj`

### Purpose

Make semantic-type classification direct instead of repeatedly walking the full predicate ladder.

### Changes

- Add a helper that extracts the semantic tag from canonical semantic-type maps
- Add a helper that answers whether a value is any semantic type by tag presence
- Rework `semantic-type-value?` to use the direct tag helper instead of chaining all predicates

### Notes

This is not the whole performance fix, but it removes unnecessary predicate fan-out from the canonical fast path and makes later dispatch simpler.

### Owner

- `src/skeptic/analysis/types.clj`

## Step 2: Split `localize-value` into explicit boundary families

### Purpose

Keep localization as the mixed-domain boundary adapter, but stop treating canonical semantic types as though they still need boundary conversion.

### Changes

- Refactor `localize-value*` in `src/skeptic/analysis/bridge/localize.clj`
- Separate the logic into explicit families:
  - unbound Var handling
  - Var traversal / cycle protection
  - schema-base custom wrappers
  - canonical semantic types
  - raw collections
  - passthrough scalars
- Add a canonical semantic fast path that avoids repeated open-coded semantic predicate dispatch
- Avoid rebuilding semantic values when no child value changes, if that can be done without changing semantics or adding excessive complexity

### Notes

The file should remain boundary-facing. The goal is not to spread localization logic elsewhere.

### Owner

- `src/skeptic/analysis/bridge/localize.clj`

## Step 3: Split boundary coercion from type normalization in `type_ops.clj`

### Purpose

Make the hot type-normalization API explicit about what it accepts.

### Changes

- Introduce a boundary-only helper, for example:
  - `coerce-boundary-type`
  - `boundary->type`
  - `normalize-boundary-type`
- Keep or redefine `normalize-type` as the canonical hot-path API
- Remove unconditional `abl/localize-value` from `normalize-type`
- Preserve support for these internal allowed inputs:
  - canonical semantic types
  - `nil`
  - schema literals that intentionally map to exact semantic values
  - optional-key wrappers if still required internally
  - native containers whose elements are already type-like inputs intended for internal normalization

### Contract after this step

- boundary callers use the explicit boundary coercion helper
- type-only callers use `normalize-type`

### Owner

- `src/skeptic/analysis/type_ops.clj`

## Step 4: Rewire boundary entrypoints to use the boundary coercion helper

### Purpose

Keep mixed-domain coercion where it belongs instead of relying on the old implicit behavior.

### Primary entrypoints to review

- `src/skeptic/analysis/bridge.clj`
- `src/skeptic/analysis/bridge/canonicalize.clj`
- `src/skeptic/typed_decls.clj`

### Expected changes

- `schema->type` and `import-schema-type` keep raw Schema-domain acceptance
- canonicalization helpers keep raw Schema-domain acceptance
- type-only callers no longer rely on these functions as generic mixed coercers

### Owner

- `src/skeptic/analysis/bridge.clj`
- `src/skeptic/analysis/bridge/canonicalize.clj`
- `src/skeptic/typed_decls.clj`

## Step 5: Remove redundant boundary work from `map_ops.clj`

### Purpose

`map_ops.clj` still does localize-then-normalize on type-side paths through `as-type` and key-query helpers.

### Changes

- Narrow `as-type` to canonical or internal type-like normalization only
- Move any true foreign-value coercion to explicit boundary helper calls at the edge
- Recheck:
  - `finite-exact-key-values`
  - `domain-key-query`
  - `map-key-query`
  - `descriptor-entry`
  - `map-entry-descriptor`
  - key-domain overlap and coverage helpers

### Notes

This file is likely a secondary hotspot because it repeatedly normalizes map-entry keys during annotation and cast logic.

### Owner

- `src/skeptic/analysis/map_ops.clj`

## Step 6: Audit remaining hot-path callers of `normalize-type`

### Purpose

Ensure the new API split is actually respected.

### Files to inspect

- `src/skeptic/analysis/annotate.clj`
- `src/skeptic/analysis/calls.clj`
- `src/skeptic/analysis/origin.clj`
- `src/skeptic/analysis/type_algebra.clj`
- `src/skeptic/analysis/value.clj`
- `src/skeptic/analysis/cast.clj`
- `src/skeptic/analysis/cast/support.clj`
- `src/skeptic/analysis/cast/map.clj`
- `src/skeptic/analysis/bridge/render.clj`
- `src/skeptic/checking/pipeline.clj`
- `src/skeptic/inconsistence/report.clj`

### What to look for

- callers that still assume `normalize-type` accepts raw Schema-domain values
- callers that repeatedly normalize values already known to be canonical
- callers that can normalize once earlier and pass canonical types downstream

### Owner

- integrator pass across the listed files

## Step 7: Keep schema-only helpers closed over the boundary

### Purpose

Avoid undoing the existing schema-boundary cleanup while optimizing the hotspot.

### Checks

- do not reopen mixed schema/type behavior in `schema->type`
- do not make type-only files require schema-only helpers directly
- do not reintroduce schema-shaped mirrors into entries, AST nodes, or reports

## Tests

Add or update focused tests for:

- `semantic-type-value?` fast-path behavior on canonical semantic maps
- `normalize-type` on canonical semantic values without boundary localization
- boundary coercion helpers still accepting raw Schema-domain values and Vars
- `map_ops` behavior when given canonical semantic key types
- `schema->type` and canonicalization behavior unchanged at public boundaries

Existing suites to run at minimum:

- `lein test skeptic.analysis.type-ops-test`
- `lein test skeptic.analysis.bridge-test`
- `lein test skeptic.analysis.calls-test`
- `lein test skeptic.analysis.annotate-test`
- `lein test skeptic.checking.pipeline-test`
- `lein test skeptic.analysis.map-ops-test`
- `lein test skeptic.inconsistence.report-test`

Then run:

- `lein test`

## Profiling Validation

Re-run the profiling workload after the refactor and compare:

- time in `skeptic.analysis.type-ops/normalize-type`
- time in `skeptic.analysis.bridge.localize/localize-value*`
- allocation weight under `localize-value*`
- allocation churn in map/type helper paths
- GC count and pause time

Success means:

- `normalize-type` no longer spends most of its time entering the mixed boundary path
- `localize-value*` appears mainly at schema-facing entrypoints instead of throughout the annotation/type path
- behavior and test output remain unchanged

## Risks

- some internal callers may still quietly rely on raw boundary values reaching `normalize-type`
- `map_ops.clj` may have edge cases where callers pass non-canonical keys that are still semantically valid
- changing canonical semantic fast paths in `localize-value*` could alter recursive copy behavior in subtle ways

## Rollback Strategy

Each step should be independently reversible.

Recommended checkpoints:

1. `types.clj` semantic tag helpers only
2. `localize.clj` structure cleanup with no contract change
3. `type_ops.clj` split between boundary coercion and normalization
4. `map_ops.clj` narrowed to type-only hot-path behavior
5. caller audit and profiling pass

If a caller unexpectedly depends on the old mixed contract, add an explicit boundary adapter at that call site instead of restoring the global implicit coercion behavior.

## Suggested Agent Ownership

### Agent 1

- `src/skeptic/analysis/types.clj`

Deliver:

- semantic tag extraction helper
- simplified `semantic-type-value?`
- tests for canonical semantic detection

### Agent 2

- `src/skeptic/analysis/bridge/localize.clj`

Deliver:

- structured boundary-family dispatch
- canonical semantic fast path
- tests that preserve Var, unbound Var, and schema-base behavior

### Agent 3

- `src/skeptic/analysis/type_ops.clj`
- `src/skeptic/analysis/map_ops.clj`

Deliver:

- explicit boundary coercion helper
- narrowed `normalize-type`
- removal of redundant hot-path localization in map helpers
- focused regression tests

### Integrator

Files:

- `src/skeptic/analysis/annotate.clj`
- `src/skeptic/analysis/calls.clj`
- `src/skeptic/analysis/origin.clj`
- `src/skeptic/analysis/type_algebra.clj`
- `src/skeptic/analysis/value.clj`
- `src/skeptic/analysis/cast.clj`
- `src/skeptic/analysis/cast/support.clj`
- `src/skeptic/analysis/cast/map.clj`
- `src/skeptic/analysis/bridge/render.clj`
- `src/skeptic/checking/pipeline.clj`
- `src/skeptic/inconsistence/report.clj`

Deliver:

- caller audit
- contract cleanup
- full test pass
- reprofiling notes

## Acceptance Criteria

This plan is complete when the implementation produces all of the following:

- canonical semantic types remain the only internal shared type representation on hot paths
- `normalize-type` is no longer the implicit mixed boundary adapter
- `localize-value` remains boundary-facing instead of being paid repeatedly by type-only logic
- map/type helper hot paths stop doing redundant localize-then-normalize work
- schema-facing entrypoints still accept and correctly process raw Schema-domain inputs
- the focused suites and the full test suite pass
- the refreshed profile shows material reduction in the `normalize-type` / `localize-value*` hotspot
