# Schema Boundary Implementation Plan

## Purpose

This document turns the schema-boundary vision into an exact file-by-file migration plan.

The target outcome is:

- Schema is isolated to a closed schema-only subsystem
- all internal analysis and logic run on semantic types only
- there is exactly one conversion direction, `schema->type`
- there is no internal `type->schema` logic and no schema-shaped mirrors in analysis state

## Target Files and Responsibilities

## Schema-only files

These files remain schema-facing and are allowed to hold raw Schema values:

- `src/skeptic/schema/collect.clj`
- `src/skeptic/schema.clj`
- `src/skeptic/analysis/schema_base.clj`
- `src/skeptic/analysis/bridge.clj`
- `src/skeptic/analysis/bridge/canonicalize.clj`
- `src/skeptic/analysis/bridge/localize.clj`
- `src/skeptic/analysis/bridge/algebra.clj`
- `src/skeptic/analysis/schema/cast.clj`
- `src/skeptic/analysis/schema/map_ops.clj`
- `src/skeptic/analysis/schema/value_check.clj`
- `src/skeptic/analysis/schema/valued.clj`

## Type-only files

These files become type-only and must not depend on schema-only helpers except through explicit boundary adapters:

- `src/skeptic/analysis/types.clj`
- `src/skeptic/analysis/value.clj`
- `src/skeptic/analysis/normalize.clj`
- `src/skeptic/analysis/calls.clj`
- `src/skeptic/analysis/origin.clj`
- `src/skeptic/analysis/annotate.clj`
- `src/skeptic/checking/pipeline.clj`
- `src/skeptic/inconsistence/display.clj`
- `src/skeptic/inconsistence/report.clj`
- `src/skeptic/inconsistence/mismatch.clj`
- `src/skeptic/core.clj`

## New type-only files to create

Create these type-only namespaces and move logic into them:

- `src/skeptic/analysis/type_ops.clj`
- `src/skeptic/analysis/type_algebra.clj`
- `src/skeptic/analysis/cast.clj`
- `src/skeptic/analysis/cast/support.clj`
- `src/skeptic/analysis/cast/kernel.clj`
- `src/skeptic/analysis/cast/map.clj`
- `src/skeptic/analysis/map_ops.clj`
- `src/skeptic/analysis/value_check.clj`

## Step 1: Extract type normalization out of `bridge.clj`

Completed.

- Added `src/skeptic/analysis/type_ops.clj` with `literal-ground-type`, `exact-value-type`, `normalize-type`, `nil-bearing-type-members`, `normalize-intersection-members`, `union-type`, `intersection-type`, `de-maybe-type`, and `unknown-type?`.
- Reduced `src/skeptic/analysis/bridge.clj` to schema import logic only. `type-domain-value?` and `unknown-schema?` were removed, and `schema->type` now rejects semantic-type input and only accepts actual Schema-domain values.
- Repointed type-only callers to `skeptic.analysis.type_ops`, including `analysis/value.clj`, `analysis/calls.clj`, `analysis/origin.clj`, `analysis/annotate.clj`, `checking/pipeline.clj`, and `inconsistence/mismatch.clj`.
- Updated immediate compatibility dependents that were still using the mixed bridge behavior, including `analysis/bridge/algebra.clj`, `analysis/bridge/render.clj`, `analysis/normalize.clj`, `analysis/schema/cast.clj`, `analysis/schema/map_ops.clj`, `analysis/schema/value_check.clj`, and `inconsistence/report.clj`.
- Changed `skeptic.inconsistence.mismatch/unknown-output-schema?` to use semantic unknown-type checks instead of the removed `unknown-schema?`.
- Updated tests that previously sent semantic types back through `schema->type`, and added direct coverage for the new `type_ops` namespace and for `schema->type` rejecting semantic-type input.
- Tightening `schema->type` exposed several internal call paths that still treated it as a mixed schema/type coercion helper. Those paths were converted to local “schema-or-type to semantic type” adapters instead of reopening the public bridge contract.
- A message regression in unexpected-map-key reporting was fixed after the Step 1 cut so visible-path errors still say `is not allowed by the expected Plumatic Schema` or `is not allowed by the declared Plumatic Schema`.
- Focused `lein test` runs passed for the affected analysis, reporting, mismatch, display, path, pipeline, and core suites after the migration.

