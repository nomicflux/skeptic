# Skeptic Workspace Guidance

## Read First

Before changing the cast engine or related type-analysis code, read `skeptic/docs/blame-for-all.md`.
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
  Leiningen plugin entrypoint. Parses CLI flags (via the shared `skeptic.cli.options` vector), selects the Skeptic profile, and runs the checker in the target project.
- `skeptic.cli.main`:
  deps.edn / Clojure CLI entrypoint (`clojure -M:skeptic` and `clojure -X:skeptic`). Parses CLI flags, resolves source paths via `skeptic.cli.paths`, and calls `skeptic.core/check-project`. Library-pure: no `leiningen.core.*` dependency.
- `skeptic.profiling`:
  Optional profiling wrapper around a run. Used by the plugin before invoking the main checker.
- `skeptic.core`:
  Top-level checker/report printer. Discovers namespaces from source paths, invokes namespace checking, and formats user-facing output.
- `skeptic.checking.pipeline`:
  Main per-namespace pipeline. Reads forms, annotates analyzer ASTs, compares inferred vs declared types, and emits mismatch/exception results.

Source namespace families:

- `skeptic.cli.*`:
  Runner layer for the deps.edn / Clojure CLI entrypoint. `skeptic.cli.options` owns the shared CLI option vector and `parse` helper, used by both `leiningen.skeptic` and `skeptic.cli.main`. `skeptic.cli.paths` discovers source paths via `clojure.tools.deps/create-basis` (deps.edn-side only). `skeptic.cli.main` is the `-main` / exec-fn entrypoint. `skeptic.cli.cljs.discover` defines the shared `DiscoverySources` schema (`:source-paths`, `:cljs-files`, `:cljc-files`); `skeptic.cli.cljs.deps` (deps.edn), `skeptic.cli.cljs.lein` (Leiningen), and `skeptic.cli.cljs.shadow` (Shadow-CLJS) each implement project-layout-specific discovery returning that shape. Rule: project-metadata reading and CLI parsing live only under `skeptic.cli.*`; library namespaces (`skeptic.core`, `skeptic.checking.*`, etc.) never read project layout — they receive paths as arguments.
- `skeptic.cljs.*`:
  ClojureScript loading and admission helpers. `skeptic.cljs.analyzer-driver` exposes stateless cljs analyzer entrypoints — `parse-source-ns`, `analyze-form`, `analyze-source-file` — via the public `cljs.analyzer.api`; no compiler state is constructed or threaded across calls. `skeptic.cljs.schema-interpreter` interprets post-macroexpansion Plumatic Schema bodies inside a sci-sandboxed context with `schema.core` pre-loaded (no `clojure.lang.Compiler/eval`); used by `skeptic.schema.collect.cljs` for cljs admission.
- `skeptic.checking.*`:
  Checking-time orchestration and presentation of analyzed forms.
  `skeptic.checking.ast` extracts call/local context from annotated ASTs.
  `skeptic.checking.form` normalizes source forms and source-location metadata.
  `skeptic.checking.opts` owns the typed opts schemas (`FormCheckOpts`, `PrinterOpts`, `CompiledTypeOverride`, etc.) that pipeline and printer functions consume.
  `skeptic.checking.state` defines `ProjectState` and the schema contracts for shared per-project caches (cljs ns-ast / asts cache, type-override prov seed, project-discovery results).
  `skeptic.checking` is currently a marker namespace.
- `skeptic.schema`, `skeptic.schema.collect`, `skeptic.schema.collect.cljs`, `skeptic.typed-decls`:
  Declaration admission pipeline.
  `skeptic.schema` defines the admitted schema description shape.
  `skeptic.schema.collect` reads var metadata and admits raw Plumatic schema declarations on the JVM.
  `skeptic.schema.collect.cljs` is the cljs admission boundary: it walks cljs ASTs against the project-state cljs cache, calls `skeptic.cljs.schema-interpreter/interpret-schema-form` on post-macroexpansion Plumatic schema bodies, and emits the same `{:entries :errors}` shape as the JVM collector.
  `skeptic.typed-decls` converts admitted schema descriptions into typed entries via `schema->type`.
  `skeptic.typed-decls.malli` is the sibling converter for admitted malli-spec descriptions via `malli-spec->type`.
