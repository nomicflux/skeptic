# Skeptic Workspace Guidance

## Read First

Before changing the cast engine or related type-analysis code, read `docs/blame-for-all.md`.
Use it as the high-level algorithm reference for the library's core cast and blame behavior.

## Project Setup

1. This is `leiningen` project for a Clojure app
2. Tests are run via `lein test`
3. The plugin can be run via `lein skeptic`, and is in ../lein-skeptic
4. Linting is performed via `clj-kondo --lint <dir>`

## Namespace Map

Primary execution path:

- `leiningen.skeptic`:
  Leiningen plugin entrypoint. Parses CLI flags, selects the Skeptic profile, and runs the checker in the target project.
- `skeptic.profiling`:
  Optional profiling wrapper around a run. Used by the plugin before invoking the main checker.
- `skeptic.core`:
  Top-level checker/report printer. Discovers namespaces from source paths, invokes namespace checking, and formats user-facing output.
- `skeptic.checking.pipeline`:
  Main per-namespace pipeline. Reads forms, annotates analyzer ASTs, compares inferred vs declared types, and emits mismatch/exception results.

Source namespace families:

- `skeptic.checking.*`:
  Checking-time orchestration and presentation of analyzed forms.
  `skeptic.checking.ast` extracts call/local context from annotated ASTs.
  `skeptic.checking.form` normalizes source forms and source-location metadata.
  `skeptic.checking` is currently a marker namespace.
- `skeptic.schema`, `skeptic.schema.collect`, `skeptic.typed-decls`:
  Declaration admission pipeline.
  `skeptic.schema` defines the admitted schema description shape.
  `skeptic.schema.collect` reads var metadata and admits raw Plumatic schema declarations.
  `skeptic.typed-decls` converts admitted schema descriptions into typed entries via `schema->type`.
- `skeptic.analysis.bridge` and `skeptic.analysis.bridge.*`:
  Schema-domain to type-domain boundary.
  `skeptic.analysis.bridge` imports schemas into semantic types.
  `skeptic.analysis.bridge.canonicalize` normalizes schema forms.
  `skeptic.analysis.bridge.localize` resolves schema values into the current project context and carries localized error context.
  `skeptic.analysis.bridge.render` renders semantic types for reports and strips derived display-only fields.
  `skeptic.analysis.bridge.algebra` contains schema-boundary set/join helpers.
- `skeptic.analysis.types`, `skeptic.analysis.type-ops`, `skeptic.analysis.type-algebra`, `skeptic.analysis.normalize`:
  Core type-domain representation and normalization.
  `skeptic.analysis.types` is the canonical semantic type data model.
  `skeptic.analysis.type-ops` builds, normalizes, unions, intersects, and de-`maybe`s types.
  `skeptic.analysis.type-algebra` holds extra type-combination helpers.
  `skeptic.analysis.normalize` normalizes typed declaration entries before analysis.
- `skeptic.analysis.annotate` and `skeptic.analysis.annotate.*`:
  Analyzer AST typing/inference.
  `skeptic.analysis.annotate` dispatches on analyzer `:op` values.
  `skeptic.analysis.annotate.api` is the public API for the annotate subsystem — node accessors (`node-form`, `node-type`, `node-op`, ...) and mutators (`with-type`) used by any code that does not own node shape.
  Sub-namespaces split the work by AST area: `base`, `control`, `data`, `fn`, `invoke`, `invoke-output`, `jvm`, `match`, `numeric`, `coll`, and `map-path`.
- `skeptic.analysis.calls`, `skeptic.analysis.native-fns`, `skeptic.analysis.value`, `skeptic.analysis.value-check`, `skeptic.analysis.map-ops`, `skeptic.analysis.map-ops.algebra`, `skeptic.analysis.narrowing`, `skeptic.analysis.origin`, `skeptic.analysis.ast-children`, `skeptic.analysis.annotation`:
  Analysis helpers used by annotation and checking.
  Call resolution and callable metadata live in `calls` and `native-fns`.
  Runtime-value typing and compatibility checks live in `value`, `value-check`, `map-ops`, and `map-ops.algebra`.
  Flow-sensitive refinements live in `narrowing`.
  Provenance tracking lives in `origin`.
  AST traversal/indexing helpers live in `ast-children` and `annotation`.
