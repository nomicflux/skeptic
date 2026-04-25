# Cast Algorithm

This document describes the cast algorithm as it exists today, reconstructed from the current source and checked against [blame-for-all.md](./blame-for-all.md). It is written as a behavioral specification for a rewrite, not as a map of the current implementation.

## Algorithm

### Purpose

The cast relation decides whether a value with a given source type may be used where a target type is required. The result is not just yes or no:

- Every cast produces a tree of results.
- Each node records the source type, target type, the rule that was applied, whether the step succeeded, and any child steps.
- Failures carry blame polarity and a reason.
- Structural failures carry a path so higher layers can report the exact map key, vector slot, sequence slot, set member, union branch, or function position that failed.

The rewrite must preserve that tree-shaped result model, because reporting code depends on both the root result and the failure leaves.

### Preprocessing

Before any comparison:

- Normalize the source type with `type-ops/normalize-for-declared-type`.
- Normalize the target type with `type-ops/normalize-for-declared-type`.
- Preserve `ConditionalT` structure during that normalization rather than reducing
  it to a plain union.
- Use positive blame polarity unless a caller explicitly requests another polarity.

Function parameter checking flips polarity, but everything else keeps the current polarity.

### Annotate-To-Cast Handoff

The annotation pass does not call the cast engine directly.

Annotation remains first-order: it attaches node types and call metadata such as
`:actual-argtypes`, `:expected-argtypes`, `:output-type`, and `:fn-type`.

The checking pipeline consumes that metadata:

- `match-s-exprs` casts call and recur actual argument types to expected argument
  types through `skeptic.inconsistence.report/cast-report`.
- `def-output-results` casts inferred method output types to declared output
  types through `skeptic.inconsistence.report/output-cast-report`.
- The report layer runs `skeptic.analysis.cast/check-cast` and projects the
  resulting tree into summaries and leaf diagnostics.

### Decision Order

The cast relation is order-sensitive. The rewrite must preserve this precedence:

1. Bottom source succeeds immediately.
2. Exact type equality succeeds immediately.
3. If either side is universally quantified, use quantified casting.
4. If either side is an abstract type variable, or if the source is a sealed dynamic value, use abstract-type casting.
5. If the target is dynamic, succeed immediately.
6. If either side is a union, use union casting.
7. If either side is an intersection, use intersection casting.
8. If either side is conditional, use conditional casting.
9. If either side is nullable, use nullable casting.
10. If either side is a transparent wrapper such as an optional map key or a var wrapper, unwrap one layer and continue.
11. If both sides are functions, use function casting.
12. If both sides are maps, use map casting.
13. If both sides are vectors, use vector casting.
14. If both sides are sequences, use sequence casting.
15. If the source is a sequence and the target is a vector, use sequence-to-vector casting.
16. If the source is a vector and the target is a sequence, use vector-to-sequence casting.
17. If both sides are sets, use set casting.
18. Otherwise use leaf casting.

### Quantified Types

There are two quantified cases.

#### Generalizing to a universally quantified target

When the target is `forall X. T`:

- If the source still contains `X` free, fail with a capture error.
- Otherwise cast the source against `T`.
- If that child cast fails, the whole generalization fails.
- If that child cast succeeds, run the quantified boundary check described below before returning success.

#### Instantiating a universally quantified source

When the source is `forall X. T`:

- Replace `X` with dynamic.
- Cast the instantiated body against the target.
- If that child cast fails, the whole instantiation fails.
- If that child cast succeeds, run the quantified boundary check described below before returning success.

#### Quantified boundary check

This is the part that enforces the `Blame for All`-style scope rule.

- A cast from an abstract variable to dynamic introduces a sealed dynamic value tied to that binder.
- A cast from a sealed dynamic value back to the matching abstract variable discharges one seal.
- After a successful quantified child cast, inspect the whole child result tree for any unmatched seal tied to the quantified binder.
- If any such seal survives, fail with global blame for moving a sealed value out of scope.
- Otherwise the quantified cast succeeds.