## Step 2: Extract type algebra out of `bridge/algebra.clj`

Completed.

- Added `src/skeptic/analysis/type_algebra.clj` and moved `type-var-name`, `type-free-vars`, and `type-substitute` into it with semantic-type-only dependencies on `type_ops` and `types`.
- Reduced `src/skeptic/analysis/bridge/algebra.clj` to `resolve-placeholders` only, leaving raw Schema reconstruction in the schema-only layer.
- Repointed direct callers to `skeptic.analysis.type_algebra`, including `analysis/cast/support.clj`, `analysis/cast/kernel.clj`, `test/skeptic/analysis/bridge_test.clj`, and `test/skeptic/analysis/type_ops_test.clj`.
- Added focused regression coverage that `resolve-placeholders` remains reachable from `bridge.algebra` and still resolves placeholder-containing raw Schema values.
- Focused `lein test` runs passed for `skeptic.analysis.bridge-test`, `skeptic.analysis.type-ops-test`, and `skeptic.analysis.schema.cast-test` after the migration.

## Step 3: Remove reverse rendering from `bridge/render.clj`

Completed.

- Reduced `src/skeptic/analysis/bridge/render.clj` to type rendering only. `render-schema`, `display-form`, `display`, `fn-method->schema-compat`, and `type->schema-compat` were removed; `render-fn-input-form`, `render-type-form`, `render-type`, `polarity->side`, `flip-polarity`, and `strip-derived-types` remain.
- Repointed schema-facing display callers to `skeptic.analysis.bridge.canonicalize/schema-display-form`, including `src/skeptic/schema/collect.clj` and the schema branch in `src/skeptic/inconsistence/display.clj`.
- Repointed type-facing display callers to semantic rendering only, including `src/skeptic/inconsistence/display.clj` and `src/skeptic/core.clj`.
- Removed regenerated schema mirrors from the live typed-analysis path so deleting `type->schema-compat` did not just relocate the reverse bridge. `src/skeptic/analysis/normalize.clj`, `src/skeptic/analysis/calls.clj`, `src/skeptic/analysis/annotate.clj`, `src/skeptic/analysis/origin.clj`, and the typed `convert-arglists` path in `src/skeptic/analysis/resolvers.clj` now carry semantic `:type`, `:output-type`, and typed arglists instead of internal `:schema`, `:output`, `:expected-arglist`, `:actual-arglist`, and `:fn-schema` mirrors.
- Fixed the follow-on output-report rendering regression by making `src/skeptic/inconsistence/report.clj` render output mismatch messages from semantic types rather than raw Schema payloads.
- Updated the direct regression coverage to the type-only contract in `test/skeptic/analysis/bridge_test.clj`, `test/skeptic/analysis/type_ops_test.clj`, `test/skeptic/analysis/calls_test.clj`, `test/skeptic/analysis/annotate_test.clj`, `test/skeptic/checking/pipeline_test.clj`, `test/skeptic/inconsistence/report_test.clj`, `test/skeptic/analysis/origin_test.clj`, and related projection helpers.
- Focused `lein test` runs passed for `skeptic.inconsistence.display-test`, `skeptic.inconsistence.report-test`, `skeptic.analysis.bridge-test`, `skeptic.analysis.type-ops-test`, `skeptic.analysis.normalize-test`, `skeptic.analysis.calls-test`, `skeptic.analysis.annotate-test`, `skeptic.analysis.origin-test`, `skeptic.checking.pipeline-test`, `skeptic.core-test`, `skeptic.schema.collect-test`, and `skeptic.typed-decls-test`.

## Step 4: Add typed declaration ingestion

Completed.