- `skeptic.analysis.cast`, `skeptic.analysis.cast.kernel`, `skeptic.analysis.cast.map`, `skeptic.analysis.cast.support`:
  Core cast engine in the type domain.
  `cast` is the dispatcher.
  `cast.kernel` holds the general cast rules.
  `cast.map` handles map-specific casting.
  `cast.support` carries cast-result structures, blame/path helpers, and common utilities.
- `skeptic.analysis.schema-base`, `skeptic.analysis.schema.cast`, `skeptic.analysis.schema.map-ops`, `skeptic.analysis.schema.value-check`, `skeptic.analysis.schema.valued`:
  Legacy or boundary-facing schema-domain helpers.
  Keep new semantic work in the type domain unless an external schema-facing API specifically requires schema forms.
- `skeptic.inconsistence.*`:
  Mismatch reporting.
  `report` builds summaries from cast results.
  `path` computes visible blame paths and detail lines.
  `display` renders types/schemas for users.
  `mismatch` contains older mismatch message helpers.
- `skeptic.output`, `skeptic.output.text`, `skeptic.output.porcelain`:
  User-facing output layer.
  `skeptic.output` selects a printer based on opts.
  `skeptic.output.text` renders the human-readable, ANSI-coloured report that `lein skeptic` has always produced.
  `skeptic.output.porcelain` renders newline-delimited JSON (one object per finding) for `lein skeptic -p`.
- `skeptic.file`, `skeptic.source`, `skeptic.colours`, `skeptic.utils`, `skeptic.type-vars`:
  General support namespaces for file reading, source lookup, terminal formatting, schema-desc merging, and simple type-var values.
- `skeptic.examples`, `skeptic.static-call-examples`:
  Example/demo namespaces used for exercising analysis behavior.
- `skeptic.analysis`, `skeptic.checking`, `skeptic.core-fns`:
  Currently minimal marker namespaces, not active orchestration points.

Tests:

- `skeptic/test/...` mirrors the source namespace layout.
  Most tests use the same namespace family plus a `-test` suffix, so the nearest test is usually in the matching subtree.

## Core Rules

1. No re-exports.
   Define functions in the namespace that owns them and require that namespace directly at call sites or in tests.

2. No `declare` unless it is strictly required for real mutual recursion.
   If recursion is needed, keep the actual recursive runner small and pass it into non-recursive helpers so the recursion stays contained.

3. The main internal language of the library is the type domain.
   Plumatic schemas are an input format for type information, not the primary working representation.

4. Prefer `schema->type`, not the reverse direction.
   Convert schemas into semantic types at the boundary, do the real analysis in the type domain, and only deal in raw schema forms when interacting with external schema-facing APIs.

## API Boundaries

Some subsystems expose a dedicated API module that external callers must go through instead of reaching into implementation files:

- `skeptic.analysis.annotate.api` is the API for the annotate subsystem. Code that reads or writes fields on annotated nodes must use the accessors and mutators defined there (e.g. `node-form`, `node-type`, `with-type`). Direct `(:form node)`, `(:type node)`, `(assoc node :type ...)` are permitted only inside `annotate/*` files that own node shape (the per-AST-op annotators).
- `skeptic.analysis.bridge` is the entry point for schema→type conversion (`schema->type`). Schema-domain predicates and canonicalization live in `skeptic.analysis.bridge.canonicalize`; per rule 1 these siblings are required directly, they are not "internals of bridge."
- `skeptic.analysis.cast` is the cast dispatcher. Cast-result construction and blame/path helpers live in `skeptic.analysis.cast.support`. Again these are documented siblings, not cast internals.

