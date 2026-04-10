# `skeptic.analysis.cast` Function Map

This document is source-derived from:

- `src/skeptic/analysis/cast.clj`
- `src/skeptic/analysis/cast/kernel.clj`
- `src/skeptic/analysis/cast/map.clj`
- `src/skeptic/analysis/cast/support.clj`

## Governing Path

The cast subtree is organized around one public entrypoint and three support layers:

1. `skeptic.analysis.cast/check-cast` normalizes the two semantic types and dispatches by type shape.
2. `skeptic.analysis.cast.kernel/*` implements the generic cast rules for quantified types, abstract types, unions, intersections, wrappers, functions, vectors, seqs, sets, and leaf types.
3. `skeptic.analysis.cast.map/*` handles the special map-coverage algorithm by planning casts over exact keys, unexpected keys, and domain keys.
4. `skeptic.analysis.cast.support/*` carries cast-state, result construction, path helpers, and a few reusable checks.

## Interconnection Map

### Namespace-level graph

```mermaid
flowchart TD
  CC["cast/check-cast"] --> K["cast.kernel generic rules"]
  CC --> M["cast.map map rule"]
  CC --> S["cast.support result helpers"]

  K --> KR["kernel request helpers"]
  K --> S
  K --> CC

  M --> MP["map planners"]
  MP --> KR
  MP --> S
  MP --> MV["analysis.map-ops + value-check"]

  S --> TA["type-algebra"]
  S --> TR["bridge.render"]
```

### Main function-to-function flows

- Dispatcher fan-out:
  `check-cast` either returns directly through `support/cast-ok`, or delegates to `kernel/check-quantified-cast`, `kernel/check-abstract-type-cast`, `kernel/check-union-cast`, `kernel/check-intersection-cast`, `kernel/check-maybe-cast`, `kernel/check-wrapper-cast`, `kernel/check-function-cast`, `map/check-map-cast`, `kernel/check-vector-cast`, `kernel/check-seq-cast`, `kernel/check-seq-to-vector-cast`, `kernel/check-vector-to-seq-cast`, `kernel/check-set-cast`, or `kernel/check-leaf-cast`.
- Recursive cycle:
  Most non-leaf rules do not recurse by calling `check-cast` directly. They build `kernel/cast-request` values, run them through `kernel/run-cast-request` or `kernel/run-cast-requests`, and those helpers call back into `check-cast`.
- Shared aggregation path:
  `kernel/check-union-cast`, `kernel/check-intersection-cast`, `kernel/check-function-cast`, `kernel/check-vector-cast`, `kernel/check-seq-cast`, `kernel/check-seq-to-vector-cast`, `kernel/check-vector-to-seq-cast`, `kernel/check-set-cast`, and `map/check-map-cast` all converge on `kernel/aggregate-all-children`, which in turn converges on `support/cast-ok` or `support/cast-fail`.
- Quantified-state path:
  `kernel/check-quantified-cast` is the only generic rule that uses `support/with-abstract-var`, `support/with-nu-binding`, and `support/cast-state` to thread quantified-state information through recursive casts.
- Sealed-abstract path:
  `kernel/check-abstract-type-cast` is the rule that uses `support/register-seal`, `support/sealed-ground-name`, and `support/cast-state` to manage the seal/collapse behavior for abstract types.
- Function path:
  `kernel/check-function-cast -> kernel/check-function-method-cast -> kernel/function-domain-requests -> kernel/run-cast-requests`.
  The same method rule also creates one range request with `kernel/cast-request` and `kernel/run-cast-request`.
  Method selection itself goes through `support/matching-source-method` and `support/method-accepts-arity?`.
- Collection path:
  `kernel/check-vector-cast`, `kernel/check-seq-cast`, `kernel/check-seq-to-vector-cast`, and `kernel/check-vector-to-seq-cast` all use `kernel/collection-cast-children`.
  Vector/seq conversions additionally depend on `kernel/vector-cast-slot-count` and `kernel/expand-vector-items`.
- Set path:
  `kernel/check-set-cast` uses `kernel/set-cast-children`, which is the only collection matcher in this subtree that tries several target candidates per source element instead of zipping positions.