- Kept the existing raw schema APIs in `src/skeptic/schema/collect.clj` unchanged. `get-fn-schemas*`, `get-fn-schemas`, `collect-schemas`, `var-schema-desc`, and `ns-schemas` still remain schema-facing and return `SchemaDesc`.
- Added typed declaration ingestion in `src/skeptic/typed_decls.clj`, including `one->typed-arg-entry`, `arglist->typed-entry`, `callable-desc->typed-entry`, `desc->typed-entry`, and `typed-ns-entries`.
- Made `desc->typed-entry` the typed declaration boundary adapter. It imports declaration payloads through `schema->type` and returns semantic-only entries shaped around `:name`, `:type`, optional `:output-type`, and optional typed `:arglists`.
- Preserved arglist structure in the typed path, including `:arglist`, `:count`, per-argument `:name`, `:optional?`, and semantic `:type`, with varargs entries converted into the same typed shape.
- Kept schema-shaped fields out of the new typed path. Typed entries do not carry `:schema`, `:output`, or mixed schema/type fallback payloads.
- Added focused regression coverage in `test/skeptic/typed_decls_test.clj` and `test/skeptic/schema/collect_test.clj` for callable typed entries, non-callable typed entries, semantic-only output shape, varargs typing, and dynamic `Any` fallback entries returned by `typed-ns-entries`.
- Left consumer migration intentionally deferred. `normalize.clj`, `calls.clj`, `origin.clj`, `annotate.clj`, and `pipeline.clj` were not repointed in Step 4.

## Step 5: Simplify `normalize.clj` into typed-entry normalization

Completed.

- Reduced `src/skeptic/analysis/normalize.clj` to a type-only structural cleanup layer. `normalize-declared-type`, `one->arg-entry`, and `schema->callable` were removed, and `normalize-entry` no longer imports Schema or reconstructs callable entries from raw schema payloads.
- Tightened `arg-entry-map?`, `normalize-arg-entry`, `normalize-arglist-entry`, `entry-map?`, and `normalize-entry` to the typed-entry contract. They now operate on semantic `:type`, `:output-type`, and typed `:arglists` only, default `:optional?` and `:count`, and reject raw schema-shaped maps.
- Moved remaining Schema imports to the real boundaries instead of leaving them in normalization. `src/skeptic/analysis/value.clj`, `src/skeptic/analysis/calls.clj`, and `src/skeptic/analysis/annotate.clj` now call `schema->type` directly where semantic types are introduced.
- Switched namespace declaration ingestion to `typed-ns-entries` in the live checking path, including `src/skeptic/core.clj`, and updated the active analysis and checking tests to build declaration dictionaries from typed entries instead of raw `SchemaDesc`.
- Kept local analysis values compatible with the typed-only normalize contract by wrapping bare semantic locals into typed entries at the `annotate.clj` boundary instead of widening `normalize-entry` again.
- Added focused regression coverage in `test/skeptic/analysis/normalize_test.clj` for rejecting raw schema-only entries and for typed-entry defaulting behavior, and updated the affected analysis, origin, calls, annotate, and pipeline tests to the typed dictionary contract.
- Focused `lein test` runs passed for `skeptic.analysis.normalize-test`, `skeptic.analysis.origin-test`, `skeptic.analysis.calls-test`, `skeptic.analysis-test`, and `skeptic.checking.pipeline-test` after the migration.

## Step 6: Move the cast engine into a type-only namespace tree

Completed.

- Added the type-only cast engine namespaces `src/skeptic/analysis/cast.clj`, `src/skeptic/analysis/cast/support.clj`, `src/skeptic/analysis/cast/kernel.clj`, and `src/skeptic/analysis/cast/map.clj`.
- Kept the type-only cast engine in `src/skeptic/analysis/cast.clj`, `src/skeptic/analysis/cast/support.clj`, `src/skeptic/analysis/cast/kernel.clj`, and `src/skeptic/analysis/cast/map.clj`.
- Reduced `src/skeptic/analysis/schema/cast.clj` to the schema-boundary adapter surface. `check-cast` now converts raw Schema inputs once and delegates to the type-only engine.
- Repointed cast-support users to `skeptic.analysis.cast.support`, including `src/skeptic/analysis/cast.clj`, `src/skeptic/analysis/cast/kernel.clj`, `src/skeptic/analysis/cast/map.clj`, `src/skeptic/analysis/map_ops.clj`, `src/skeptic/analysis/value_check.clj`, and `test/skeptic/analysis/cast_test.clj`.
- Removed the old mixed schema-cast implementation files so non-schema code no longer depends on them.
- Focused `lein test` runs passed for `skeptic.analysis.schema.cast-test`, `skeptic.analysis.schema.map-cast-test`, `skeptic.inconsistence.path-test`, `skeptic.inconsistence.report-test`, `skeptic.analysis.bridge-test`, `skeptic.analysis.type-ops-test`, and `skeptic.analysis.calls-test` after the migration.