This boundary check is performed once, at quantified exit, not scattered across unrelated rules.

### Abstract Type Variables And Sealed Dynamic Values

The abstract-type rules are intentionally strict.

#### Allowed cases

- `X -> Dyn` succeeds and produces a sealed dynamic value grounded in `X`.
- `SealedDyn(X) -> X` succeeds.

#### Rejected cases

- `SealedDyn(Y) -> X` fails when `X` and `Y` differ.
- `Dyn -> X` fails.
- Placeholder-to-`X` fails.
- Any ordinary concrete, structural, or leaf source to `X` fails.
- `X -> non-Dyn` fails unless some future rule explicitly introduces a legal path.
- Any other remaining sealed-dynamic mismatch fails.

This is the main place where the algorithm follows the `Blame for All` discipline: instantiation uses dynamic, but re-entry to an abstract variable is allowed only through a matching seal.

### Dynamic Type Tests

There is one cast-aware type-test rule related to `Blame for All`:

- Testing a sealed dynamic value against a ground type does not return ordinary success or ordinary mismatch.
- It fails with global blame for inspecting a sealed value.
- Testing any non-sealed value succeeds and records whether the normalized types match exactly.

This rule is separate from the cast relation, but it is part of the same semantic contract and must be preserved in the rewrite.

### NumericDyn

Broad numeric uncertainty is not modeled as a ground `Number` type. The checker
has a separate dyn-like type, `NumericDyn`, for "known numeric, but not proven
`Int` or proven non-`Int` numeric".

This distinction matters for leaf compatibility:

- `Int` may cast to `NumericDyn`.
- Fine numeric grounds such as `Double`, `Float`, `BigDecimal`, and `Ratio`
  may cast to `NumericDyn`.
- `NumericDyn` may cast to numeric leaves under the gradual rules, because it
  may still be `Int` or a fine non-`Int` numeric ground.
- Fine numeric grounds remain distinct ground types. The checker must not lump
  them into a synthetic broad `Number` ground.

### Unions

Union behavior depends on which side is a union.

#### Source union

If the source is a union:

- Cast every source member to the full target.
- Every branch must succeed.
- If any branch fails, the union cast fails.

This branch takes precedence when both source and target are unions.

#### Target union

If the target is a union and the source is not:

- Cast the source to every target member.
- If any branch succeeds, the union cast succeeds.
- If no branch succeeds, the union cast fails.

The success case still retains the full set of attempted branches so reporting can explain why the other alternatives did not match.

### Intersections

Intersection behavior is also side-dependent.

#### Target intersection

If the target is an intersection:

- Cast the source to every target member.
- Every target member must accept.

This branch takes precedence when both sides are intersections.

#### Source intersection

If the source is an intersection and the target is not:

- Cast every source member to the target.
- Every source member must succeed.

### Conditional Types

Conditional casting is currently union-like, but it preserves the `ConditionalT`
branch structure until dispatch.

#### Source conditional

If the source is conditional:

- Cast every conditional branch type to the full target.
- Every branch must succeed.
- The aggregate rule reported today is `:source-union`.

#### Target conditional

If the target is conditional and the source is not:

- Cast the source to every conditional branch type.
- If any branch succeeds, the conditional cast succeeds.
- If no branch succeeds, it fails with the same no-branch shape used by target
  unions.
- The aggregate rule reported today is `:target-union`.

### Nullable Types

Nullable casting has three cases.

- Nullable source to nullable target: cast the inner types.
- Exact `nil` value to nullable target: succeed immediately.
- Non-nullable source to nullable target: cast the source to the target’s inner type.
- Nullable source to non-nullable target: fail immediately with a nullable-source reason.

`s/maybe` and the exact singleton `(eq nil)` are both nullable-shaped targets (the latter is a value type for `nil`, not a `MaybeT`). The same rules apply once the target is recognized as nullable: for example, a `Maybe` source to `(eq nil)` checks the maybe’s inner against the singleton nil type (rule `:maybe-to-eq-nil` when that branch applies).