- `skeptic.malli-spec`, `skeptic.malli-spec.collect`, `skeptic.analysis.malli-spec.bridge`:
  MalliSpec-domain admission and boundary.
  `skeptic.malli-spec` defines the admitted `MalliSpecDesc` shape and uses `:malli-spec` everywhere; it never carries `:schema`, which is reserved for Plumatic.
  `skeptic.malli-spec.collect` walks `(ns-interns ns)`, reads `:malli/schema` var metadata (skipping macros and vars without it), and returns `{:entries {qualified-sym {:name ... :malli-spec ...}} :errors [...]}`.
  `skeptic.malli-spec.collect.cljs` is the cljs counterpart that reads `:malli/schema` Var meta off cljs ASTs and emits the same admitted `{:entries :errors}` shape.
  `skeptic.analysis.malli-spec.bridge` exposes `admit-malli-spec`, `malli-spec-domain?`, and `malli-spec->type`. `malli-spec->type` is a small recursive runner over the admitted Malli form: it handles the `[:=> [:cat & inputs] output]` callable shape (producing a `FunT`/`FnMethodT`), `[:maybe X]` (producing `MaybeT`), `[:or X Y ...]` (via `ato/union-type` over converted members), `[:and X Y ...]` (via `ato/intersection-type` over converted members), `[:tuple X Y ...]` (producing `(at/->SeqT prov (at/pattern-from-prefix-tail [Tx Ty ...] nil) :vector)`, the fixed-arity heterogeneous shape that the cast engine already enforces via `prefix-tail-cast-fails-arity?`), `[:map [:k T] [:k {:optional true} T] ...]` (producing `MapT` with `ValueT(:k) → T` for required keys and `OptionalKeyT(ValueT(:k)) → T` for optional keys; the optional map-level properties index — including `:closed` — is parsed and dropped, matching Plumatic's closed-by-default representation), `[:multi {:dispatch :kw} [tag schema] ...]` (producing `ConditionalT` with one branch per entry; each branch is `[tag (form->type schema) descriptor]` where the descriptor is `{:path [:kw] :values [tag]}` for keyword dispatch or `nil` for fn-dispatch and the `:malli.core/default` sentinel — using more structure than Plumatic's `s/conditional` import preserves), `[:= X]` (via `ato/exact-value-type`, mirroring Plumatic's `s/eq`), `[:enum ...]` (via exact value types), `[:schema {:registry R} body]` (merging `R` into the active-registry context and recursing on `body`, supporting both transparent indirection and local-registry carriers), and `[:ref ::name]` (resolving through the active registry; emitting `InfCycleT` when the ref is already active in the current import path; unknown refs are rejected at Malli's `m/schema` admission step with `:malli.core/invalid-ref` before the runner sees them, so the bridge's `PlaceholderT` branch — present for symmetry with Schema's `var-import-type` cycle handling at `src/skeptic/analysis/bridge.clj:491-515` — is unreachable via the standard `desc->type` admission path). Leaves route through a five-entry primitive table (`:int`, `:string`, `:keyword`, `:boolean`, `:any`) and recognized bare predicate symbols. Any other Malli form (e.g., `:vector`, sequence/regex combinators) returns `Dyn`.
- `skeptic.analysis.bridge` and `skeptic.analysis.bridge.*`:
  Schema-domain to type-domain boundary.
  `skeptic.analysis.bridge` imports schemas into semantic types.
  `skeptic.analysis.bridge.canonicalize` normalizes schema forms.
  `skeptic.analysis.bridge.localize` resolves schema values into the current project context and carries localized error context.
  `skeptic.analysis.bridge.render` renders semantic types for reports and strips derived display-only fields.
  `skeptic.analysis.bridge.algebra` contains schema-boundary set/join helpers.