- Leaf path:
  `kernel/check-leaf-cast` is where the subtree exits into value-level compatibility checks through `analysis.value-check/value-satisfies-type?` and `analysis.value-check/leaf-overlap?`.
- Map path:
  `map/check-map-cast -> map/map-cast-children`.
  `map/map-cast-children` fans out into `map/exact-target-entry-cast-results`, `map/exact-source-entry-cast-results`, and `map/domain-entry-cast-results`.
  Those three functions depend on the corresponding `plan-*` helpers plus `kernel/cast-request`, `kernel/run-cast-requests`, and `support/cast-fail`.
- Map-descriptor path:
  The whole map rule is driven by `analysis.map-ops/map-entry-descriptor`, `analysis.map-ops/effective-exact-entries`, `analysis.map-ops/exact-key-candidates`, `analysis.map-ops/exact-key-entry`, and `analysis.map-ops/key-domain-covered?`.
- Path-reporting path:
  `map/map-entry-failure` combines `support/cast-fail` with `analysis.value-check/with-map-path`.
  Generic child requests use `support/with-cast-path` instead.

### Utility entrypoints not on the main `check-cast` path

- `support/indexed-cast-children`
- `support/check-type-test`
- `support/exit-nu-scope`
- `map/candidate-value-cast-results`

These functions are defined in the cast subtree but are not referenced by other `skeptic/src` files in the current workspace.

## Namespace Map

### `skeptic.analysis.cast`

- `check-cast`: Public dispatcher. Normalizes both types, sets the active polarity in `opts`, then chooses the rule family in a fixed order: bottom and exact equality first, then quantified and abstract rules, then dynamic/union/intersection/maybe/wrapper/function/map/collection cases, and finally leaf comparison.

### `skeptic.analysis.cast.support`

- `ensure-cast-state`: Fills in missing cast-state fields with defaults for `:nu-bindings`, `:abstract-vars`, and `:active-seals`.
- `cast-state`: Reads `:cast-state` from opts and normalizes it through `ensure-cast-state`.
- `with-abstract-var`: Adds a quantified binder to the active abstract-variable set.
- `with-nu-binding`: Records a binder-to-witness binding and also marks the binder abstract.
- `register-seal`: Adds a normalized sealed-dynamic witness to the active seal set.
- `sealed-ground-name`: Pulls a type-variable-style name back out of a sealed dynamic ground.
- `contains-sealed-ground?`: Walks a semantic type recursively to see whether a given binder appears inside any sealed dynamic ground.
- `cast-result`: Low-level constructor for the cast result map, including blame-side and blame-polarity fields.
- `cast-ok`: Convenience constructor for successful cast results.
- `cast-fail`: Convenience constructor for failing cast results.
- `with-cast-path`: Appends one visible path segment to a cast result.
- `indexed-cast-children`: Builds child results and tags each one with an indexed path segment.
- `all-ok?`: Returns true only when every child result succeeded.
- `check-type-test`: Evaluates a dynamic type test. It succeeds for normal values and fails globally for sealed dynamics because those represent tampering-sensitive abstractions.
- `exit-nu-scope`: Checks that a type leaving a quantified scope no longer contains the quantified binder in any sealed ground.
- `method-accepts-arity?`: Arity matcher for `FnMethodT`, including variadic methods.
- `matching-source-method`: Finds the first source function method whose arity can satisfy a target method.
- `optional-key-inner`: Unwraps `OptionalKeyT` and otherwise returns the input unchanged.

### `skeptic.analysis.cast.kernel`

#### Request and child orchestration

- `cast-request`: Packages one recursive cast request, optionally with a path segment.
- `run-cast-request`: Executes one request through the recursive `check-cast` function and attaches the path segment if present.
- `run-cast-requests`: Executes a vector of requests.
- `indexed-cast-requests`: Builds requests over a collection while tagging each one with an indexed path segment.
- `aggregate-all-children`: Returns `cast-ok` when every child succeeded, otherwise `cast-fail` with the accumulated children.
- `collection-cast-children`: Shared helper for position-wise vector and seq casting.
- `expand-vector-items`: Expands a homogeneous vector type into concrete slots for a requested arity.
- `vector-cast-slot-count`: Computes the compatible slot count for vector/vector and vector/seq casts, including homogeneous expansion.
- `set-cast-children`: For each source set member, tries all target members and keeps the first success or records an element failure.

