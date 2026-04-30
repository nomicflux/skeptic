# Skeptic Workspace Guidance

## Read First

Before changing the cast engine or related type-analysis code, read `docs/blame-for-all.md`.
Use it as the high-level algorithm reference for the library's core cast and blame behavior.

## Deliverable matching (agents)

When implementing or answering from this doc, **match the requested artifact and audience**. Do not substitute a different deliverable and treat it as equivalent.

Examples:
- **Audience:** Material for maintainers (credentials layout, release mechanics, internal rationale) belongs in **`AGENTS.md`**, `docs/`, workflow comments, or other non–end-user surfaces—not in the root **`README.md`**, which is for people consuming Skeptic as a tool.
- **Fidelity:** If the request is a concrete workflow, file change, or behavior spec, implement **that** (or say what blocks you and ask one focused question)—do not replace it with a shortened story plus “human discipline” or process prose.
- **Navigation / “where in UI”:** If the request is how to reach a page in GitHub or another product, the answer is an **ordered list of exact UI labels** for every hop (e.g. Settings → Security → …). Omitting a parent step produces a **wrong** path, not a summary.
- **If the real answer is “I cannot without X”** (missing access, ambiguous scope), say so or ask—**do not** ship a different-shaped answer and imply it satisfies the ask.

## Project Setup

1. This is `leiningen` project for a Clojure app
2. Tests are run via `lein test`
3. The plugin lives in `../lein-skeptic` and is published as **`org.clojars.nomicflux/lein-skeptic`** on Clojars. For local development without Clojars, run `../script/install-local.sh` (cleans the local m2 cache for skeptic and `lein install`s both `skeptic` and `lein-skeptic`), then `lein with-profile +skeptic-plugin skeptic -p` from this folder (the `:skeptic-plugin` profile pins **`org.clojars.nomicflux/lein-skeptic`** to match that install). **Always use `-p` / `--porcelain`** — the default ANSI text report is for humans; agents must consume JSONL findings, never grep coloured output.
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
  `skeptic.typed-decls.malli` is the sibling converter for admitted malli-spec descriptions via `malli-spec->type`.
- `skeptic.malli-spec`, `skeptic.malli-spec.collect`, `skeptic.analysis.malli-spec.bridge`:
  MalliSpec-domain admission and boundary.
  `skeptic.malli-spec` defines the admitted `MalliSpecDesc` shape and uses `:malli-spec` everywhere; it never carries `:schema`, which is reserved for Plumatic.
  `skeptic.malli-spec.collect` walks `(ns-interns ns)`, reads `:malli/schema` var metadata (skipping macros and vars without it), and returns `{:entries {qualified-sym {:name ... :malli-spec ...}} :errors [...]}`.
  `skeptic.analysis.malli-spec.bridge` exposes `admit-malli-spec`, `malli-spec-domain?`, and `malli-spec->type`. `malli-spec->type` is a small recursive runner over the admitted Malli form: it handles the `[:=> [:cat & inputs] output]` callable shape (producing a `FunT`/`FnMethodT`), `[:maybe X]` (producing `MaybeT`), `[:or X Y ...]` (via `ato/union-type` over converted members), and `[:enum ...]` (via exact value types). Leaves route through a five-entry primitive table (`:int`, `:string`, `:keyword`, `:boolean`, `:any`) and recognized bare predicate symbols. Any other Malli form (e.g., `:map`, `:vector`, registry refs, sequence/regex combinators) returns `Dyn`.
- `skeptic.analysis.bridge` and `skeptic.analysis.bridge.*`:
  Schema-domain to type-domain boundary.
  `skeptic.analysis.bridge` imports schemas into semantic types.
  `skeptic.analysis.bridge.canonicalize` normalizes schema forms.
  `skeptic.analysis.bridge.localize` resolves schema values into the current project context and carries localized error context.
  `skeptic.analysis.bridge.render` renders semantic types for reports and strips derived display-only fields.
  `skeptic.analysis.bridge.algebra` contains schema-boundary set/join helpers.