- `skeptic.provenance`, `skeptic.provenance.schema`:
  Provenance API and schema contracts. `skeptic.provenance` exposes `make-provenance`, `of`, `source`, `lang`, `provenance?`, `merge-provenances`, and the `Provenance` record (carrying `:source`, `:qualified-sym`, `:declared-in`, `:var-meta`, `:refs`, `:lang`). `skeptic.provenance.schema` defines the `Lang` and `Provenance` Plumatic schemas — `:lang ∈ {:clj :cljs #{:clj :cljs}}` — used to type-check provenance values at admission boundaries.
- `skeptic.analysis.types`, `skeptic.analysis.types.schema`, `skeptic.analysis.types.proto`, `skeptic.analysis.type-ops`, `skeptic.analysis.type-algebra`:
  Core type-domain representation and normalization.
  `skeptic.analysis.types` is the canonical semantic type data model. Ordered sequential collections are represented by a single `SeqTRec [prov items tail ordered-coll-kind]` record — the prior split between `VectorTRec` and `SeqTRec` has been collapsed. The `:ordered-coll-kind` field is an enum `(s/enum :vector :sequential)` set on every value (`ordered` excludes sets — those have their own `SetTRec`). Per-source-domain mapping at admission:
    - Plumatic homogeneous `[X]` and any `prefix-tail-import-type` path the schema bridge currently invokes with `at/->SeqT` — `:sequential`. Plumatic's own predicate is `(or nil? sequential? (instance? java.util.List))`, so `:vector` would invent information for these admissions.
    - Plumatic heterogeneous prefix-tail forms (e.g. `[(s/one A) (s/one B) X]`) and any `prefix-tail-import-type` path the schema bridge invokes through the `:vector`-stamping `at/->SeqT` lambda at `src/skeptic/analysis/bridge.clj:691` — `:vector` (preserved from prior admission shape for diagnostic continuity).
    - Malli `[:vector child]` — `:vector`. Malli `[:sequential child]` — `:sequential`. Malli `[:tuple X Y ...]` — `:vector` (current admission shape preserved).
    - Native-fn registrations — per the registration's intent (vector-returning ops `:vector`; seq-returning ops `:sequential`).
    - Literal vector value (`[1 2 3]`) — `:vector`. Literal list/seq value (`(list 1 2 3)`, lazy seqs) — `:sequential`.
    - Operations whose runtime output is always a `seq` / `lazy-seq` (`map`, `filter`, `concat`, `lazy-seq`, `rest`, `seq` of a vector, etc.) — `:sequential`.
    - Operations whose runtime output preserves vector identity (`conj` on a vector, `subvec`, `into [] coll`, `butlast` / `take` / `drop` returning a vector via the `coll/*` helpers) — `:vector`.
  At value-check time, `skeptic.analysis.value-check` dispatches on `:ordered-coll-kind` to pick the runtime predicate: `:vector` → `vector?`, `:sequential` → `sequential?`. A list value matches a `:sequential`-kind SeqT but not a `:vector`-kind SeqT — that is the one place where the discriminator field gates accept/reject behavior rather than diagnostic flavour.
  The merged `->SeqT [prov pattern ordered-coll-kind]` constructor is the only ordered-collection constructor. `pattern` is a vector of regex atoms — each atom is a tagged map of shape `{:kind :one  :type T}` (one element of type `T`) or `{:kind :star :type T}` (zero or more elements of type `T`). The schema invariant on `pattern` is `(:one)* (:star)?` — zero or more `:one` atoms followed by at most one trailing `:star` atom, enforced by `skeptic.analysis.types.schema/Pattern`. This expresses exactly what the prior `(items, tail)` pair could express: an empty closed sequence (`pattern=[]`), a closed prefix (`pattern=[{:one A}{:one B}]`), a homogeneous tail (`pattern=[{:star T}]`), or a prefix with a homogeneous tail (`pattern=[{:one A}{:one B}{:star T}]`). Callers that still want the old prefix-and-tail framing use the adapters `pattern-prefix` (returns the leading `:one` atoms' types as a vector — equivalent to the old `:items`) and `pattern-tail` (returns the trailing `:star` atom's type or `nil` — equivalent to the old `:tail`); admission boundaries use `pattern-from-prefix-tail` to build a pattern from a prefix and an optional tail. `:ordered-coll-kind` is still read directly at every consumer. `seq-type?` is the canonical predicate for the merged record and matches both kinds; vector-only checks are written as `(and (seq-type? t) (= :vector (:ordered-coll-kind t)))`.
  `skeptic.analysis.types.schema` defines schema contracts for semantic types. The `OrderedCollKind = (s/enum :vector :sequential)` schema gates the new field.
  `skeptic.analysis.types.proto` defines the protocol used to tag semantic type records.
  `skeptic.analysis.type-ops` builds, normalizes, unions, intersects, and de-`maybe`s types.
  `skeptic.analysis.type-algebra` holds extra type-combination helpers.