When an existing API module lacks a helper that a new consumer needs, extend the API module rather than reaching past it. The purpose of the boundary is to keep node shape (and analogous cast-result shape) changeable in one place.

## Recursive Runner Pattern

When recursive analysis is needed, structure it in two layers:

- Recursive runner:
  One small function owns recursion, dispatch order, and recursive descent. It decides when to recurse and what child work to run next.
- Non-recursive helpers:
  Helper functions do not call each other recursively. They take plain data plus the recursive runner as an argument, build child requests, run the provided runner on those requests, and aggregate the results.

Use this pattern to avoid large mutually recursive clusters.

The goal is to keep recursion explicit, local, and easy to reason about.

## Type Domain vs Schema Domain

The library has two different representations that must not be confused.

### Schema Domain

The schema domain is the external Plumatic Schema representation.
Examples include:

- `s/Int`
- `s/Any`
- `s/maybe`
- map schemas such as `{s/Keyword s/Int}`
- optional keys and other raw schema forms

This domain exists because users write schemas and external APIs provide schemas.
It is the input format at the boundary.

### Type Domain

The type domain is the library's internal semantic representation of types.
This is the domain used for real analysis and cast reasoning.
In this codebase, those semantic types live in `src/skeptic/analysis/types.clj`.
The type domain is not an abstract idea here; it is the concrete family of internal values built and recognized by helpers such as:

- constructors:
  `at/->DynT`, `at/->BottomT`, `at/->GroundT`, `at/->RefinementT`, `at/->AdapterLeafT`, `at/->OptionalKeyT`, `at/->FnMethodT`, `at/->FunT`, `at/->MaybeT`, `at/->UnionT`, `at/->IntersectionT`, `at/->MapT`, `at/->VectorT`, `at/->SetT`, `at/->SeqT`, `at/->VarT`, `at/->PlaceholderT`, `at/->ValueT`, `at/->TypeVarT`, `at/->ForallT`, and `at/->SealedDynT`
- predicates:
  `at/dyn-type?`, `at/bottom-type?`, `at/ground-type?`, `at/refinement-type?`, `at/adapter-leaf-type?`, `at/optional-key-type?`, `at/fn-method-type?`, `at/fun-type?`, `at/maybe-type?`, `at/union-type?`, `at/intersection-type?`, `at/map-type?`, `at/vector-type?`, `at/set-type?`, `at/seq-type?`, `at/var-type?`, `at/placeholder-type?`, `at/value-type?`, `at/type-var-type?`, `at/forall-type?`, and `at/sealed-dyn-type?`

This is the domain where the cast engine, blame logic, compatibility checks, and most internal reasoning should happen.

Concretely, when code is doing real semantic work, it should usually be operating on values shaped like:

- `at/->GroundT :int 'Int`
- `at/->ValueT inner value`
- `at/->FunT [...]`
- `at/->MapT {...}`
- `at/->UnionT members`
- `at/->ForallT binder body`
- `at/->SealedDynT ground`

and branching with the corresponding `at/*-type?` predicates.

## Boundary Rule

Convert from schema domain into type domain at the boundary with `schema->type`.
In this codebase that means `skeptic.analysis.bridge/schema->type`.

Preferred flow:

- receive schema-like input
- canonicalize if needed
- convert with `ab/schema->type`
- do the real work in the type domain
- only return to schema-level forms when an external schema-facing API specifically requires it

Avoid flows that treat raw schemas as the main analysis language or that reconstruct schemas just to continue internal reasoning.

In short:

- schema domain is for input/output boundaries
- type domain is for internal semantics

## Design Bias

- Keep cast and analysis helpers small, focused, and type-domain-first.
- Preserve ordered dispatch when branch priority is semantically meaningful.
- Treat schema-level helpers as boundary adapters, not as the core analysis model.