- `skeptic.analysis.types`, `skeptic.analysis.types.schema`, `skeptic.analysis.types.proto`, `skeptic.analysis.type-ops`, `skeptic.analysis.type-algebra`:
  Core type-domain representation and normalization.
  `skeptic.analysis.types` is the canonical semantic type data model.
  `skeptic.analysis.types.schema` defines schema contracts for semantic types.
  `skeptic.analysis.types.proto` defines the protocol used to tag semantic type records.
  `skeptic.analysis.type-ops` builds, normalizes, unions, intersects, and de-`maybe`s types.
  `skeptic.analysis.type-algebra` holds extra type-combination helpers.
- `skeptic.analysis.annotate` and `skeptic.analysis.annotate.*`:
  Analyzer AST typing/inference.
  `skeptic.analysis.annotate` dispatches on analyzer `:op` values.
  `skeptic.analysis.annotate.api` is the public API for the annotate subsystem — node accessors (`node-form`, `node-type`, `node-op`, ...) and mutators (`with-type`) used by any code that does not own node shape.
  Sub-namespaces split the work by AST area: `base`, `control`, `data`, `fn`, `invoke`, `invoke-output`, `jvm`, `match`, `numeric`, `coll`, `map-path`, `map-projection`, and `shared-call`; `test-api` exists for test-facing helpers.
- `skeptic.analysis.calls`, `skeptic.analysis.native-fns`, `skeptic.analysis.predicates`, `skeptic.analysis.predicate-descriptor`, `skeptic.analysis.value`, `skeptic.analysis.value-check`, `skeptic.analysis.map-ops`, `skeptic.analysis.map-ops.algebra`, `skeptic.analysis.narrowing`, `skeptic.analysis.origin`, `skeptic.analysis.sum-types`, `skeptic.analysis.ast-children`, `skeptic.analysis.annotation`:
  Analysis helpers used by annotation and checking.
  Call resolution and callable metadata live in `calls`, `native-fns`, and `predicates`.
  Predicate descriptor parsing lives in `predicate-descriptor`.
  Runtime-value typing and compatibility checks live in `value`, `value-check`, `map-ops`, and `map-ops.algebra`.
  Flow-sensitive refinements live in `narrowing`.
  Assumption/origin tracking lives in `origin`.
  Closed-sum exhaustiveness helpers live in `sum-types`.
  AST traversal/indexing helpers live in `ast-children` and `annotation`.
- `skeptic.analysis.cast` and `skeptic.analysis.cast.*`:
  Core cast engine in the type domain.
  `cast` is the dispatcher.
  `cast.branch` handles union, intersection, conditional, maybe, and wrapper casts.
  `cast.collection` handles vector, seq, set, and leaf casts.
  `cast.function` handles function casts.
  `cast.map` handles map-specific casting.
  `cast.quantified` handles `forall`, type-var, and sealed-dyn casts.
  `cast.result` projects cast-result diagnostics.
  `cast.schema` defines cast-result schema contracts.
  `cast.support` constructs cast results and carries path helpers and common utilities.
- `skeptic.analysis.schema-base`, `skeptic.analysis.schema.cast`, `skeptic.analysis.schema.map-ops`, `skeptic.analysis.schema.value-check`, `skeptic.analysis.schema.valued`:
  Legacy or boundary-facing schema-domain helpers.
  Keep new semantic work in the type domain unless an external schema-facing API specifically requires schema forms.
- `skeptic.inconsistence.*`:
  Mismatch reporting.
  `report` builds summaries from cast results.
  `path` computes visible blame paths and detail lines.
  `display` renders types/schemas for users.
  `mismatch` contains older mismatch message helpers.
- `skeptic.output`, `skeptic.output.text`, `skeptic.output.porcelain`, `skeptic.output.serialize`:
  User-facing output layer.
  `skeptic.output` selects a printer based on opts.
  `skeptic.output.text` renders the human-readable, ANSI-coloured report that `lein skeptic` has always produced.
  `skeptic.output.porcelain` renders newline-delimited JSON (one object per finding) for `lein skeptic -p`.
  `skeptic.output.serialize` converts findings into JSON-ready data.
- `skeptic.file`, `skeptic.source`, `skeptic.colours`, `skeptic.type-vars`:
  General support namespaces for file reading, source lookup, terminal formatting, and simple type-var values.
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