- `skeptic.analysis.annotate` and `skeptic.analysis.annotate.*`:
  Analyzer AST typing/inference.
  `skeptic.analysis.annotate` dispatches on analyzer `:op` values.
  `skeptic.analysis.annotate.api` is the public API for the annotate subsystem — node accessors (`node-form`, `node-type`, `node-op`, ...) and mutators (`with-type`) used by any code that does not own node shape.
  Sub-namespaces split the work by AST area: `base`, `control`, `data`, `fn`, `invoke`, `invoke-output`, `jvm`, `match`, `numeric`, `coll`, `map-path`, `map-projection`, and `shared-call`; `cljs` is the cljs annotator family — host-call, host-field, js, and js-var node types — wired via the same dispatcher when the source file is cljs; `test-api` exists for test-facing helpers.
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
  `cast.java-callable` handles a `FunT` source against a Java callable-interface class target (Runnable, Callable, Comparator, java.util.function.*).
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
- `skeptic.classloader-fix`:
  JVM bootstrap-loader probe + cache prime, gated so it only fires when the probe actually fails. JDK 8 users and `LEIN_USE_BOOTCLASSPATH=no` users pay zero cost. Required for `cljs.analyzer.api` macro loading inside the Leiningen JVM on JDK 9+.
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
- `skeptic.provenance` is the API for Provenance values carried alongside the declaration dict and attached to every Type. Consumers use `make-provenance`, `of`, `source`, `lang`, `provenance?`, and `merge-provenances` — not the `Provenance` record constructor or raw `:source` / `:lang` field access. `prov/of` is strict: it throws if given a value without `:prov`, so any code that fabricates a Type without a real Provenance is rejected at the earliest read. There is no `prov/unknown` sentinel; every Type everywhere must carry a named-source Provenance.

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

#### How Plumatic Schema annotations are checked in this codebase

**Skeptic itself is the schema checker.** This is not a runtime-validation codebase.

- A `:- T` annotation on an `s/defn` parameter or return position is a **static contract**. Skeptic-the-tool reads these annotations off var metadata at analysis time and uses them to check every call site against `T`. That is the enforcement mechanism.
- `s/with-fn-validation` is **disabled during plugin runs** (the checker turns it off around its own analysis pass) and **enabled during the test suite** (so tests do exercise schema annotations at runtime). Production code paths in user projects do not rely on it. Runtime schema validation is therefore a test-time aid, not the mechanism by which Skeptic enforces type contracts on user code.
- `s/check` and `s/validate` are **NEVER the answer to anything** in this codebase. Not for enforcing an invariant, not for proving a type is "checked," not for validating data at a boundary, not at all. Any reasoning that arrives at "we should call `s/check`" or "this isn't checked because nothing calls `s/validate`" is reasoning from generic Clojure habits and is wrong for Skeptic. Stop and re-derive.
- The way to make a new invariant load-bearing is to declare it in the relevant `s/defn` signature so that Skeptic sees it and checks callers. If a parameter is un-annotated, Skeptic does not constrain it — that is the only sense in which a contract is "missing."

When writing or reviewing code that turns on whether a schema is "enforced," "checked," "validated," or "load-bearing," name the consuming mechanism explicitly: it is Skeptic's static read of `s/defn` annotations. Anything else (a `(s/check Pattern …)` call, an `s/validate` invocation, a hypothetical runtime guard) is not the mechanism this codebase uses and not the right tool to reach for.

### MalliSpec Domain