#### Quantified and abstract rules

- `check-quantified-cast`: Handles both quantified target types (`generalize`) and quantified source types (`instantiate`), while managing abstract binders and nu-bindings in cast-state.
- `check-abstract-type-cast`: Handles type variables and sealed dynamics, including sealing source abstractions, collapsing matching seals, and rejecting incompatible abstract targets or sources.

#### Union, intersection, maybe, and wrapper rules

- `check-union-cast`: For source unions, every source branch must cast to the target. For target unions, any target branch may succeed.
- `check-intersection-cast`: For target intersections, every target component must accept the source. For source intersections, each source component is checked against the target.
- `check-maybe-cast`: Handles `MaybeT` on either side, including the special case where exact `nil` satisfies a maybe target.
- `check-wrapper-cast`: Strips `OptionalKeyT` and `VarT` wrappers and recurs on the inner types.

#### Function rules

- `function-domain-requests`: Builds contravariant argument casts from target inputs to source inputs and flips polarity for those checks.
- `check-function-method-cast`: Checks one target method against a matching source method by combining domain child casts with one range cast.
- `check-function-cast`: Runs `check-function-method-cast` for every target method and aggregates the results.

#### Collection and leaf rules

- `check-vector-cast`: Aligns vector slots, expands homogeneous vectors when needed, then casts slot-by-slot.
- `check-seq-cast`: Casts seq items positionally when arities match.
- `check-seq-to-vector-cast`: Casts seq items into vector slots using the same slot-expansion logic as vector casts.
- `check-vector-to-seq-cast`: Casts vector items into seq slots using the same slot-expansion logic as vector casts.
- `check-set-cast`: Requires equal set cardinality, then matches each source member against some target member.
- `check-leaf-cast`: Final fallback for value, dynamic, placeholder, ground, refinement, adapter-leaf, and function-vs-adapter-leaf combinations.

### `skeptic.analysis.cast.map`

#### Path and failure helpers

- `map-path-segment`: Converts an exact key into a visible `{:kind :map-key ...}` path segment when possible.
- `map-entry-failure`: Builds a map-specific failure result and attaches the failing key path.

#### Candidate execution helpers

- `run-candidate-casts`: Runs a set of requests and collapses the result to a single success when any candidate succeeds; otherwise preserves the full failure set.
- `candidate-value-cast-results`: Builds candidate value-cast requests for a source value against several target entries.

#### Exact-key planning

- `plan-exact-target-entry-casts`: For one exact target entry, finds candidate source entries and builds the corresponding value-cast requests.
- `exact-target-entry-cast-results`: Executes the target-entry plan and also reports missing required keys or nullable-key mismatches.
- `plan-exact-source-entry-casts`: For one exact source entry that is not already matched exactly, finds compatible target domain entries.
- `exact-source-entry-cast-results`: Executes that plan or reports an unexpected source key when no target domain entry covers it.

#### Domain-key planning

- `expand-domain-entry`: Splits a union-valued source domain key into one entry per member so coverage checks happen member-by-member.
- `plan-domain-entry-casts`: Finds target domain entries whose key domains cover a source domain entry.
- `domain-entry-cast-results`: Executes those domain-entry casts or emits a `:map-key-domain` failure when the target key domain does not cover the source key domain.

#### Map subtree driver

- `map-cast-children`: Builds the full child-result set for a map cast by combining three passes:
  required and optional target exact entries,
  extra exact source entries,
  and source domain entries.
- `check-map-cast`: Public map-rule entrypoint. Runs `map-cast-children` and aggregates the results under the `:map` rule.

## Shape Summary

- Dispatcher: `check-cast`
- Generic recursive rules: `kernel.clj`
- Map-specific coverage algorithm: `map.clj`
- State/result/path utilities: `support.clj`

The main design pattern in this subtree is: build child requests first, run them through the recursive `check-cast`, then aggregate the resulting child tree into one cast result with blame metadata and visible paths.