5. The declaration dict holds bare Types. No sidecar data on dict values.
   Once admitted, each dict value is a Type, with no `:typings`, `:output-type`, `:arglists`, `:accessor-summary`, `:type`, or other wrappers. Each Type carries its own `:prov`, and declaration-level origin is also surfaced as a `Provenance` record in a parallel map.

## API Boundaries

Some subsystems expose a dedicated API module that external callers must go through instead of reaching into implementation files:

- `skeptic.analysis.annotate.api` is the API for the annotate subsystem. Code that reads or writes fields on annotated nodes must use the accessors and mutators defined there (e.g. `node-form`, `node-type`, `with-type`). Direct `(:form node)`, `(:type node)`, `(assoc node :type ...)` are permitted only inside `annotate/*` files that own node shape (the per-AST-op annotators).
- `skeptic.analysis.bridge` is the entry point for schema→type conversion (`schema->type`). Schema-domain predicates and canonicalization live in `skeptic.analysis.bridge.canonicalize`; per rule 1 these siblings are required directly, they are not "internals of bridge."
- `skeptic.analysis.cast` is the cast dispatcher. Cast-result construction and path helpers live in `skeptic.analysis.cast.support`; diagnostic projection lives in `skeptic.analysis.cast.result`. Again these are documented siblings, not cast internals.
- `skeptic.provenance` is the API for Provenance values carried alongside the declaration dict and attached to every Type. Consumers use `make-provenance`, `of`, `source`, `provenance?`, and `merge-provenances` — not the `Provenance` record constructor or raw `:source` field access. `prov/of` is strict: it throws if given a value without `:prov`, so any code that fabricates a Type without a real Provenance is rejected at the earliest read. There is no `prov/unknown` sentinel; every Type everywhere must carry a named-source Provenance.

When an existing API module lacks a helper that a new consumer needs, extend the API module rather than reaching past it. The purpose of the boundary is to keep node shape (and analogous cast-result shape) changeable in one place.

## Recursive Runner Pattern

When recursive analysis is needed, structure it in two layers:

- Recursive runner:
  One small function owns recursion, dispatch order, and recursive descent. It decides when to recurse and what child work to run next.
- Non-recursive helpers:
  Helper functions do not call each other recursively. They take plain data plus the recursive runner as an argument, build child requests, run the provided runner on those requests, and aggregate the results.

Use this pattern to avoid large mutually recursive clusters.

The goal is to keep recursion explicit, local, and easy to reason about.

## Domains

The library has three domains that must not be confused: Schema, MalliSpec, and Type.

Conversion is one-way into Type: `Schema → Type` (via `skeptic.analysis.bridge/schema->type`) and `MalliSpec → Type` (via `skeptic.analysis.malli-spec.bridge/malli-spec->type`). There is no `Type → Schema`, `Type → MalliSpec`, `Schema → MalliSpec`, or `MalliSpec → Schema`.

`:schema` as a keyword in this codebase means Plumatic Schema, always. Malli data at the admission boundary is carried as `:malli-spec` and currently read from `:malli/schema` var metadata only, so the Plumatic collector never sees it.

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

### MalliSpec Domain