## Step 7: Split map reasoning into schema adapters and type logic

Completed.

- Added `src/skeptic/analysis/map_ops.clj` and moved the type-only map reasoning surface there, including query construction, descriptor building, lookup candidate selection, `map-get-type`, and `merge-map-types`.
- Changed the map-key query representation to carry semantic `:type` rather than raw Schema `:schema`.
- Reduced `src/skeptic/analysis/schema/map_ops.clj` to the schema-facing adapter layer, keeping `nested-value-compatible?`, `map-get-schema`, and `merge-map-schemas` as raw-Schema entrypoints that delegate into the type-side logic where appropriate.
- Repointed active internal callers to `skeptic.analysis.map_ops`, including `src/skeptic/analysis/calls.clj` and `src/skeptic/analysis/annotate.clj`.
- The result is that internal map reasoning runs on semantic types, while Schema-returning map helpers stay isolated to the schema-facing namespace.

## Step 8: Split value/type checks into schema adapters and type logic

Completed.

- Added `src/skeptic/analysis/value_check.clj` and moved the semantic helper surface there: `exact-value-type?`, `path-key`, `with-map-path`, `map-contains-key-classification`, `contains-key-type-classification`, `refine-type-by-contains-key`, `ground-accepts-value?`, `leaf-overlap?`, `type-compatible-map-value?`, `set-value-satisfies-type?`, `map-value-satisfies-type?`, and `value-satisfies-type?`.
- Reduced `src/skeptic/analysis/schema/value_check.clj` to the schema-facing adapter functions `map-entry-kind`, `contains-key-classification`, and `refine-schema-by-contains-key`, leaving schema-library partial behavior on the schema side.
- Repointed internal callers to `skeptic.analysis.value_check`, including `src/skeptic/analysis/origin.clj`, `src/skeptic/analysis/map_ops.clj`, `src/skeptic/analysis/cast/kernel.clj`, and `src/skeptic/analysis/cast/map.clj`.
- Updated schema-facing regression tests to call the moved semantic helpers through the new type-only namespace and added focused coverage in `test/skeptic/analysis/value_check_test.clj`.
- The full `lein test` suite passed after the migration, including the follow-up fix that restored `src/skeptic/analysis/schema/valued.clj` to the schema-facing adapter path so its public API still returns raw schema/value forms.

## Step 9: Remove dead schema reconstruction in `resolvers.clj`

Completed.

- Moved the typed `convert-arglists` helper into `src/skeptic/analysis/calls.clj`.
- Deleted `src/skeptic/analysis/resolvers.clj` entirely instead of leaving an empty namespace behind.
- This removed the unused reverse Schema reconstruction helpers that had no remaining active callers and violated the one-way schema boundary.
- The result is that no internal schema-resolution subsystem survives past the typed callable boundary.

## Step 10: Make `calls.clj` type-only

Completed.

- Reduced `src/skeptic/analysis/calls.clj` to type-only dependencies by removing the direct requires on `schema.core`, `skeptic.analysis.bridge`, `skeptic.analysis.schema-base`, and the deleted `skeptic.analysis.resolvers`.
- Kept `node-info` restricted to the typed fields `:type`, `:output-type`, `:arglists`, `:arglist`, `:expected-argtypes`, `:actual-argtypes`, `:fn-type`, and `:origin`.
- Replaced the fallback callable construction in `default-call-info` with direct semantic function construction using `at/->FunT` and `at/->FnMethodT` instead of going through `sb/dynamic-fn-schema` and `schema->type`.
- Updated `call-info` to use the moved local `convert-arglists` helper and return typed metadata only.
- Added regression coverage in `test/skeptic/analysis/calls_test.clj` to assert the absence of the internal schema mirrors `:schema`, `:output`, `:expected-arglist`, `:actual-arglist`, and `:fn-schema` on projected call nodes.
- The result is that call analysis metadata is purely semantic and no longer regenerates Schema-shaped mirrors internally.