### Transparent Wrappers

Optional map-key wrappers and var wrappers are transparent to casting.

- If the source is wrapped, unwrap the source and continue.
- Otherwise, if the target is wrapped, unwrap the target and continue.

Only one layer is removed per step; recursion handles deeper nesting.

### Functions

Function casting is structural and method-based.

- The target function determines the required methods.
- For each target method, find a source method that accepts the same arity, allowing variadic source methods to satisfy larger arities.
- If no such source method exists, that method fails with an arity mismatch.
- Otherwise:
  - Cast each target parameter type to the corresponding source parameter type using flipped polarity.
  - Cast the source return type to the target return type using the current polarity.
- A method succeeds only if every domain and range child succeeds.
- The full function cast succeeds only if every target method succeeds.

In other words, the domain is contravariant and the range is covariant.

### Maps

Map casting is structural and candidate-based.

#### Map descriptors

Each map is first reorganized into a descriptor with three parts:

- required exact keys
- optional exact keys
- domain entries for non-exact keys

If a key type is a finite union of exact values, it is expanded into separate exact entries before further checking.

#### Exact target keys

For each exact key required by the target descriptor:

- Gather every source entry that could supply that exact key:
  - the exact source entry for that key, if present
  - otherwise every source domain entry whose key domain includes that exact key
- If no source candidate exists:
  - required target key fails as missing
  - optional target key contributes nothing
- If candidates exist:
  - cast every candidate value to the target value type
  - every candidate must succeed
- If the target key is required but the matching exact source key is optional, also record a nullable-key failure

The “every candidate must succeed” rule matters. If multiple source entries could provide the same exact key, all of them must be compatible, because the target expects that key unconditionally.

#### Extra exact source keys

For exact source keys that are not already paired with exact target keys:

- Find target domain entries whose key domain fully covers that exact key.
- If none exist, fail as an unexpected key.
- If candidates exist, cast the source value to each candidate target value type.
- Succeed if any candidate succeeds.

This is existential, not universal, because the source key only needs one compatible target domain slot.

#### Source domain entries

For each source domain entry:

- If the source key domain is a union, split it into separate source-domain branches.
- For each branch, find target domain entries whose key domains fully cover that branch.
- If none exist, fail with key-domain-not-covered.
- If candidates exist, cast the source value to each candidate target value type.
- Succeed if any candidate succeeds.

#### Map result

Concatenate all child results from the three phases above. The map cast succeeds only if every child succeeds.

### Vectors

Vector casting compares slots positionally, but it allows homogeneous expansion.

- If both vectors already have the same number of stored slots, compare them one-for-one.
- If the target is homogeneous with a single stored slot, repeat that target slot until it matches the source slot count.
- If the source is homogeneous with a single stored slot, repeat that source slot until it matches the target slot count.
- Otherwise fail with a vector arity mismatch.

After slot expansion, cast every aligned pair. The vector cast succeeds only if every slot cast succeeds.

### Sequences

Sequence-to-sequence casting is stricter than vector casting.

- The stored item counts must match exactly.
- Items are compared one-for-one.
- No homogeneous expansion is performed here.

The sequence cast succeeds only if every item cast succeeds.

### Sequence/Vector Cross Casts

Sequence-to-vector and vector-to-sequence casts reuse the same slot-count rules as vectors:

- equal stored slot counts are allowed
- single-slot homogeneous expansion is allowed on either side
- otherwise the cast fails with an arity mismatch

After alignment, every position must succeed.

### Sets

Set casting is cardinality-sensitive.

- The source and target sets must contain the same number of members.
- For each source member, try casting it to every target member.
- If any target member accepts, keep one successful witness for that source member.
- If no target member accepts, record an element mismatch for that source member.

The set cast succeeds only if every source member found a target witness.

