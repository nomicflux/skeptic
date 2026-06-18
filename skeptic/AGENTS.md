# Skeptic Workspace Guidance

## Read First

Before changing the cast engine or related type-analysis code, read `skeptic/docs/blame-for-all.md`.
Use it as the high-level algorithm reference for the library's core cast and blame behavior.

Before changing anything that crosses the host/worker boundary (analyzer driver,
classpath construction, transport, project-state wiring), read the **Worker
Runtime Isolation** section below.

## Deliverable matching (agents)

When implementing or answering from this doc, **match the requested artifact and audience**. Do not substitute a different deliverable and treat it as equivalent.

Examples:
- **Audience:** Material for maintainers (credentials layout, release mechanics, internal rationale) belongs in **`AGENTS.md`**, `docs/`, workflow comments, or other non–end-user surfaces—not in the root **`README.md`**, which is for people consuming Skeptic as a tool.
- **Fidelity:** If the request is a concrete workflow, file change, or behavior spec, implement **that** (or say what blocks you and ask one focused question)—do not replace it with a shortened story plus “human discipline” or process prose.
- **Navigation / “where in UI”:** If the request is how to reach a page in GitHub or another product, the answer is an **ordered list of exact UI labels** for every hop (e.g. Settings → Security → …). Omitting a parent step produces a **wrong** path, not a summary.
- **If the real answer is “I cannot without X”** (missing access, ambiguous scope), say so or ask—**do not** ship a different-shaped answer and imply it satisfies the ask.

## PSP Completion Gates

When `/psp` is used in this repo, each phase's completion gate requires **all three** of the following to pass before the phase can be marked complete:

1. `lein test` — full test suite, zero failures.
2. `clj-kondo --lint .` — zero warnings.
3. **Skeptic on self** — `lein with-profile +skeptic-plugin skeptic -p` (after `../script/install-local.sh`) produces zero findings.

## Project Setup

1. This is `leiningen` project for a Clojure app
2. Tests are run via `lein test`
3. The plugin lives in `../lein-skeptic` and is published as **`org.clojars.nomicflux/lein-skeptic`** on Clojars. For local development without Clojars, run `../script/install-local.sh` (cleans the local m2 cache for skeptic and `lein install`s both `skeptic` and `lein-skeptic`), then `lein with-profile +skeptic-plugin skeptic -p` from this folder (the `:skeptic-plugin` profile pins **`org.clojars.nomicflux/lein-skeptic`** to match that install). **Always use `-p` / `--porcelain`** — the default ANSI text report is for humans; agents must consume JSONL findings, never grep coloured output.
4. Linting is performed via `clj-kondo --lint <dir>`
5. **Including test sources on the deps.edn path:** `clj -T:skeptic check` reads sources from the active deps.edn basis only. `:paths` is always on; test paths are not, unless an alias declares them as `:extra-paths`. To cover test code, pass the alias that adds the test path: `clj -T:skeptic check :alias :test`. The Leiningen path picks up `:test-paths` automatically.
6. **Projects pinned below Clojure 1.7:** the worker's floor is Clojure 1.7. Targets that pin an older Clojure in their `:dev` profile (e.g. fnhouse pins 1.6.0) need a profile that brings Clojure up to 1.7+ for the Skeptic run: `lein with-profile -dev,+1.7 skeptic`. Use whichever profile the project provides that pins Clojure ≥ 1.7.

## Namespace Map (DESCRIPTIVE, NOT NORMATIVE)

Primary execution path:

- `leiningen.skeptic`: Leiningen plugin entrypoint. Zero-transitive hermetic-host launcher. Owns no Skeptic, schema, malli, nrepl, or transit code on its own classpath — it `:require`s only `leiningen.core.*`, `clojure.java.io`, and `clojure.string`. Resolves Skeptic host-deps and worker-deps via aether against a synthetic project at task time (those resolutions never enter lein's plugin-tree mediation, so `:pedantic? :abort` projects load cleanly), then spawns two subprocesses: the worker JVM (built from `leiningen.core.eval/shell-command project worker-form` with `-classpath` augmented to `project-cp ++ plugin-jars ++ worker-jars ++ skeptic-self`) and the host JVM (built with only the aether-resolved host-deps jars as `-cp`). The host JVM is told the worker's nREPL port via stdin EDN. Project profiles, injections, dynamic reader bindings, and registered data readers remain authoritative on the worker side; the host side carries only Skeptic's pinned versions.
- `skeptic.tool`: deps.edn tool entrypoint exposed at `clj -T:skeptic check`. One-line wrapper over `skeptic.cli.main/check-project`.
- `skeptic.cli.main`: library-pure deps.edn driver. `check-project` does the real work for the `-T` tool path. `check-from-launcher` is the hermetic-host entrypoint invoked by `leiningen.skeptic`: it reads `{:root :paths :worker :args}` as EDN on stdin, parses `:args` via `skeptic.cli.options`, synthesizes a `:worker-spawn` fn whose returned worker map names the externally-managed nREPL port, and calls `skeptic.core/check-project`. The legacy `-M` (`-main`/`run-cli`) and `-X` (`run`) entrypoints intentionally refuse with a redirect message — those invocations are not hermetic because they would load the client project on Skeptic's runtime classpath.
- `skeptic.core`: top-level checker/report printer. Discovers namespaces, invokes per-namespace checking, formats output.
- `skeptic.checking.pipeline`: main per-namespace pipeline. Reads forms, annotates analyzer ASTs, compares inferred vs declared types, emits results.

Source namespace families (open one of these files when you need to know what's inside):

- `skeptic.cli.*`: runner layer for deps.edn / Clojure CLI. `skeptic.cli.options` owns the shared CLI option vector — used by `skeptic.cli.main/check-from-launcher` for stdin-EDN `:args` parsing under the hermetic-host launcher and by `skeptic.cli.main/check-project` for the `-T` tool path. The lein-skeptic launcher namespace does NOT require `skeptic.cli.options`; it forwards `args` raw and parsing happens host-side. `skeptic.cli.cljs.{deps,lein,shadow}` implement project-layout-specific cljs source discovery returning the shared `DiscoverySources` shape in `skeptic.cli.cljs.discover`. **Rule:** project-metadata reading and CLI parsing live only under `skeptic.cli.*`; library namespaces never read project layout — they receive paths as arguments.
- `skeptic.host.*`: host runtime dependency declaration. `skeptic.host.deps/host-deps` is a literal quoted vector of `[coord version]` tuples — the coordinates the lein-skeptic launcher resolves via aether to assemble the host JVM's `-cp`. Parallel to `skeptic.worker.deps/worker-deps` for the worker JVM. `skeptic/deps.edn`'s `:host` alias mirrors the same vector for ad-hoc invocations like `clj -A:host`. The deps.edn tool path (`clj -T:skeptic check`) is already hermetic by virtue of how `clj -T` runs tools, so it does not need an explicit host-deps resolution step. See **Host Runtime Isolation** below.
- `skeptic.worker.*`: worker JVM that runs analysis in the project's runtime. Host code lives outside this family and never analyzes project source directly. Both entrypoints build the launch classpath as `(concat project-cp runtime-entries)` through `skeptic.worker.classpath`, resolving `skeptic.worker.deps/worker-deps` with their own build system (tools.deps for deps.edn, lein's aether wrapper for Lein). The deps.edn entrypoint spawns via `skeptic.worker.process`; the Lein launcher owns a worker subprocess created from Lein's prepared project JVM command with that runtime tail appended (the launcher inlines the worker classpath assembly because it cannot `:require skeptic.*`; equivalence with `skeptic.worker.classpath/worker-classpath-entries` is asserted by a launcher-side test). `skeptic.worker.{server,client,transport,wire}` carry the host↔worker nREPL transport with explicit teardown and non-EDN sentinel handling; `skeptic.worker.{analyzer_clj,analyzer_cljs}` are the in-worker analyzer entrypoints that read project source by path and project annotated AST data back over the wire; `skeptic.worker.project_context` holds project-state hand-off. See **Worker Runtime Isolation** below for the contract.
- `skeptic.cljs.*`: ClojureScript loading and admission helpers. `analyzer-driver` exposes stateless cljs analyzer entrypoints via `cljs.analyzer.api` (no compiler state threaded across calls). `schema-interpreter` interprets post-macroexpansion Plumatic Schema bodies in a sci sandbox.
- `skeptic.checking.*`: checking-time orchestration — AST/form/opts/state helpers consumed by the pipeline.
- `skeptic.schema`, `skeptic.schema.collect[.cljs]`, `skeptic.typed-decls[.malli]`: declaration admission pipeline. Reads var metadata, admits raw Plumatic/Malli declarations, converts admitted descriptions into typed entries via `schema->type` / `malli-spec->type`.
- `skeptic.malli-spec`, `skeptic.malli-spec.collect[.cljs]`, `skeptic.analysis.malli-spec.bridge`: MalliSpec admission and the Malli→Type bridge. See the MalliSpec Domain section below for the conversion contract.
- `skeptic.analysis.bridge` and `skeptic.analysis.bridge.*`: Schema→Type boundary. Entry point is `schema->type`; siblings `canonicalize`, `localize`, `render`, `algebra` hold schema-form normalization, project-context resolution, display rendering, and set/join helpers respectively.
- `skeptic.provenance`, `skeptic.provenance.schema`: Provenance API and schema contracts. See the Types Carry Provenance section below.
- `skeptic.analysis.types`, `skeptic.analysis.types.{schema,proto}`, `skeptic.analysis.type-ops`, `skeptic.analysis.type-algebra`: canonical semantic type data model, schema contracts, the protocol used to tag type records, and helpers that build/normalize/union/intersect/de-`maybe` types. Ordered sequential collections use a single `SeqTRec` with `:ordered-coll-kind ∈ {:vector :sequential}` and a regex-atom `pattern` field — pattern atoms are tagged `{:kind :one :type T}` or `{:kind :star :type T}`, invariant `(:one)* (:star)?`. Adapters: `pattern-prefix`, `pattern-tail`, `pattern-from-prefix-tail`. Canonical predicate is `seq-type?`; vector-only is `(and (seq-type? t) (= :vector (:ordered-coll-kind t)))`.
- `skeptic.analysis.annotate` and `skeptic.analysis.annotate.*`: analyzer AST typing/inference. Dispatcher in `annotate`; `annotate.api` is the public accessor/mutator surface; sub-namespaces split work by AST op family (`cljs` sub-namespace handles host-call/host-field/js/js-var when the source file is cljs).
- `skeptic.analysis.calls`, `skeptic.analysis.native-fns`, `skeptic.analysis.predicates`, `skeptic.analysis.predicate-descriptor`, `skeptic.analysis.value[-check]`, `skeptic.analysis.map-ops[.algebra]`, `skeptic.analysis.narrowing`, `skeptic.analysis.origin`, `skeptic.analysis.sum-types`, `skeptic.analysis.{ast-children,annotation}`: analysis helpers — call resolution, native callable metadata, predicate parsing, runtime-value typing/compatibility, flow-sensitive refinement, assumption/origin tracking, closed-sum exhaustiveness, AST traversal.
- `skeptic.analysis.call-kinds.*`: per-call-kind dispatch zone. Public API: eight question-answering fns (`invoke-output-type`, `static-call-output-type`, `invoke-numeric-narrow-type`, `static-numeric-narrow-type`, `call-test-assumption`, `if-test-conjuncts`, `literal-key-projection`, `static-get-map-key-lookup-origin`) and the public helper `region-conjuncts`. Sub-nses internally hold private kind sym-sets and shape predicates; consumers never see kind keywords, kind sets, or kind-named predicates. **Rule:** nothing outside `skeptic.analysis.call-kinds.*` references `*-call-syms`, `*-invoke-syms`, `*-call?`, `*-invoke?`, or `static-*-call?` (exceptions: `static-nil?-call?` and `static-nil?-target` on `calls.clj`, which are structural JVM-shape predicates, not call-kind sets; the `type-predicate-*` family on `calls.clj`, which is type-predicate domain).
- `skeptic.analysis.cast` and `skeptic.analysis.cast.*`: cast engine in the type domain. Sub-namespaces split casts by target shape (`branch`, `collection`, `function`, `java-callable`, `map`, `quantified`, `result`, `schema`, `support`).
- `skeptic.analysis.schema-base`, `skeptic.analysis.schema.*`: legacy or boundary-facing schema-domain helpers. Keep new semantic work in the type domain unless an external schema-facing API specifically requires schema forms.
- `skeptic.inconsistence.*`: mismatch reporting — `report` builds summaries from cast results, `path` computes blame paths, `display` renders types for users, `mismatch` holds older message helpers.
- `skeptic.output[.text,.porcelain,.serialize]`: user-facing output. `output` selects the printer; `text` is ANSI, `porcelain` is newline-delimited JSON, `serialize` converts findings into JSON-ready data.
- `skeptic.{file,source,colours,type-vars}`: general support — file I/O, source lookup, terminal formatting, simple type-var values.
- `skeptic.classloader-fix`: JDK 9+ bootstrap-loader probe/cache prime. Gated; JDK 8 and `LEIN_USE_BOOTCLASSPATH=no` users pay zero cost.

Tests: `skeptic/test/...` mirrors source layout, namespaces have a `-test` suffix.

## Core Rules

1. No re-exports. Define functions in the namespace that owns them and require that namespace directly at call sites or in tests.
2. No `declare` unless strictly required for real mutual recursion. Keep the recursive runner small and pass it into non-recursive helpers.
3. The main internal language of the library is the type domain. Plumatic schemas are an input format, not the primary working representation.
4. Prefer `schema->type`, not the reverse direction. Convert at the boundary; do real analysis in the type domain.
5. The declaration dict holds bare Types. No sidecar wrappers (`:typings`, `:output-type`, `:arglists`, `:accessor-summary`, `:type`). Every Type carries its own `:prov`; declaration-level origin is also surfaced in a parallel Provenance map.

## API Boundaries

Some subsystems expose a dedicated API module that external callers must go through instead of reaching into implementation files:

- `skeptic.analysis.annotate.api` — the API for the annotate subsystem. Code reading or writing fields on annotated nodes must use its accessors/mutators (`node-form`, `node-type`, `with-type`). Direct field access on nodes is permitted only inside `annotate/*` files.
- `skeptic.analysis.bridge` — entry point for schema→type. Sibling files (`canonicalize`, `localize`, `render`, `algebra`) are required directly per rule 1; they are not "internals of bridge."
- `skeptic.analysis.cast` — cast dispatcher. Cast-result construction lives in `skeptic.analysis.cast.support`; diagnostic projection in `skeptic.analysis.cast.result`.
- `skeptic.provenance` — API for Provenance values. Consumers use `make-provenance`, `of`, `source`, `lang`, `provenance?`, `merge-provenances` — never the `Provenance` record constructor or raw `:source` / `:lang` field access. `prov/of` is strict: missing `:prov` throws. There is no `prov/unknown` sentinel.

When an existing API module lacks a helper that a new consumer needs, extend the API module rather than reaching past it.

## Recursive Runner Pattern

When recursive analysis is needed, structure it in two layers: one small **recursive runner** owns recursion and dispatch order; **non-recursive helpers** take plain data plus the runner as an argument, build child requests, run the runner on them, and aggregate results. Helpers do not call each other recursively. This keeps recursion explicit, local, and easy to reason about. The runner is stack-safe: it executes via a heap-allocated continuation stack so AST depth does not map to JVM stack depth.

## Domains

The library has three domains that must not be confused: Schema, MalliSpec, and Type.

Conversion is one-way into Type: `Schema → Type` (via `skeptic.analysis.bridge/schema->type`) and `MalliSpec → Type` (via `skeptic.analysis.malli-spec.bridge/malli-spec->type`). There is no `Type → Schema`, `Type → MalliSpec`, `Schema → MalliSpec`, or `MalliSpec → Schema`.

`:schema` as a keyword in this codebase means Plumatic Schema, always. Malli data at the admission boundary is carried as `:malli-spec`.

### Schema Domain

The schema domain is the external Plumatic Schema representation: `s/Int`, `s/Any`, `s/maybe`, map schemas like `{s/Keyword s/Int}`, optional keys, and other raw schema forms. It exists because users and external APIs write schemas; it is the input format at the boundary.

**Skeptic is the schema checker.** A `:- T` annotation on an `s/defn` parameter or return position is a **static contract** that Skeptic reads off var metadata at analysis time and checks every call site against. `s/with-fn-validation` is disabled during plugin runs and enabled during the test suite — runtime schema validation is a test-time aid, not the enforcement mechanism. `s/check` / `s/validate` are never the answer here: to make a new invariant load-bearing, declare it in the relevant `s/defn` signature so Skeptic sees it.

### MalliSpec Domain

The MalliSpec domain is the external [Malli](https://github.com/metosin/malli) representation — Malli vector/map/AST forms such as `:int`, `[:=> [:cat :int] :int]`, `[:map [:x :int]]`, `[:function ...]`. See `docs/malli-reference.md` for the forms Skeptic targets and what is stubbed.

The slice as it now stands:

- **Discovery:** `:malli/schema` Var-meta plus `(malli.core/function-schemas)` registry projection (captures `m/=>`). Unioned by `skeptic.malli-spec.collect/ns-malli-spec-results`.
- **Conversion (`malli-spec->type`):** admits via `m/form ∘ m/schema` and recursively dispatches the admitted form. Heads currently handled:
  - `[:=> [:cat & inputs] output]` → `FunT` with one `FnMethodT`.
  - `[:function & arms]` (each arm a `:=>`) → single `FunT` with one `FnMethodT` per arm.
  - `[:maybe X]` → `MaybeT`.
  - `[:or X Y ...]`, `[:and X Y ...]` → `UnionT` / `IntersectionT` over converted members (dedup/singleton-collapse matches the Schema-side `sb/both?` behavior).
  - `[:tuple X Y ...]` → `SeqT` with closed prefix-pattern, `:ordered-coll-kind :vector` (the cast engine treats `tail=nil :vector` as exact-arity).
  - `[:vector child]`, `[:sequential child]`, `[:set child]` → homogeneous `SeqT` / `SetT`. `:min`/`:max` and similar container properties are parsed and dropped.
  - `[:map [:k T] [:k {:optional true} T] ...]` → `MapT` with `ValueT` keys for required entries and `OptionalKeyT(ValueT(...))` for optional. The map-level `{:closed true}` property emits explicit keys only; otherwise (default open) a `Keyword → Dyn` domain entry is added.
  - `[:multi {:dispatch DISP} [tag schema] ...]` → `ConditionalT`. Branch descriptor is `{:path [DISP] :values [tag]}` for keyword dispatch, `nil` for fn-dispatch and the `:malli.core/default` sentinel.
  - `[:= X]`, `[:enum ...]` → exact value types.
  - `[:schema {:registry R} body]` merges `R` into the active-registry context and recurses on `body`.
  - `[:ref ::name]` resolves through the active registry; recursive position emits `InfCycleT`. Unknown refs are rejected by Malli's `m/schema` upstream.
  - Primitive leaves: `:int`, `:string`, `:keyword`, `:symbol`, `:boolean`, `:double`, `:float`, `:nil`, `:qualified-keyword`, `:qualified-symbol`, `:any`, plus recognized bare predicate symbols. `:uuid` admits to `Dyn`. `:char`, `:pos-int`, `:neg-int`, `:nat-int` are not in Malli 0.20.1's default registry.
  - Anything else (sequence/regex combinators outside the `:=>` head, etc.) returns `Dyn`.
- **Strict separation at admission, merge at the pipeline boundary:** `typed-ns-results` returns Schema-derived entries plus `:skeptic/type-overrides`; the Malli collector and bridge remain domain-pure. `skeptic.checking.pipeline/namespace-dict` combines per-namespace schema, malli, and native-fn entries through `merge-type-dicts`; once inside the merged dict, all entries reach the analyzer in the same shape.
- **Disable gates:** `:plumatic-disable` / `:malli-disable` opts (CLI `--plumatic-disable` / `--malli-disable`) early-return the relevant intake. Three gate points must stay symmetric: `skeptic.typed-decls/typed-ns-results`, `skeptic.typed-decls.malli/typed-ns-malli-results`, and `skeptic.checking.pipeline/ns-var-provs` plus `project-var-provs`. Adding either stream's disable behavior anywhere new requires updating both halves so a finding's `:source` cannot announce a stream that produced no admission.
- **Deferred:** sequence/regex combinators outside the `:=>` head; JSONL Malli kinds; `.skeptic/config.edn` Malli surface; `:skeptic/type` Malli interpretation; analyzer-side wiring of Malli-derived types as a separate type source.

### Type Domain

The type domain is the library's internal semantic representation, defined in `src/skeptic/analysis/types.clj`. It is the concrete family of internal values built by `at/->*` constructors (`->DynT`, `->BottomT`, `->GroundT`, `->NumericDynT`, `->RefinementT`, `->AdapterLeafT`, `->OptionalKeyT`, `->FnMethodT`, `->FunT`, `->MaybeT`, `->UnionT`, `->IntersectionT`, `->MapT`, `->SetT`, `->SeqT`, `->VarT`, `->PlaceholderT`, `->InfCycleT`, `->ValueT`, `->TypeVarT`, `->ForallT`, `->SealedDynT`, `->ConditionalT`) and recognized by the matching `at/*-type?` predicates.

This is the domain where the cast engine, blame logic, compatibility checks, and most internal reasoning happen. Boundary-only code may touch the schema/malli domains; everything else should be operating on Type records and branching with `at/*-type?` predicates.

## Boundary Rule

Convert from an external domain into the type domain at the boundary: `schema->type` for Plumatic, `malli-spec->type` for Malli. Canonicalize input if needed, convert, then do all real work in the type domain; only return to schema-level forms when an external schema-facing API specifically requires it. Avoid flows that treat raw schemas as the analysis language or reconstruct schemas to continue internal reasoning.

## Dict, Provenance, and Equality

Once Schema and MalliSpec values have crossed their respective boundaries, the per-namespace declaration dict returned by `skeptic.checking.pipeline/namespace-dict` holds **bare Types** keyed by qualified symbol. No sidecar wrappers (`:typings`, `:output-type`, `:arglists`, `:accessor-summary`, `:type`) live on dict values. The direct admission flow per source is:

- `Schema → schema->type → dict[sym] = Type` (via `skeptic.typed-decls`)
- `MalliSpec → malli-spec->type → dict[sym] = Type` (via `skeptic.typed-decls.malli`)
- `native → Type → dict[sym] = Type` (via `skeptic.analysis.native-fns`)
- `:skeptic/type-overrides → dict[sym] = Type` (via opts consumed by `skeptic.typed-decls`)

There is no intermediate entry-map shape between admission and dict insertion.

Every Type is a `defrecord` with `:prov` as field 1. The constructor contract is absolute: every positional `at/->*` arrow constructor and every `at/Dyn`/`at/NumericDyn`/`at/BottomType` helper takes a Provenance as first argument. `prov/of` is the canonical reader and throws if `:prov` is absent — there is no fallback and no `prov/unknown` sentinel.

Origin of an entry (Schema / MalliSpec / native / type-override / inferred) is carried on each admitted Type's `:prov` and also surfaced as a `Provenance` record in the parallel `:provenance` map produced by `namespace-dict`, keyed by the same qualified symbol. Downstream checking consumes the dict for type reasoning and consults provenance only to attach `:source` on findings — never to reconstruct the original schema/malli-spec.

Ingestion boundaries supply the root Provenance: `schema->type` and `bridge.localize/*` → `:schema`; `malli-spec->type` → `:malli`; `native-fns/static-call-native-info` → `:native`; `:type-overrides` → `:type-override`; analyzer annotation, `exact-value-type`, `type-of-value` → `:inferred` (taken from `prov/with-ctx` when inside the analyzer).

**Combinator rule (container-owns-identity):** when a combinator joins or merges existing Types into a new composite (union, intersection, merged map, joined seq), the result carries an **anchor provenance** supplied by the caller — the container's own prov — not a provenance derived from the items. Items keep their own provs; the composite owns its own. Combinators requiring an anchor take it as an explicit first parameter (`av/join`, `amo/merge-map-types`, `amoa/merge-types`, `coll/concat-output-type`; `shared-call/shared-call-output-type` derives the anchor from `default-output-type`).

Each Provenance also carries a `:lang` field — one of `:clj`, `:cljs`, or `#{:clj :cljs}` — identifying the host language. A `.cljc` Var admitted by both passes is merged into `#{:clj :cljs}`. The `skeptic.provenance/lang` reader and `skeptic.provenance.schema/Lang` schema are the only valid surfaces; raw `:lang` access is reserved for bridge code that mints provs.

**Equality:** use `at/type=?` to compare Types by shape (it strips `:prov` recursively). Plain `=` on defrecords also compares `:prov` and will produce false negatives. `at/dedup-types` deduplicates by shape while preserving one representative.

## Design Bias

- Keep cast and analysis helpers small, focused, and type-domain-first.
- Preserve ordered dispatch when branch priority is semantically meaningful.
- Treat schema-level helpers as boundary adapters, not as the core analysis model.

## Host Runtime Isolation

Skeptic's host code runs in a JVM whose classpath is **only**
Skeptic-resolved jars: no project `:dependencies`, no other lein
plugin transitives, no shared classloader with the surrounding
build tool. The deps.edn tool path (`clj -T:skeptic check`) gets
this for free — `clj -T` is hermetic by construction. The Lein
plugin path gets it through the hermetic-host launcher, which
runs `leiningen.skeptic/skeptic` as a thin task body inside lein's
JVM and then spawns a fresh host JVM that carries no Skeptic-side
state from lein.

The contract is strict and load-bearing:

- **Lein-skeptic ships zero transitives.** The plugin's
  `:dependencies` and `:managed-dependencies` are empty. Aether
  mediation at lein plugin load sees one coord with no
  transitives, so `:pedantic? :abort` projects do not abort on
  lein-skeptic. Other plugins' transitives (codox's old cljs,
  lein-doo's tools.reader, etc.) cannot win a host-side
  classloader race against Skeptic's pinned versions, because
  Skeptic's pinned versions are not on lein's classloader at all.
- **The launcher resolves host-deps via aether against a
  synthetic project.** `leiningen.skeptic/resolve-host-jars`
  builds `{:dependencies <host-deps> :repositories
  (:repositories project) ...}` and calls
  `leiningen.core.classpath/resolve-managed-dependencies` —
  `:add-classpath? true` is forbidden, so resolved jars NEVER
  land on lein's classloader. The synthetic project inherits the
  repo-connection keys (`:repositories`, `:mirrors`,
  `:certificates`, `:local-repo`) so private repos and proxies
  work, but never the user's `:dependencies`, `:plugins`, or
  `:managed-dependencies`.
- **Host-deps is data, not code.** `skeptic.host.deps/host-deps`
  is a literal quoted vector with no `require`/`read-string`/
  runtime evaluation in the file. The launcher reads it out of
  the Skeptic jar as text without loading the namespace
  (`leiningen.skeptic/read-skeptic-vector`); the same mechanism
  reads `skeptic.worker.deps/worker-deps`. This keeps Skeptic's
  whole universe off lein's classloader: even the var that
  declares the deps is never resolved on the launcher side.
- **The launcher namespace requires no `skeptic.*`.** It depends
  only on `leiningen.core.*`, `clojure.java.io`, and
  `clojure.string` — all already on lein's bundled classpath.
  Anything Skeptic-side the launcher needs at task time
  (`worker-classpath-entries` shape, source-path discovery from
  the project map) is inlined; equivalence with the canonical
  Skeptic-side implementation is asserted by tests.
- **The host JVM connects to an already-running worker.** Alt B
  of the design: the launcher (in lein) spawns the worker JVM
  first via `leiningen.core.eval/shell-command project
  worker-form` so the worker carries lein's full project
  preparation (`:jvm-opts`, `:global-vars`, `:injections`,
  `:warn-on-reflection`, `:prep-tasks`, env), waits for the port
  handshake, then spawns the host JVM and tells it the worker's
  port over stdin EDN. The host's `:worker-spawn` fn is a stub
  returning `{:port <int> :stop-fn (constantly nil)}` — the host
  does NOT spawn the worker, the launcher owns both subprocess
  lifecycles.
- **The launcher carries the user's CLI args forward, raw.**
  `leiningen.skeptic/skeptic` does no CLI parsing beyond
  recognizing literal `--help` / `-h` to short-circuit a JVM
  spawn. Other args are written into the host's stdin EDN under
  `:args` and parsed host-side via `skeptic.cli.options/parse`.
  New flags need only a Skeptic release; lein-skeptic does not
  need a coordinated bump.
- **Host JVM does NOT inherit lein-only env vars.** The launcher
  strips `LEIN_JVM_OPTS`, `DRIP_INIT`, `DRIP_INIT_CLASS`, and
  `JVM_OPTS` from the host process's environment. `JVM_OPTS` is
  intended for the project subprocess (lein convention); the host
  is neither lein's JVM nor the project's subprocess.
- **Tools.reader 1.0.0-beta3 floor.** `host-deps` pins
  `tools.reader 1.6.0`. Pre-1.0.0-beta3 the
  `SourceLoggingPushbackReader` is not `Closeable`, so the host's
  `with-open` in `skeptic.file/ns-for-clojure-file` would
  reflectively call `.close` on a class that has no such method
  and crash. Aether mediation against the synthetic project picks
  the explicit pin over any transitive's older version. The pin
  is bumped via Skeptic library version bump, not user override.
- **Stdout from the host JVM is the user-facing output.** The
  launcher pipes host stdout to lein's stdout directly. Worker
  stdout/stderr is drained by the launcher and forwarded to lein
  stderr only under `-v`; never enters user-facing stdout.
- **Launcher shutdown sequence on host exit.** `(1)` waitFor the
  host process; `(2)` signal the worker to stop (currently via
  `Process.destroy`; future work: nREPL `:op shutdown` once a
  no-Skeptic-deps client lives in the launcher); `(3)` reap the
  worker with a bounded waitFor + destroyForcibly tail. JVM
  shutdown hooks on both subprocesses cover Ctrl-C / SIGTERM on
  the lein side.

## Worker Runtime Isolation

Analysis runs in a separate worker JVM inside the **project's own**
runtime. Host code (`leiningen.skeptic`, `skeptic.tool`,
`skeptic.cli.main`, `skeptic.core`, the checking pipeline, the cast
engine, the bridge) remains outside the project analyzer. The worker JVM
is what reads project source and emits analyzer ASTs.

The boundary exists so the project's pinned versions of Clojure,
Plumatic Schema, Malli, `tools.analyzer`, and any other library drive
the analyzer. Skeptic's own declared versions of those libraries no
longer collide with the project's.

The contract is strict and load-bearing:

- **Worker classpath is project-first; the worker runtime is resolved
  separately.** Both entrypoints build the launch classpath as
  `(concat project-cp runtime-entries)` through
  `skeptic.worker.classpath` — deps.edn resolves
  `skeptic.worker.deps/worker-deps` via tools.deps, Lein via its aether
  wrapper, and the Lein path starts from Lein's prepared project JVM
  command (profiles, injections, global-vars, jvm-opts, env). Skeptic's
  coordinates never enter the project's `:dependencies`: dependency
  mediation must not blend Skeptic's graph with the project's, because
  mediated versions vary per project and per machine. A symbol present
  in both Clojures is a wrong-jar bug, not a version mismatch; treat it
  as a classpath ordering failure.
- **Project code loads as the project: the worker only ever loads a
  checked namespace via the project's own `(require ns)`.** Lein project
  profiles and injections run before `run-worker!`, which then runs every
  project analysis operation on the `clojure.main` launch thread itself.
  Clojure's require resolves the namespace against the classpath (AOT
  `__init.class` when strictly newer, else `.clj`, else `.cljc`) exactly
  as the project's own boot would, and a namespace an earlier require
  already pulled in is not re-evaluated. Skeptic never selects or
  evaluates a namespace's defining file itself — a namespace provided by
  several files is the require's decision, observed afterwards from the
  loaded vars' `:file` metadata (`.scratch/loader-probes/`). Capture is
  reading, never loading: after require, the worker re-reads the source
  text without evaluation on the same thread, against the runtime's own
  registries (`data_readers.clj`, injection `set!`s, `alter-var-root`).
  A tagged literal whose tag no registration resolves at re-read time
  (e.g. one registered by a mid-file `set!` of `*data-readers*`, which
  is local to the load's own binding frame — probe4/probe5 in
  `.scratch/reader-probes/`) reads as its `tagged-literal` placeholder:
  an unknown value, typed Dyn, never a skeptic error on a file the
  project loads fine. A file the project's own load rejects fails inside
  require and surfaces as that namespace's finding.
- **The host owns the worker process.** The Lein plugin drains both
  child streams itself, forwards them only to host stderr under `-v`,
  captures startup output for failure diagnostics, and never lets
  worker chatter enter porcelain stdout. Startup waits for either the
  port handshake or process exit, with no arbitrary elapsed-time
  cutoff. Teardown requests shutdown and forcibly terminates the owned
  process if the request fails or the child does not exit.
- **Worker reads its own source.** The host sends paths over the
  wire, never forms. The bulk `analyze-namespace` op opens the file
  inside the worker and returns the projected AST. The streaming op
  orders requested namespaces dependency-first (ns decls parsed with
  tools.namespace), so a broken namespace's load failure is attributed
  to that namespace itself rather than to the first dependent whose
  transitive require pulls it in. The host never collapses discovery to
  one file per namespace: every discovered `[ns file]` pair streams to
  the worker, duplicates flagged on the wire; the worker answers a
  flagged file that the loaded runtime says another file defines with
  `:shadowed-by`, and the host reports it as an `ns-discovery-warning`
  instead of checking text the project never loads. There is no
  round-trip op that takes a form on the host, ships it to the worker,
  and ships back an AST — that shape was deliberately removed and must
  not return.
- **Skeptic, schema, malli, and admission are host-only.** The
  worker never runs `schema->type`, `malli-spec->type`, the cast
  engine, the bridge, or any `skeptic.*` analysis namespace.
  Malli's registry is data on the wire (`m/=>` carried as
  `:form`, `mx/defn` carried as `:source-form :-`), not a
  registry projection. The host runs the admission and conversion;
  the worker ships var-meta as inert data.