## Step 11: Make `origin.clj` type-only

Completed.

- Removed the dependency on `src/skeptic/analysis/normalize.clj` from `src/skeptic/analysis/origin.clj`.
- Added a local typed-entry coercion helper in `origin.clj` so flow refinement accepts either typed entry maps or bare semantic types without reopening schema-era fallback behavior.
- Updated `effective-entry` to use the new local typed-entry coercion instead of `normalize-entry`, and kept its output limited to typed fields plus `:origin`.
- Preserved the existing refinement behavior for `truthy-local` and `contains-key` assumptions through `skeptic.analysis.value_check`.
- Added focused regression coverage in `test/skeptic/analysis/origin_test.clj` that `effective-entry` returns typed data only and does not reintroduce `:schema`.

## Step 12: Make `annotate.clj` type-only

Completed for the remaining annotation surface changed in this session.

- Renamed the public annotation entrypoint in `src/skeptic/analysis/annotate.clj` from `attach-schema-info-loop` to `annotate-form-loop`.
- Renamed the arg helper `arg-schema-specs` to `arg-type-specs`.
- Renamed fn-method metadata from `:arg-schema` to `:param-specs` and updated `annotate-fn-method`, `method->arglist-entry`, and `annotate-fn` to use the new field consistently.
- Removed the direct schema-facing requires from `annotate.clj` and replaced schema-derived bool typing with direct semantic bool construction.
- Replaced class-tag typing through schema import in `annotate-new` and `annotate-catch` with direct use of `skeptic.analysis.value/class->type`.
- Added `class->type` to `src/skeptic/analysis/value.clj` and updated `src/skeptic/checking/pipeline.clj` to use it for tagged method output inference.
- Updated `src/skeptic/analysis/bridge/render.clj` so `strip-derived-types` recurses through `:param-specs` instead of `:arg-schema`.
- Repointed callers and test helpers to `annotate-form-loop`, including `src/skeptic/checking/pipeline.clj`, `test/skeptic/analysis_test.clj`, and direct annotation tests.
- Added focused regression coverage in `test/skeptic/analysis/annotate_test.clj` for the new `:param-specs` field and for the absence of the internal schema-mirror fields `:schema`, `:output`, `:fn-schema`, `:actual-arglist`, and `:expected-arglist` on projected annotated nodes.
- Focused `lein test` runs passed for `skeptic.analysis.origin-test`, `skeptic.analysis.annotate-test`, `skeptic.analysis-test`, and `skeptic.checking.pipeline-test` after the migration.

## Step 13: Make `value.clj` direct-to-type

Completed.

- Reduced `src/skeptic/analysis/value.clj` to the direct semantic-type path. `class->schema`, `coll-element-schema`, `map-schema`, and `schema-of-value` were removed from the namespace.
- Kept `class->type` and `type-join*` as the public type-side helpers.
- Rewrote `type-of-value` to construct semantic types directly for literals, vectors, seqs/lists, sets, maps, classes, and fallback runtime classes instead of round-tripping through Schema reconstruction.
- Preserved exact map-entry information in the direct path by building valued semantic key/value entries for runtime map literals rather than reconstructing raw Schema maps first.
- Updated `test/skeptic/analysis/value_test.clj` to assert semantic `type-of-value` behavior instead of schema reconstruction helpers.

## Result

Native values no longer go through a native -> Schema -> type round trip, and the old schema-reconstruction helpers are gone from the type-only value path.

## Step 14: Make pipeline and reporting type-only

Completed.