The MalliSpec domain is the external [Malli](https://github.com/metosin/malli) schema representation — Malli vector/map/AST forms such as `:int`, `[:=> [:cat :int] :int]`, `[:map [:x :int]]`, `[:function ...]`. See `docs/malli-reference.md` for the forms Skeptic targets and what is currently stubbed.

The slice as it now stands:

- **Discovery:** `:malli/schema` var metadata only. Deferred: `malli.core/function-schemas` registry, `m/=>`, `malli.experimental/defn`.
- **Conversion (`malli-spec->type`):** admits via `m/form ∘ m/schema`; recursively converts the callable shape `[:=> [:cat & inputs] output]` into a `FunT` with one `FnMethodT`, `[:maybe X]` into `MaybeT` over the converted inner, `[:or X Y ...]` via `ato/union-type` over the converted members, and `[:enum ...]` via exact value types. Leaves support `:int → Int`, `:string → Str`, `:keyword → Keyword`, `:boolean → Bool`, `:any → Dyn`, and recognized bare predicate symbols. Every other Malli form (`:map`, `:vector`, registry refs, sequence/regex combinators, etc.) returns `Dyn`.
- **Strict separation at admission, merge at the pipeline boundary:** `typed-ns-results` returns Schema-derived entries plus `:skeptic/type-overrides`; the Malli collector (`skeptic.malli-spec.collect`) and Malli→Type bridge (`skeptic.analysis.malli-spec.bridge`) remain domain-pure standalone modules. Pipeline wiring happens in `skeptic.checking.pipeline/namespace-dict`, which combines per-namespace schema, malli, and native-fn entries through `merge-type-dicts [schema-result malli-result native-result]`; once inside the merged dict, Malli-admitted entries reach the analyzer in the same shape as schema entries.
- **Deferred:** registry-based discovery; unsupported Malli forms such as `:map`, `:vector`, registry refs, and sequence/regex combinators; multi-arity malli-spec admission; JSONL Malli kinds; `.skeptic/config.edn` Malli surface; `:skeptic/type` Malli interpretation; analyzer-side wiring of Malli-derived types as a separate type source.

### Type Domain

The type domain is the library's internal semantic representation of types.
This is the domain used for real analysis and cast reasoning.
In this codebase, those semantic types live in `src/skeptic/analysis/types.clj`.
The type domain is not an abstract idea here; it is the concrete family of internal values built and recognized by helpers such as:

- constructors:
  `at/->DynT`, `at/->BottomT`, `at/->GroundT`, `at/->NumericDynT`, `at/->RefinementT`, `at/->AdapterLeafT`, `at/->OptionalKeyT`, `at/->FnMethodT`, `at/->FunT`, `at/->MaybeT`, `at/->UnionT`, `at/->IntersectionT`, `at/->MapT`, `at/->VectorT`, `at/->SetT`, `at/->SeqT`, `at/->VarT`, `at/->PlaceholderT`, `at/->InfCycleT`, `at/->ValueT`, `at/->TypeVarT`, `at/->ForallT`, `at/->SealedDynT`, and `at/->ConditionalT`
- predicates:
  `at/dyn-type?`, `at/bottom-type?`, `at/ground-type?`, `at/numeric-dyn-type?`, `at/refinement-type?`, `at/adapter-leaf-type?`, `at/optional-key-type?`, `at/fn-method-type?`, `at/fun-type?`, `at/maybe-type?`, `at/union-type?`, `at/intersection-type?`, `at/map-type?`, `at/vector-type?`, `at/set-type?`, `at/seq-type?`, `at/var-type?`, `at/placeholder-type?`, `at/inf-cycle-type?`, `at/value-type?`, `at/type-var-type?`, `at/forall-type?`, `at/sealed-dyn-type?`, and `at/conditional-type?`

This is the domain where the cast engine, blame logic, compatibility checks, and most internal reasoning should happen.

Concretely, when code is doing real semantic work, it should usually be operating on values shaped like:

- `at/->GroundT prov :int 'Int`
- `at/->ValueT prov inner value`
- `at/->FunT prov [...]`
- `at/->MapT prov {...}`
- `at/->UnionT prov members`
- `at/->ForallT prov binder body`
- `at/->SealedDynT prov ground`

and branching with the corresponding `at/*-type?` predicates.

## Boundary Rule

Convert from an external domain into the type domain at the boundary: `schema->type` for Plumatic (`skeptic.analysis.bridge/schema->type`), `malli-spec->type` for Malli (`skeptic.analysis.malli-spec.bridge/malli-spec->type`).

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

## The Dict After Admission Is Types, Full Stop

Once Schema and MalliSpec values have crossed their respective boundaries into the Type domain, the per-namespace declaration dict returned by `skeptic.checking.pipeline/namespace-dict` holds bare Types keyed by qualified symbol. No `:typings`, `:output-type`, `:arglists`, `:accessor-summary`, `:type`, or other sidecar wrappers live on dict values.

The origin of an entry (Schema, MalliSpec, native, or user type-override) is carried on each admitted Type's `:prov` field and is also surfaced as a `Provenance` record (see `skeptic.provenance`) in the parallel `:provenance` map produced by `namespace-dict`, keyed by the same qualified symbol. Downstream checking consumes the dict for real type reasoning and consults provenance only to attach `:source` on findings — never to reconstruct the original schema/malli-spec.

The direct admission flow per source is:

- `Schema → schema->type → dict[sym] = Type` (via `skeptic.typed-decls`)
- `MalliSpec → malli-spec->type → dict[sym] = Type` (via `skeptic.typed-decls.malli`)
- `native → Type → dict[sym] = Type` (via `skeptic.analysis.native-fns`)
- `:skeptic/type-overrides → dict[sym] = Type` (via opts consumed by `skeptic.typed-decls`)

There is no intermediate entry-map shape between admission and dict insertion.

## Types Carry Provenance As A Record Field

Every Type in `skeptic.analysis.types` is a `defrecord` with `:prov` as field 1. The constructor contract is absolute:

- Every positional arrow constructor (`at/->GroundT`, `at/->MapT`, `at/->FunT`, `at/->FnMethodT`, `at/->MaybeT`, `at/->UnionT`, `at/->SeqT`, `at/->VectorT`, `at/->SetT`, `at/->ValueT`, `at/->ForallT`, `at/->PlaceholderT`, `at/->InfCycleT`, `at/->RefinementT`, `at/->AdapterLeafT`, `at/->OptionalKeyT`, `at/->VarT`, `at/->TypeVarT`, `at/->BottomT`, `at/->DynT`, `at/->NumericDynT`, `at/->SealedDynT`, `at/->ConditionalT`) takes a Provenance as first argument.
- Type helpers that wrap constructors (`at/Dyn`, `at/NumericDyn`, `at/BottomType`) also take a Provenance as first argument.
- `prov/of` is the canonical reader and throws if `:prov` is absent. There is no fallback and no `prov/unknown` sentinel.

Combinator provenance rule (container-owns-identity):

- When a combinator joins or merges existing Types into a new composite (union, intersection, merged map, joined seq), the result carries an **anchor provenance** supplied by the caller — the container's own prov — not a provenance derived from the items. Items keep their own provs on themselves; the composite owns its own.
- Combinators requiring an anchor take it as an explicit first parameter: `av/join anchor-prov types`, `amo/merge-map-types anchor-prov types`, `amoa/merge-types anchor-prov types`, `coll/concat-output-type anchor-prov args`, `shared-call/shared-call-output-type` derives the anchor from `default-output-type`.

Ingestion boundaries supply the root Provenance used throughout a derived subtree:

- `skeptic.analysis.bridge/schema->type` and `skeptic.analysis.bridge.localize/*` — `:schema`.
- `skeptic.analysis.malli-spec.bridge/malli-spec->type` threads `:malli` through every sub-constructor (`FunT`, `FnMethodT`, `MaybeT`, `UnionT` members, enum values, primitive leaves, and predicate witnesses).
- `skeptic.analysis.native-fns/static-call-native-info` — `:native`.
- `skeptic.config` / `skeptic.typed-decls` for `:type-overrides` — `:type-override`.
- `skeptic.analysis.type-ops/exact-value-type`, `skeptic.analysis.value/type-of-value`, and the analyzer annotation layer — `:inferred`, taken from `prov/with-ctx` when inside the analyzer.

Equality helpers:

- `at/type=?` compares Types by shape, stripping `:prov` recursively. Tests and any shape-sensitive code must use it instead of `=`, because `=` on defrecords also compares `:prov`.
- `at/dedup-types` deduplicates by shape (same recursive strip) while preserving one representative from the input.

## Design Bias

- Keep cast and analysis helpers small, focused, and type-domain-first.
- Preserve ordered dispatch when branch priority is semantically meaningful.
- Treat schema-level helpers as boundary adapters, not as the core analysis model.

## Releasing and CI

Version bumps, `script/verify-monorepo-versions.sh`, GitHub Actions workflows,
and Clojars deployment are documented in [../docs/releasing.md](../docs/releasing.md)
at the repository root.