### Leaf Types

Leaf casting handles everything that is not structural after the earlier rules have been exhausted.

#### Exact values

- If the source is an exact value type, succeed only when that runtime value satisfies the target type.
- If the target is an exact value type, succeed only when that runtime value is accepted by the source type.

#### Residual dynamic values

- Dynamic source succeeds against any remaining target.
- Placeholder source succeeds against any remaining target.
- Infinite-cycle source succeeds against any remaining target.

This is the residual dynamic case that remains after the stricter abstract-variable rules.

#### Ground, refinement, and adapter leaves

- Ground types compare by the leaf-overlap relation.
- Refinements compare through their base types.
- Adapter leaves are treated as overlapping at this stage.

If overlap holds, the cast succeeds. Otherwise it fails as a leaf mismatch.

#### Functions against predicate-like leaf types

- A function value may satisfy an adapter leaf only when that adapter accepts a function witness.
- Otherwise the cast fails.

#### Final fallback

Anything not accepted by the cases above fails.

### Paths And Failure Leaves

Structural child casts attach path segments:

- map key paths for exact visible keys
- vector and sequence indexes
- set members
- function parameter indexes and function range
- union and intersection branch indexes
- nullable inner values

Higher layers rely on those paths to render precise input and output mismatch reports. A rewrite must preserve both the visible path segments and the rule/reason information on the failure leaves.

## Public API Boundary Used Outside The Cast Subtree

These are the only public functions inside the `cast` subtree that production code outside that subtree currently calls.

### `skeptic.analysis.cast/check-cast`

Callers outside the subtree:

- `skeptic.inconsistence.report`
- `skeptic.analysis.map-ops` via `requiring-resolve`
- `skeptic.analysis.value-check` via `requiring-resolve`
- `skeptic.analysis.schema.cast` wraps it at the schema boundary

Arities:

- `(check-cast source-type target-type)`
- `(check-cast source-type target-type opts)`

Inputs:

- `source-type`: any type-like value accepted by the current normalization layer
- `target-type`: any type-like value accepted by the current normalization layer
- `opts`: optional map

Current external contract of `opts`:

- `:polarity`, default `:positive`
- additional keys are tolerated and propagated through recursive checks; current production callers outside the subtree do not rely on any other key

Outputs:

- always returns a cast-result map
- base keys always present:
  - `:ok?`
  - `:blame-side`
  - `:blame-polarity`
  - `:rule`
  - `:source-type`
  - `:target-type`
  - `:children`
  - `:reason`
- `:children` is always a vector of cast-result maps
- success returns `:ok? true`, `:blame-side :none`, `:blame-polarity :none`
- failure returns `:ok? false` and a non-`nil` `:reason`
- rule-specific detail keys may also be present. Current details used or produced in the tree include:
  - `:path`
  - `:binder`
  - `:instantiated-type`
  - `:sealed-type`
  - `:chosen-rule`
  - `:matches?`
  - `:expected-key`
  - `:actual-key`
  - `:target-method`
  - `:source-key-domain`

The rewrite must keep the function name, both arities, default polarity behavior, and the result-tree shape.

### `skeptic.analysis.cast.support/optional-key-inner`

Callers outside the subtree:

- `skeptic.analysis.map-ops`
- `skeptic.analysis.value-check`

Arity:

- `(optional-key-inner type)`

Input:

- `type`: a semantic type value

Output:

- if `type` is an optional-key wrapper, return its inner type
- otherwise return `type` unchanged

The current function does not normalize the input itself. External callers currently pass semantic type values.

### `skeptic.analysis.cast.support/with-cast-path`

Callers outside the subtree:

- `skeptic.analysis.value-check`

Arity:

- `(with-cast-path result segment)`

Inputs:

- `result`: a cast-result map
- `segment`: a visible path segment such as `{:kind :map-key :key k}`

Output:

- returns the cast-result map with `segment` appended to `:path` when `segment`
  is present

