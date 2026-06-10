# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Expanded Malli admission to cover `:and`, `:tuple`, `:vector`,
  `:sequential`, `:set` (with `:min`/`:max` parsed and dropped), `:map`
  with required keys, `{:optional true}` keys, and the `:closed true`
  property (default is open, matching Malli's semantics — extra keyword
  keys are admitted with `Any` values); multi-arity `:function` with
  per-arm `FnMethodT`; `:multi` with `{:dispatch :kw}` tagged dispatch
  (later branches are narrowed by negation of earlier tags); `:=`;
  `:schema` with optional `{:registry {...}}` properties carrying a
  local registry; `:ref` resolved through the active registry with
  cycle detection (recursive positions emit `InfCycleT` rather than
  diverging); and the primitive leaves `:double`, `:float`,
  `:qualified-keyword`, and `:qualified-symbol`. Sequence/regex
  combinators outside the `:=>` head remain experimental.
- ClojureScript source-file support. Skeptic discovers `.cljs` and
  `.cljc` files in deps.edn, Leiningen, and Shadow-CLJS projects and
  admits Plumatic Schema (`s/defn` / `s/def` / `s/defschema`) and
  Malli (`:malli/schema`) declarations on cljs vars. `.cljc` files
  are admitted twice (once per host language); identical findings
  from both passes are deduped with `lang` set to `["clj","cljs"]`.