The MalliSpec domain is the external [Malli](https://github.com/metosin/malli) schema representation — Malli vector/map/AST forms such as `:int`, `[:=> [:cat :int] :int]`, `[:map [:x :int]]`, `[:function ...]`. See `docs/malli-reference.md` for the forms Skeptic targets and what is currently stubbed.

The slice as it now stands:

- **Discovery:** `:malli/schema` Var-meta plus `(malli.core/function-schemas)` registry projection (which captures `m/=>` and `malli.experimental/defn` as compile-time-registered entries). Both surfaces are unioned by `skeptic.malli-spec.collect/ns-malli-spec-results`.
- **Conversion (`malli-spec->type`):** admits via `m/form ∘ m/schema`; recursively converts the callable shape `[:=> [:cat & inputs] output]` into a `FunT` with one `FnMethodT`, `[:maybe X]` into `MaybeT` over the converted inner, `[:or X Y ...]` via `ato/union-type` over the converted members, `[:and X Y ...]` via `ato/intersection-type` over the converted members (so dedup / singleton-collapse / ordering match the Schema-side `sb/both?` behavior at `src/skeptic/analysis/bridge.clj:623-624`), `[:tuple X Y ...]` directly into `(at/->SeqT prov (at/pattern-from-prefix-tail [Tx Ty ...] nil) :vector)` — the same fixed-arity heterogeneous shape Plumatic vector schemas import to via `prefix-tail-import-type` at `src/skeptic/analysis/bridge.clj:644-645`; the cast engine treats `tail=nil` `:vector`-kind `SeqT` as closed/exact-arity via `prefix-tail-cast-fails-arity?` at `src/skeptic/analysis/cast/collection.clj:24-26`, so no separate tuple type variant is needed. `[:tuple]` (zero-element) collapses in `m/form` to the bare keyword `:tuple` and falls through to `Dyn`. `[:map [:k T] [:k {:optional true} T] ...]` directly into `at/->MapT` with `ato/exact-value-type prov k` keys for required entries and `at/->OptionalKeyT prov (ato/exact-value-type prov k)` keys for optional entries — the same shape Plumatic plain-map schemas import to via `map-import-type` at `src/skeptic/analysis/bridge.clj:393-409`; the cast engine consumes both kinds via `amo/map-entry-descriptor` at `src/skeptic/analysis/map_ops.clj:85-110`, so no separate Type variant is needed. The optional map-level properties index `{:closed true|false}` controls openness: when `:closed true`, the bridge emits the explicit-key entries only (closed `MapT`); otherwise — including `{:closed false}` and any absent property — the bridge adds a `(at/->GroundT prov :keyword 'Keyword) → (at/Dyn prov)` domain entry to the assembled `MapT`, matching Plumatic's `s/Keyword s/Any` open-map idiom and Malli's open-by-default semantics. Other properties on the index are parsed and dropped. `[:map]` (zero-element) collapses in `m/form` to the bare keyword `:map` and falls through to `Dyn`. `[:multi {:dispatch DISP} [tag schema] ...]` directly into `at/->ConditionalT` with one branch triple `[tag (form->type schema) descriptor]` per entry — same triple shape Plumatic's `s/conditional` imports to via `conditional-import-type` at `src/skeptic/analysis/bridge.clj:464-480`; consumed by `effective-conditional-branches` / `effective-conditional-arms` at `src/skeptic/analysis/conditional_arms.clj:52-76`. The descriptor is `{:path [DISP] :values [tag]}` when `DISP` is a keyword (so later arms get correctly narrowed by negation of earlier tags via `route-conditional-by-values`) and `nil` for fn-dispatch and the `:malli.core/default` sentinel branch — Malli's `:multi` carries enough structure to emit `:values` descriptors that Plumatic's `s/conditional` cannot, so the bridge uses that structure where available. `[:= X]` directly into `ato/exact-value-type prov X` — same conversion Plumatic's `(s/eq X)` uses at `src/skeptic/analysis/bridge.clj:590-591`. And `[:enum ...]` via exact value types. `[:schema {:registry R} body]` merges `R` into the recursive runner's `:registry` context and recurses on `body` — `m/form` preserves the registry as a property of the `:schema` node (round-trip-stable across `m/form ↔ m/schema`), so `admit-malli-spec` does not need to thread the `m/schema` object through. `[:ref ::name]` resolves through the active registry: when `::name` is already in `:active-refs` the runner emits `(at/->InfCycleT prov ::name)` with `:closed-refs #{::name}`; when present in the registry it recurses with `::name` added to `:active-refs`. Unknown refs are rejected upstream by Malli's `m/schema` with `:malli.core/invalid-ref` before `form->type` traversal begins, so the bridge's `PlaceholderT` branch — included for symmetry with Schema's `var-import-type` cycle handling at `src/skeptic/analysis/bridge.clj:491-515` — is unreachable via the standard `desc->type` admission path. Mirroring that pattern, `form->type` returns `{:type T :closed-refs #{...}}` (built by `import-result`) and merges via `merge-closed-refs`; `malli-spec->type` unwraps `:type` at the outer call. Leaves resolve through a registry: `:int → Int`, `:string → Str`, `:keyword → Keyword`, `:symbol → Symbol`, `:boolean → Bool`, `:double → Double`, `:float → Float`, `:nil → ValueT(Dyn, nil)`, `:qualified-keyword → Keyword`, `:qualified-symbol → Symbol`, `:any → Dyn`, plus recognized bare predicate symbols. `:uuid` is admitted by Malli but has no Skeptic ground and falls through to `Dyn`. `:char`, `:pos-int`, `:neg-int`, and `:nat-int` are not in Malli 0.20.1's default registry and are rejected at admission. `[:function & arms]` (with optional properties) — when every arm is a `[:=> [:cat ...] out]` shape — admits to a single `at/->FunT` carrying one `at/->FnMethodT` per arm, mirroring Plumatic's multi-arity `function-import-type` at `src/skeptic/analysis/bridge.clj:417-448`. `[:vector child]`, `[:vector {props} child]` admit via `(at/->SeqT prov (at/pattern-from-prefix-tail [] child-type) :vector)` (homogeneous: empty prefix, child as tail) — same shape Plumatic's `[s/Int]` import produces via `prefix-tail-import-type`. `[:sequential child]` admits the same way with `:sequential`. `[:set child]` admits via `at/->SetT prov #{child-type} true` (homogeneous=true), mirroring Plumatic's `#{s/Int}` import at `src/skeptic/analysis/bridge.clj:647-650`. Properties on `:vector`/`:set`/`:sequential` (e.g. `{:min n :max m}`) are parsed and dropped — Skeptic has no length constraint on container types. Every other Malli form (regex/sequence combinators outside `:=>` head, e.g. `:cat` outside the function head, `:alt`, `:*`, `:+`, `:?`, `:repeat`, `:re`, `:fn`, etc.) returns `Dyn`.
- **Strict separation at admission, merge at the pipeline boundary:** `typed-ns-results` returns Schema-derived entries plus `:skeptic/type-overrides`; the Malli collector (`skeptic.malli-spec.collect`) and Malli→Type bridge (`skeptic.analysis.malli-spec.bridge`) remain domain-pure standalone modules. Pipeline wiring happens in `skeptic.checking.pipeline/namespace-dict`, which combines per-namespace schema, malli, and native-fn entries through `merge-type-dicts [schema-result malli-result native-result]`; once inside the merged dict, Malli-admitted entries reach the analyzer in the same shape as schema entries.
- **Disable gates:** `:plumatic-disable` and `:malli-disable` opts (set by the `--plumatic-disable` / `--malli-disable` CLI flags) early-return the relevant intake. Three gate points must stay symmetric or the dict and provs maps will disagree: `skeptic.typed-decls/typed-ns-results` (Plumatic dict + `:type-override` reduction), `skeptic.typed-decls.malli/typed-ns-malli-results` (Malli dict), and `skeptic.checking.pipeline/ns-var-provs` plus `project-var-provs` (per-source provenance branches and the type-override-prov seed). Adding either stream's disable behavior anywhere new requires updating both halves so a finding's `:source` cannot announce a stream that produced no admission.
- **Deferred:** sequence/regex combinators outside the `:=>` head (`:cat` outside the function head, `:alt`, `:*`, `:+`, `:?`, `:repeat`, `:re`, `:fn`, etc.); JSONL Malli kinds; `.skeptic/config.edn` Malli surface; `:skeptic/type` Malli interpretation; analyzer-side wiring of Malli-derived types as a separate type source.

### Type Domain

The type domain is the library's internal semantic representation of types.
This is the domain used for real analysis and cast reasoning.
In this codebase, those semantic types live in `src/skeptic/analysis/types.clj`.
The type domain is not an abstract idea here; it is the concrete family of internal values built and recognized by helpers such as:

- constructors:
  `at/->DynT`, `at/->BottomT`, `at/->GroundT`, `at/->NumericDynT`, `at/->RefinementT`, `at/->AdapterLeafT`, `at/->OptionalKeyT`, `at/->FnMethodT`, `at/->FunT`, `at/->MaybeT`, `at/->UnionT`, `at/->IntersectionT`, `at/->MapT`, `at/->SetT`, `at/->SeqT` (3-arg `[prov pattern ordered-coll-kind]` where `pattern` is a vector of regex atoms — `{:kind :one :type T}` or `{:kind :star :type T}` — matching `(:one)* (:star)?`, with `:ordered-coll-kind ∈ {:vector :sequential}`), `at/->VarT`, `at/->PlaceholderT`, `at/->InfCycleT`, `at/->ValueT`, `at/->TypeVarT`, `at/->ForallT`, `at/->SealedDynT`, and `at/->ConditionalT`
- predicates:
  `at/dyn-type?`, `at/bottom-type?`, `at/ground-type?`, `at/numeric-dyn-type?`, `at/refinement-type?`, `at/adapter-leaf-type?`, `at/optional-key-type?`, `at/fn-method-type?`, `at/fun-type?`, `at/maybe-type?`, `at/union-type?`, `at/intersection-type?`, `at/map-type?`, `at/set-type?`, `at/seq-type?` (matches the merged `SeqTRec` regardless of `:ordered-coll-kind`; callers needing vector-only or sequential-only behavior dispatch on the field — vector-only as `(and (seq-type? t) (= :vector (:ordered-coll-kind t)))`), `at/var-type?`, `at/placeholder-type?`, `at/inf-cycle-type?`, `at/value-type?`, `at/type-var-type?`, `at/forall-type?`, `at/sealed-dyn-type?`, and `at/conditional-type?`

This is the domain where the cast engine, blame logic, compatibility checks, and most internal reasoning should happen.

Concretely, when code is doing real semantic work, it should usually be operating on values shaped like:

- `at/->GroundT prov :int 'Int`
- `at/->ValueT prov inner value`
- `at/->FunT prov [...]`
- `at/->MapT prov {...}`
- `at/->UnionT prov members`
- `at/->ForallT prov binder body`
- `at/->SealedDynT prov ground`
- `at/->SeqT prov pattern :vector` / `at/->SeqT prov pattern :sequential` (one record covers both ordered-collection kinds via the `:ordered-coll-kind` field; `pattern` is a vector of `:one`/`:star` atoms, see Type Domain SeqT description)

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

- Every positional arrow constructor (`at/->GroundT`, `at/->MapT`, `at/->FunT`, `at/->FnMethodT`, `at/->MaybeT`, `at/->UnionT`, `at/->SeqT`, `at/->SetT`, `at/->ValueT`, `at/->ForallT`, `at/->PlaceholderT`, `at/->InfCycleT`, `at/->RefinementT`, `at/->AdapterLeafT`, `at/->OptionalKeyT`, `at/->VarT`, `at/->TypeVarT`, `at/->BottomT`, `at/->DynT`, `at/->NumericDynT`, `at/->SealedDynT`, `at/->ConditionalT`) takes a Provenance as first argument.
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

Each Provenance also carries a `:lang` field — one of `:clj`, `:cljs`, or `#{:clj :cljs}` — identifying the host language the Type was admitted under. Single-language admissions stamp `:clj` or `:cljs`; a `.cljc` Var admitted by both passes is merged into `#{:clj :cljs}` for cross-language attribution. The `skeptic.provenance/lang` reader and `skeptic.provenance.schema/Lang` schema are the only valid surfaces; raw `:lang` access is reserved for the bridge code that mints provs.

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