### `skeptic.analysis.cast.result`

Callers outside the subtree:

- `skeptic.inconsistence.report`
- `skeptic.analysis.map-ops` via `requiring-resolve`
- `skeptic.analysis.value-check` via `requiring-resolve`
- `skeptic.analysis.schema.cast`

Live public functions:

- `ok?`: boolean projection over `:ok?`
- `root-summary`: root-level report summary containing success, rule, blame, actual, and expected type data
- `leaf-diagnostics`: flattened failure diagnostics with accumulated visible paths
- `primary-diagnostic`: first failure diagnostic, falling back to the root on success or root-only results

## Record Of Non-Unit Tests That Exercise The Cast Subtree

This section excludes direct unit coverage under `skeptic/test/skeptic/analysis/cast/`
except where those tests pin cast/report boundary behavior that downstream
annotation and checking rely on.

### Direct Or Near-Direct Boundary Coverage

#### `skeptic/test/skeptic/analysis/schema/cast_test.clj`

- `raw-schema-check-cast-adapts-to-type-domain-test`
  - verifies the schema-facing wrapper preserves exact casts, target-dynamic casts, and structural map casts

#### `skeptic/test/skeptic/analysis/annotate/integration_test.clj`

- `annotate-form-loop-integration-test`
  - verifies ordinary annotated calls carry actual and expected argument types
  - verifies annotation stays first-order and introduces no quantified, abstract-variable, or sealed-dynamic types
- `integration-preserves-local-invocation`
  - verifies a narrowed argument type produced by analysis is cast-compatible with the callee’s expected type

#### `skeptic/test/skeptic/analysis/annotate/typed_flow_test.clj`

- `typed-flow-through-let-and-if-test`
  - verifies type information flows through local binding and conditional annotation
- `typed-function-flow-test`
  - verifies functions compute parameter specs, arglists, and output types consumed by checking

#### `skeptic/test/skeptic/analysis/annotate/match_test.clj`

- `case-conditional-narrow-for-lits-empty-picks-uses-anchor-test`
  - verifies failed conditional case branch picking produces bottom with the anchor provenance
- `case-conditional-default-narrow-empty-default-uses-anchor-test`
  - verifies exhausted conditional defaults produce bottom with the anchor provenance

#### `skeptic/test/skeptic/analysis/annotate/structural_test.clj`

- `structural-throw-try-and-loop-test`
  - loop bodies and recur sites carry the expected result and bottom types
- `structural-literal-collections-test`
  - literal vectors, maps, and sets preserve the collection shapes later checked by casts

### External Consumers That Depend On Cast Semantics

#### `skeptic/test/skeptic/analysis/map_ops_test.clj`

- `map-lookup-and-map-get-type-regression-test`
  - exact-key lookup prefers the exact entry
  - domain-key lookup joins exact and broad-key candidate values
- `semantic-map-query-regression-test`
  - broad-key queries return both exact and domain candidates

These tests constrain the map-cast rewrite indirectly because map candidate selection is shared between lookup behavior and map casting.

#### `skeptic/test/skeptic/analysis/value_check_test.clj`

- `contains-key-type-classification-regression-test`
  - broad-key maps are classified as `:unknown`
  - explicit required keys classify as `:always`
- `semantic-value-satisfies-type-regression-test`
  - vector values satisfy homogeneous vector targets
  - vector values satisfy equal-length fixed tuples
  - vector values do not satisfy the wrong tuple arity

These tests constrain exact-key handling, optional-key handling, and tuple/homogeneous collection distinctions used by casting.

### Reporting And Message-Level Coverage

#### `skeptic/test/skeptic/inconsistence/report_test.clj`

- `cast-report-basic-failures-test`
  - exact success
  - nullable-source failure metadata
  - leaf mismatch metadata
- `output-cast-report-renders-canonical-output-test`
  - output mismatch reports render canonical actual and expected map types
- `nested-output-cast-report-includes-summary-and-path-details-test`
  - nested map mismatches produce visible map-key paths