- `clj -T:skeptic check` deps.edn tool entrypoint, so Skeptic is now
  usable from a `deps.edn` `:tools/usage` alias in addition to the
  Leiningen plugin. Supported tool arg-map keys include `:project-dir`,
  `:paths` (override discovered source paths), and `:alias` (merge
  additional deps.edn aliases when resolving the project's basis).
  `clojure -M:skeptic` and `clj -X:skeptic` print a message redirecting
  to the `-T` form.
- Analysis now runs against the **project's own classpath**: the
  project's pinned versions of Clojure, Plumatic Schema, Malli,
  `tools.analyzer`, and any other library are what drive checking.
  Skeptic's own declared dependency versions no longer collide with
  the project's.
- `--cljs-disable` flag to skip ClojureScript admission entirely. With
  the flag set, `.cljs` files are dropped and `.cljc` files are
  admitted as `:clj`-only — the `:cljs` reader-conditional branch is
  discarded.
- `--plumatic-disable` and `--malli-disable` flags to switch off either
  intake stream entirely. A disabled stream contributes no entries and
  no findings whose source matches that stream. `--plumatic-disable`
  additionally suppresses `:skeptic/type-overrides`, since overrides
  are a Plumatic-domain construct. A Var declared via both streams is
  still admitted via the enabled one; combining both flags leaves only
  Skeptic's built-in native-fn declarations.
- `lang` field on every JSONL `location` object identifying the host
  language the reported type was admitted under: `"clj"`, `"cljs"`, or
  a sorted JSON array `["clj","cljs"]` when both passes of a `.cljc`
  file produced the same finding.
- Function values are now checked against Java callable-interface
  targets (`Runnable`, `Callable`, `Comparator`, and the
  `java.util.function.*` single-abstract-method interfaces).
  Previously a `:- Runnable` parameter produced a confusing
  `(=> Any) but expected java.lang.Runnable` mismatch for any Clojure
  fn; now Skeptic checks the source fn's arity against the interface's
  abstract-method arity and casts its declared return type to `Bool`
  for `Predicate` / `BiPredicate`, `Int` for `Comparator`, and `Dyn`
  for the rest.

### Fixed

- Leiningen analysis now runs the worker inside the project's prepared
  runtime: the plugin launches an owned subprocess from Lein's project JVM
  command (profiles, injections, global-vars, and jvm-opts included) with
  Skeptic's separately resolved worker runtime appended after the project's
  classpath entries. Project analysis operations execute on the
  `clojure.main` launch thread itself, so registered readers behave exactly
  as they do under the project's own runtime — `data_readers.clj` and
  injection `set!`s included, with no Skeptic-side reader machinery.
  The plugin keeps worker output off porcelain stdout,
  reports child startup output on launch failure, waits for startup without
  an arbitrary timeout, and preserves the original analysis exception across
  cleanup failures.
- The worker no longer retries a failed namespace `require` with
  `:reload-all`. The project's own runtime never retries a require, and
  the retry could make analysis succeed on source the project itself
  cannot load. A require failure now propagates exactly as the project's
  own `clojure.main` raises it.
- ClojureScript admission failures now report the actual underlying
  exception (e.g. the analyzer's `No such namespace: …`) instead of a
  placeholder `cljs admission failed for this source-file` with no
  cause, and the finding's `lang` is `cljs` instead of `clj`. Exception
  findings also state the factual consequence for checking coverage
  ("Checking of this file was aborted.", "Call sites were checked as if
  this var had no declaration.") instead of narrating Skeptic's
  error-routing.
- Runtime objects that data readers place into analyzed source (e.g. a
  `#date-time` tagged literal producing a joda `DateTime`) no longer
  kill analysis with the marshaller's
  `Not supported: class org.joda.time.DateTime`. Wire safety for value
  leaves is now decided by the transit-verified leaf set (char, UUID,
  exact `java.util.Date`, plus plain EDN scalars and collections), not
  by a pr-str round-trip — a project `print-method` that emits a
  readable form had made live objects look plain while the marshaller
  had no handler for them. Such values cross as class-carrying opaque
  sentinels and are typed by their class at call sites. A transit
  default-handler backstop additionally converts anything that slips
  past projection into a class-name-plus-print sentinel instead of
  throwing, logging one stderr line per value so projection gaps stay
  visible without failing the run.
- Host↔worker transport failures are loud and immediate instead of
  silent or repetitive. A reply arriving for a foreign request id —
  previously skipped by single-reply receives and a silent end of
  streaming receives, leaving the run to finish "green" on partial
  results — now throws with the stray message attached. The worker's
  bulk-analysis stream aborts at the first namespace whose reply cannot
  be sent (naming it) instead of analyzing every remaining namespace
  into a dead socket and printing a send failure for each. A socket
  read timeout no longer masquerades as end-of-stream, and transport
  closes are logged to stderr so the order of connection teardown is
  visible in worker output.
- Plumatic Schema map schemas using non-keyword required keys
  (e.g. `{(s/required-key "a") s/Int}`) now check correctly: correct
  call sites are no longer flagged with a spurious unexpected-key
  finding, and missing-required-key cases are now reported instead of
  being silently dropped. Keyword required keys were already handled
  by Plumatic's short-circuit to bare keywords; the bug only affected
  string and other non-keyword keys.
- Analysis no longer throws `Expected typed entry` on Malli `:map` or other
  unsupported Malli forms encountered while building the per-namespace
  declaration dict.
- `(assoc nilable-map k v)` (and `update` on a nilable map) no longer
  carries a `Maybe` wrapper through the result, since `(assoc nil k v)`
  returns `{k v}` — not `nil`. The nil-branch is captured by downgrading
  the inner map's required keys to optional, so callers that previously
  saw a spurious nullability finding now get a clean check, while callers
  that depended on inner keys being required see a `:map-nullable-key`
  finding instead of a false pass.
- `(when (or (nil? a) (nil? b) ...) (throw ...))` now narrows every
  guarded local in subsequent statements, not just the single-variable
  shape. Previously a disjunctive throw-guard's De-Morgan negation was
  treated as unsupported, so the rest of the enclosing `do` continued to
  see each local as `(maybe T)` — yielding a spurious nullability
  finding on a correct program. The conjunctive shape
  `(when (and (nil? a) (nil? b)) (throw))` was already handled and is
  unchanged.

## [0.8.1] - 2026-05-05

### Added

- Per-namespace error counts at the end of text and JSONL reports, so
  large runs show which namespaces are worst-offending without piping
  through `wc`.

### Fixed

- Collections that mix a fixed prefix with a homogeneous tail
  (e.g. `[(s/one s/Int 'a) (s/one s/Str 'b) s/Keyword]`) are now
  checked correctly. Previously Skeptic treated every collection
  schema as either fully fixed-arity or fully homogeneous (#8).
- Earlier `cond` / `case` branches now restrict later ones. A
  predicate test that succeeds on one arm narrows the remaining
  arms by negation, so a `(some? v)` arm following `(vector? v)`
  sees `v` as a non-vector non-nil, and downstream sum-type
  variants flagged exhausted are removed from the residual (#9).

## [0.8.0] - 2026-05-03

### Added

- Initial Malli intake. Skeptic now reads `:malli/schema` Var metadata
  and projects the compile-time `(malli.core/function-schemas)`
  registry (which captures `m/=>`). The first batch of admitted
  forms covers single-arity `[:=> [:cat ...] out]` function schemas,
  `:maybe`, `:or`, `:enum`, the primitive leaves `:int`, `:string`,
  `:keyword`, `:symbol`, `:boolean`, `:nil`, and `:any`, and the bare
  predicate symbols recognized by Skeptic's predicate registry.
  Unsupported Malli forms admit as dynamic. Broader Malli shapes are
  added in subsequent releases.
- `--explain-full` flag to print fully expanded structural forms in
  type-mismatch output. Without the flag, declared Schema names are
  preferred so reports stay compact.
- Findings carry source attribution. Text output annotates each
  location with `[source: schema|malli|native|type-override|inferred]`
  and JSONL findings include the same source on the `location` object,
  so consumers can tell which intake stream produced the expected
  type. The corresponding `Provenance` is recorded on every admitted
  Type and is used to render the `:source` field.

### Changed

- Default type rendering uses declared Schema and Malli names where
  available, falling back to fully expanded structural forms only when
  pinned via `--explain-full`. Reports for `s/maybe`, `s/eq`, declared
  map schemas, and similar shapes are noticeably shorter.
- `lein skeptic` runs the analysis pass under
  `schema.core/without-fn-validation`, avoiding Plumatic Schema
  function-validation overhead in projects that have runtime Schema
  validation enabled. Runtime validation is restored after Skeptic's
  pass completes.

### Fixed

- Multi-arity `defn` output is now checked per arity against the
  declared return type for that specific arity, instead of comparing
  every analyzed method against the first arity's declared output.
- Flow-sensitive narrowing flows through more shapes: aliases,
  let-bound vars, path-shaped map projections, conditional branches,
  and enum/sum-type tests. A `(some? n)` or `(string? s)` guard now
  refines the local in subsequent expressions even when the local
  reaches the test through a structured-origin path.
- Conditional narrowing fixes for let-vars and `and`/`or` paths so
  branch tests on a let-bound local refine subsequent reads of that
  local (previously the refinement was lost across the let boundary).
- Dynamic types narrow through ground-classifying positive predicates
  (`string?`, `int?`, `keyword?`, etc.), so a `Dyn` value tested
  positively against a recognized predicate is treated as the
  corresponding ground type for the remainder of the then-branch.

## [0.7.1] - 2026-04-22

### Added

- `-o`/`--output OUTPUT_FILE` on `lein skeptic` so skeptic's findings, summary, or JSONL stream can be written to a file while lein/JVM chatter stays on stdout (#2).

### Changed

### Fixed

- `(or x fallback)` no longer reports a spurious nullability error when `fallback` is truthy.
- Flow-sensitive narrowing now flows through named destructured map keys: a
  `(str/blank? a)` or `(some? a)` guard on a `{:keys [a]}` destructure refines
  the local `a` itself, not only the parent map, so downstream reads of `a`
  see the narrowed type.

## [0.7.0] - 2026-04-19

### Added

- Clojars coordinates `org.clojars.nomicflux/skeptic` and `org.clojars.nomicflux/lein-skeptic`, `:deploy-repositories` for Leiningen, CI checks that keep the library and plugin versions aligned before publish, and GitHub Actions for **Release lifecycle** (orchestrates phases), **Change project versions** (reusable version bump), and **Publish to Clojars** (reusable deploy to Clojars).
- GitHub Actions CI running automated checks on pushes and pull requests.
- `lein skeptic -p` / `--porcelain` for newline-delimited JSON output (one
  JSON object per line), documented in the README.
- `lein skeptic --profile` for optional CPU, memory, and wall-clock profiling;
  when combined with `--porcelain`, the profile summary is written to stderr so
  stdout stays JSONL-only.
- Optional project `.skeptic/config.edn` with `:exclude-files` (root-relative
  globs that skip loading and checking matched paths) and `:type-overrides`
  (Plumatic Schema forms evaluated with `schema.core` in scope and merged into
  collected declarations, including `:output`-only overrides).
- Fine-grained check controls: `:skeptic/ignore-body` and `:skeptic/opaque` on
  `s/defn` attribute maps, and `^{:skeptic/type T}` on expressions (see README
  *Suppressing checks*).
- An expanded user-facing `README.md` that explains what Skeptic checks, how
  the plugin works, how to install it, how to interpret its reports, where to
  find the algorithm reference, and how to use configuration, JSONL output,
  suppressions, and a short “building from source” section.
- A new `--analyzer` CLI flag to print analyzer output while inspecting a
  namespace.
- Richer mismatch reports with source expressions, file and line information,
  enclosing forms, and affected inputs.
- Output validation for function bodies, so Skeptic now checks declared
  return Plumatic Schema annotations in addition to call-site argument
  compatibility.
- Static-call coverage and tests for Plumatic-Schema-sensitive operations such as `get`
  and `merge`.

### Changed

- Reworked the checker to analyze forms through `clojure.tools.analyzer` ASTs
  instead of the older macroexpansion-driven pipeline.
- Switched mismatch checking to a directional cast engine that reports cast
  rules, blame side, and blame polarity instead of only undirected mismatch
  against declared Plumatic Schema annotations.
- Switched namespace reading to `clojure.tools.reader` with source logging so
  reports can preserve source text and location metadata.
- Canonicalized Plumatic Schema handling across checker internals so built-in classes,
  Plumatic Schema aliases, map keys, and maybe types compare more consistently.
- Expanded namespace declaration collection to inspect schematized interned vars
  instead of only public vars.
- Further refactored analysis and the per-namespace checking pipeline, with
  continued improvements to the directional cast and blame reporting model
  (theory from Ahmed, Findler, Siek, and Wadler, *Blame for All*, POPL 2011; see
  [README](README.md#cast-checking-and-attribution)).
- Leiningen plugin uses an explicit Skeptic dependency profile so classpath wiring for the library stays clear.

### Fixed

- Preserved auto-resolved keywords from the target namespace when collecting
  declared Plumatic Schema annotations and checking forms.
- Reduced nested helper calls and map lookups to their final inferred types
  before reporting mismatches.
- Improved output mismatch rendering for declared map Plumatic Schema
  annotations so reported types are canonical and easier to read.
- Stabilized self-analysis and internal checking so recursive or self-hosted
  runs no longer depend on loader-sensitive semantic values embedded in
  canonical analysis data.
- Leiningen now surfaces a non-zero exit when Skeptic reports inconsistencies
  instead of treating the run as successful in some failure cases.
- Broader inference and checking fixes: flow-sensitive nil narrowing around
  `throw`, `case`, and `cond`; numeric tower comparisons; `clojure.string/blank?`;
  conditional branches; `maybe` / `eq` / nil edge cases; invoke analysis without
  redundant walks; cyclic type graphs; preservation of map projections through
  checks; and a single consolidated boundary for schema-side compatibility checks
  against inferred types.