- `src/skeptic/checking/pipeline.clj` now uses the type-only output contract all the way through. `method-output-type` uses `some-> (:tag body) av/class->type`, and the remaining unknown-output check now goes through `incm/unknown-output-type?`.
- `src/skeptic/inconsistence/report.clj` now depends on `skeptic.analysis.cast` for report-time cast execution and treats `dynamic-display-type?`, `cast-report`, and `output-cast-report` as semantic-type-only functions.
- Removed report-time schema coercion from the internal reporting path. The mixed `as-type` helper is gone, and the report path no longer canonicalizes raw Schema or converts it back to types before dispatch.
- `src/skeptic/inconsistence/mismatch.clj` now exposes `unknown-output-type?`; `output-compatible-schemas` and `unknown-output-schema?` were removed.
- `src/skeptic/inconsistence/display.clj` now keeps the rendering split explicit: schema display still goes through `abc/schema-display-form`, type display goes through `abr/render-type-form`, and the remaining exact-key extraction no longer uses `schema->type`.
- `src/skeptic/core.clj` already used `typed-decls/typed-ns-entries` from earlier steps and needed no further structural change for this cut.
- Updated the report- and path-facing tests to use semantic types when exercising the narrowed report APIs, including `test/skeptic/inconsistence/report_test.clj` and `test/skeptic/inconsistence/path_test.clj`.

## Result

Reporting, path rendering, and checking consume semantic types all the way through, while Plumatic-Schema-facing wording such as `declared return Plumatic Schema` remains explicit for user-visible diagnostics.

## Step 15: Update tests by boundary

Completed for the remaining boundary-sensitive test surface touched by Steps 13 and 14.

- Updated the direct value-path coverage in `test/skeptic/analysis/value_test.clj` to assert semantic `type-of-value` results instead of `schema-of-value`.
- Updated `test/skeptic/inconsistence/mismatch_test.clj` to use `unknown-output-type?` and removed the old `output-compatible-schemas` coverage.
- Updated `test/skeptic/inconsistence/report_test.clj` so `cast-report` and `output-cast-report` are exercised with semantic types, matching the narrowed contract of the production functions.
- Updated `test/skeptic/inconsistence/path_test.clj` to pass semantic types into `cast-report`, preserving the expected visible-path behavior after the reporting boundary stopped accepting raw Schema values on internal paths.
- Earlier steps had already moved the internal analysis tests and pipeline tests onto typed entries and semantic-only metadata; this final cut kept those tests passing without reopening schema-shaped mirrors.
- Schema-boundary tests remain schema-facing where intended, including `test/skeptic/schema/collect_test.clj`, `test/skeptic/analysis/schema/cast_test.clj`, and `test/skeptic/analysis/schema/map_cast_test.clj`, while the schema canonicalization / placeholder tests now live in `test/skeptic/analysis/bridge_test.clj`.
- Focused type-only coverage now exists across the migrated areas, including `skeptic.analysis.value/type-of-value`, `skeptic.analysis.map-ops`, `skeptic.checking.pipeline`, and the report/path stacks. The full `lein test` suite passes after the migration.

## Result

Tests now enforce the boundary actually implemented in the codebase: schema-only tests stay at the boundary, and internal/reporting tests exercise semantic type behavior directly.

## Final Acceptance Checklist

The migration is complete only when all of these are true:

- `rg "type->schema" src` returns no matches
- `rg "compat-schema|compat-schemas" src` returns no matches
- `rg "schema->type" src` shows only schema-only boundary import code
- `rg ":schema|:output|:fn-schema|:actual-arglist|:expected-arglist|:arg-schema" src/skeptic/analysis src/skeptic/checking` returns no internal-analysis-state matches
- `src/skeptic/analysis/resolvers.clj` is deleted
- non-schema code no longer requires schema-only helpers directly
- the full test suite passes
- schema-boundary tests still verify raw Schema behavior
- internal analysis tests verify semantic type behavior only

## Defaults Chosen

- The vision document path is `docs/schema-boundary-vision.md`.
- The implementation plan path is `docs/schema-boundary-implementation-plan.md`.
- `skeptic.analysis.schema.cast/check-cast` remains as an explicit schema-boundary adapter for raw Schema callers.
- `typed-decls/typed-ns-entries` becomes the only declaration ingress used by internal analysis code.
- Internal field compatibility shims are not preserved.