- `output-summary-declared-type-shows-full-conditional-union`
  - output summaries retain the full target union in declared-type text
- `output-report-summary-uses-root-expected-type-metadata`
  - output summary metadata keeps the root expected type
- `output-summary-uses-visible-path-as-headline-focus`
  - visible structural paths become the headline focus
- `output-summary-omits-redundant-in-when-focus-equals-expression`
  - output summaries suppress redundant self-context
- `output-summary-falls-back-to-top-level-when-no-actionable-leaf-details`
  - output summaries fall back to top-level mismatch text when there is no better visible leaf
- `nested-dynamic-map-cast-stays-structural-test`
  - nested dynamic map casts remain structural rather than collapsing into a top-level dynamic success
  - incompatible nested maps report both missing and unexpected keys
- `broad-key-map-cast-regression-test`
  - source exact keys must be covered by target broad-key domains
  - broad-key structural map cast still succeeds in the compatible direction
- `input-summary-uses-single-focused-arg`
  - single focused nullable-source failures use the focused argument
- `input-summary-uses-blame-for-multiple-focused-args`
  - grouped input failures fall back to the whole blame expression
- `semantic-tamper-message-test`
  - sealed inspection renders an inspect-a-sealed-value message
  - quantified escape renders a move-a-sealed-value-out-of-scope message
- `union-like-output-cast-report-test`
  - conditional/either-style outputs cast to `Int` and `Str` but not `Keyword`
- `both-schema-output-cast-report-test`
  - intersection-like outputs accept only targets supported by every component
  - nested map output still rejects incompatible intersections
- `constrained-and-eq-compatibility-test`
  - unconstrained base types can cast to compatible constrained targets
  - incompatible base types and incompatible exact values do not
  - exact-value targets accept matching base types and reject incompatible ones
- `enum-compatibility-test`
  - enums cast to compatible member base types
  - mixed enums cast to each covered base type but not uncovered ones
  - enum-to-base and base-to-enum directionality is preserved

#### `skeptic/test/skeptic/analysis/cast/result_test.clj`

- `leaf-diagnostics-projects-failed-source-conditional-at-aggregate-level`
  - source conditional failures project at the aggregate source-union level
- `conditional-types-work-under-maybe-cast-test`
  - conditional types still cast correctly when nested under maybe

### End-To-End Pipeline Coverage

The pipeline tests exercise casts through full analysis, checking, blame selection, and report assembly. They are not unit tests of casting, but they constrain the behavior that a rewrite must preserve.

#### General success/failure flow

`skeptic/test/skeptic/checking/pipeline/basics_test.clj`

- `annotated-input-ground-type-mismatch`
  - ground input mismatches are reported from annotated call metadata
- `failing-functions`
  - nil, ground, recur, multi-arity, and parametric-function mismatches remain reported
- `fn-chain-type-errors`
  - helper-return mismatch is reported at the caller boundary

#### Loop, sequence, vector, and map shape coverage

`skeptic/test/skeptic/checking/pipeline/control_flow_test.clj`

- `loop-return-matches-declared-vector-and-map-schemas`
  - loop outputs satisfy declared vector and nested map schemas
- `for-declared-int-seq-output-must-type-check`
  - homogeneous integer sequence output succeeds
- `for-declared-str-seq-output-fails-when-body-is-int-seq`
  - sequence element mismatch fails
- `for-even-str-odd-int-declared-int-seq-fails`
  - mixed branch result fails against pure integer sequence target
- `for-even-str-odd-int-declared-str-seq-fails`
  - mixed branch result fails against pure string sequence target
- `for-even-str-odd-int-declared-cond-pre-seq-succeeds`
  - the same mixed branches succeed when the target is the matching conditional union
- `sum-type-exhaustive-branches`
  - closed-sum exhaustive branches avoid joining unreachable defaults before output casting

`skeptic/test/skeptic/checking/pipeline/collections_test.clj`

