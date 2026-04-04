# Skeptic Workspace Guidance

## Read First

Before changing the cast engine or related type-analysis code, read `docs/blame-for-all.md`.
Use it as the high-level algorithm reference for the library's core cast and blame behavior.

## Project Setup

1. This is `leiningen` project for a Clojure app
2. Tests are run via `lein test`
3. The plugin can be run via `lein skeptic`, and is in ../lein-skeptic
4. Linting is performed via `clj-kondo --lint <dir>`

## Core Rules

1. No re-exports.
   Define functions in the namespace that owns them and require that namespace directly at call sites or in tests.

2. No `declare` unless it is strictly required for real mutual recursion.
   If recursion is needed, keep the actual recursive runner small and pass it into non-recursive helpers so the recursion stays contained.

3. The main internal language of the library is the type domain.
   Plumatic schemas are an input format for type information, not the primary working representation.

4. Prefer `schema->type`, not the reverse direction.
   Convert schemas into semantic types at the boundary, do the real analysis in the type domain, and only deal in raw schema forms when interacting with external schema-facing APIs.

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