- **The transport is nREPL with explicit teardown.** EDN over the
  bencode transport strips form metadata, so the worker reattaches
  `:form` metadata as plain data and the host re-materializes it
  (using `clojure.walk/postwalk` for clj ASTs, `:children`-only
  descent for cljs ASTs — never `postwalk` over a cljs AST,
  which runs away into `:env`/`:init` back-refs). Blame and source
  location are read from metadata, not from AST structure.
- **Worker resolves vars via `ns-interns`, not `ns-resolve`.**
  `ns-resolve` also resolves classes via `Class/forName`, which
  hits sibling-class collisions on the project classpath. Use the
  intern table.
- **Worker-side source compiles on the project's pinned Clojure —
  floor is 1.7.** Every namespace the worker loads
  (`skeptic.worker.{server,analyzer-clj,analyzer-cljs,client,transport,transport-impl,wire}`)
  and the Lein-side init form compile under Clojure 1.7, because the
  project's Clojure wins via project-first ordering. No
  `requiring-resolve` (1.10) — use explicit `require` plus `resolve`.
  No 1.9+ predicates (`boolean?`, `any?`, `seqable?`, `ident?`,
  `pos-int?`, ...) — use `instance?` checks. No `clojure.string/includes?`
  / `starts-with?` / `ends-with?` / `last-index-of` (1.8) — use
  `(.indexOf ^String s ^String sub)`.

`s/defn` is normalized to `defn` inside the worker before analysis,
so the `:def` node is visible to def-discovery; the raw form is kept
as `:source-form`.

## Releasing and CI

Version bumps, `script/verify-monorepo-versions.sh`, GitHub Actions workflows,
and Clojars deployment are documented in [../docs/releasing.md](../docs/releasing.md)
at the repository root.