- `abcde-maps-output-type-errors`
  - nested vector-of-map output mismatch is preserved structurally
- `nested-call-mismatch-renders-field-paths`
  - input mismatch paths for nested map fields are preserved
- `vector-call-mismatch-renders-index-paths`
  - input mismatch paths for vector indexes are preserved
- `vector-literal-tuples-derive-homogeneous-views-at-check-boundary`
  - vector literal tuple and homogeneous views remain distinguished at the cast boundary
- `printer-path-renders-only-user-facing-data`
  - path rendering remains user-facing rather than UI-internal

#### Input/output reporting, paths, and metadata

`skeptic/test/skeptic/checking/pipeline/check_ns_phase_test.clj`

- `static-call-examples-check-ns`
  - end-to-end output mismatch messages for map rebuilds and nested helper calls
- `symbol-output-annotation-regression`
  - symbol-based output annotations still feed the same output-cast path
- `collect-annotations-output-annotation-regression`
  - collected output annotations still feed the same output-cast path
- `examples-maybe-multi-step-check-ns`
  - flat and nested maybe failures are preserved, and matching maybe successes remain accepted

`skeptic/test/skeptic/checking/pipeline/reporting_phase_test.clj`

- `output-mismatch-renders-canonical-map-types`
  - end-to-end canonical map rendering in output mismatches
- `output-summary-highlights-path-or-drops-redundant-self-context`
  - end-to-end output summaries prefer leaf paths and suppress redundant context
- `nested-output-mismatch-renders-field-paths`
  - end-to-end nested map output mismatch produces `[:user :name]`
- `check-results-carry-cast-metadata`
  - reports retain cast rule, actual/expected types, root cast result, leaf cast results, blame side, blame polarity, location, and focus sources

`skeptic/test/skeptic/checking/pipeline/resolution_test.clj`

- `resolved-helper-failures-use-final-reduced-types`
  - mismatches are reported on the final reduced helper type, not an intermediate shape
- `check-s-expr-uses-resolved-helper-types`
  - the same reduced-helper behavior is preserved in single-expression checking
- `resolution-path-resolutions`
  - checked-call analysis preserves resolution context while executing cast compatibility checks
- `shadow-provenance-uses-param-binding`
  - shadowed locals preserve context while executing cast compatibility checks
- `call-mismatch-summary-uses-single-focused-input`
  - focused single-argument summaries use the argument rather than the whole call

#### Wrapper and annotation boundary coverage

`skeptic/test/skeptic/checking/pipeline/basics_test.clj`

- `annotated-wrapper-regression`
  - annotated wrapper forms still surface the right blame expression
- `checking-annotated-wrapper-regression`
  - annotated input/output wrappers and constrained outputs still check correctly

#### Maybe, union, intersection, and conditional-contract coverage

`skeptic/test/skeptic/checking/pipeline/nullability_test.clj`

- `when-not-blank-maybe-str`
  - maybe-string narrowing through `when-not-blank` remains accepted

`skeptic/test/skeptic/checking/pipeline/contracts_test.clj`

- `checking-conditional-input-contracts`
  - conditional/either/if-style input unions accept the covered branches and reject uncovered ones
- `checking-conditional-output-contracts`
  - the same conditional and intersection behavior holds on output checking
- `conditional-contract-contains-key-refinement`
  - `contains?`-style refinement can narrow to exact map shapes without over-refining optional broad-key presence
- `conditional-contract-cond-thread-output-construction`
  - conditional construction of map outputs preserves the accepted joined result
- `nested-conditional-contract-cond-thread`
  - nested conditional map construction preserves outer and inner branch routing
- `handles-ab-case-routing`
  - case routing across `:a`, `:b`, and combined `:ab` shapes remains accepted

`skeptic/test/skeptic/checking/pipeline/control_flow_test.clj`

- `cond-three-branch-join-output`
  - three-branch joins preserve the resulting union output
