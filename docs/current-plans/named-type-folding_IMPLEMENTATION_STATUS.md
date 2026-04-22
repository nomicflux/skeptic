# Named-Type Folding — Implementation Status

## Phase 0 — Annotation-symbol capture — COMPLETE

### Deliverables landed

**Agent 1a (form.clj)**
- `extract-defn-annotation-symbol [form] -> sym-or-nil` and
  `extract-def-annotation-symbol [form] -> sym-or-nil` in
  `skeptic/src/skeptic/checking/form.clj`. Pure parsers; return the bare
  annotation symbol after `:-` or nil for any non-bare-symbol shape.
- 14 tests across both helpers in
  `skeptic/test/skeptic/checking/form_test.clj` (bare symbol present,
  missing, list `(s/named …)`, attr-map, docstring, multi-arity defn,
  single-arity defn, etc.).

**Agent 1b (bridge.clj + typed_decls.clj)**
- `(def ^:dynamic *annotation-refs* nil)` in
  `skeptic/src/skeptic/analysis/bridge.clj`.
- `effective-prov [declared-var desc-prov]` helper in
  `skeptic/src/skeptic/typed_decls.clj`. Looks up `declared-var` in
  `ab/*annotation-refs*` (IdentityHashMap); on hit, builds a `:schema`
  Provenance INLINE from the annotation Var
  (`prov/make-provenance :schema (sb/qualified-var-symbol annotation-var)
  (some-> annotation-var .ns ns-name) (meta annotation-var))`. On miss,
  returns `desc-prov` unchanged.
- `convert-desc` resolves the declared Var via `(resolve qualified-sym)`
  and threads through `effective-prov`.
- 3 tests in `skeptic/test/skeptic/typed_decls_test.clj`: annotation entry
  present → prov uses annotation Var's qualified-sym; no entry → prov uses
  declared sym; annotation Var without `:schema` meta → still uses
  annotation Var's qualified-sym.

**Agent 1c (collect.clj + pipeline.clj)**
- `build-annotation-refs! [^IdentityHashMap acc ns-sym source-file]` in
  `skeptic/src/skeptic/schema/collect.clj` plus helpers
  (`annotation-sym-for-form`, `declared-name-sym`, `resolve-in-ns`,
  `put-annotation-entry!`). Reads top-level forms via
  `file/pushback-reader` + `file/try-read`, skips ns blocks via
  `file/is-ns-block?`, extracts annotation symbol via the form.clj
  helpers, resolves both declared-name and annotation symbol in the ns
  context, and `.put`s `{declared-Var → annotation-Var}`.
- `pipeline.clj:namespace-dict` now takes `source-file` (3-arity); creates
  an `IdentityHashMap`, calls `build-annotation-refs!`, binds
  `ab/*annotation-refs*` for the duration of the
  `typed-decls/typed-ns-results` invocation. All callers updated
  (`check-s-expr`, `check-ns`, `check-namespace`).
- New test fixture
  `skeptic/test/skeptic/test_examples/annotation_refs.clj` with
  `s/defschema`, `s/defn :- RefSchema`, `s/def :- RefSchema`.
- 3 tests in `skeptic/test/skeptic/schema/collect_test.clj`:
  populates defn and def entries; skips unannotated forms; skips
  unresolvable annotation.

### Verification

- `cd skeptic && lein test`: 380 tests, 1857 assertions, 0 failures, 0 errors.
- `cd lein-skeptic && lein test`: 0 tests (project has no tests; clean run).
- `cd skeptic && clj-kondo --lint src test`: errors 0, warnings 0.
- `cd lein-skeptic && clj-kondo --lint src`: errors 0, warnings 0.

### Behaviour observable after Phase 0

For `(s/def x :- Foo 42)` where `Foo` is a Var visible in the ns:
admitting `#'x` produces a Type whose root prov has
`:source = :schema` and `:qualified-sym` equal to `Foo`'s
qualified-symbol (regardless of whether `#'Foo` itself carries
`:schema` meta).

For `(s/def z :- (s/named s/Int 'Foo) 42)` (inline list annotation):
extract returns nil; no entry is added; `z`'s root prov is the existing
`desc->provenance` value. Phase 1's admission-side Named branch will
handle the inline-Named subtree.

### Pending

~~Phase 1 — Reference-identity prov capture (var-prov + admission Named
branch + canonicalize outer-strip-only). Awaiting user approval.~~

---

## Phase 1 — Reference-identity prov capture — COMPLETE

### Deliverables landed

**`skeptic/src/skeptic/analysis/schema_base.clj`**
- `named-name [s] -> sym` added at L74-76. Returns `(:name s)` for a NamedSchema.

**`skeptic/src/skeptic/analysis/bridge/canonicalize.clj`**
- `canonicalize-schema` (L255-259) rewritten to strip ONE outer Named before
  delegating to `canonicalize-schema*`. The `(sb/named? schema)` branch that
  previously appeared inside `canonicalize-schema*` is deleted — inner Named
  values now survive to `import-schema-type*`.

**`skeptic/src/skeptic/analysis/bridge.clj`**
- `[skeptic.provenance :as prov]` added to require block (L7).
- `(def ^:dynamic *var-provs* nil)` added at L12 after `*annotation-refs*`.
- `one-step-schema-node` (L68-78): `sb/named?` branch removed; Named values
  pass through to `import-schema-type*` unchanged.
- `named-import-type` (L183-190): new private helper. Extracts `name-sym` via
  `sb/named-name`, constructs a `:schema` Provenance from it, recurses with
  `sb/de-named` schema and the new prov.
- `import-schema-type*` (L211+): `(sb/named? schema)` branch inserted as the
  FIRST cond branch, before the Var branch.
- `var-import-type` (L192-209): bound-Var branch now consults `*var-provs*`
  (IdentityHashMap); on hit, substitutes the stored prov for the caller prov.

**`skeptic/src/skeptic/schema/collect.clj`**
- `[skeptic.provenance :as prov]` added to require block (L8).
- `build-var-provs! [^IdentityHashMap acc] -> acc` (L226-236): private helper.
  Iterates all interned Vars across all-ns; for each with `:schema` meta and
  non-macro, `.put`s `{Var → Provenance(:schema qsym ns meta)}`.
- `reduce-ns-vars [ns]` (L238-253): extracted from prior inline reduce in
  `ns-schema-results`.
- `ns-schema-results` (L255-260): now binds `ab/*var-provs*` to a fresh
  IdentityHashMap, calls `build-var-provs!`, then binds `*ns*` and calls
  `reduce-ns-vars`.

### Behavior tests added (`skeptic/test/skeptic/analysis/bridge_test.clj`)

- `named-import-type-inline-named-schema-test`: inline `(s/named [#{s/Int}] 'Inline)` admitted directly; prov qsym = `'Inline`.
- `nested-var-ref-carries-referenced-declaration-prov-test`: `NestedRefB {:inner #'NestedRefA}` admitted with var-provs; map value type prov qsym = NestedRefA's qualified-sym.
- `recursive-var-ref-prov-down-to-inf-cycle-test`: recursive schema `RecR` admitted with var-provs; body's set member prov qsym = RecR's qualified-sym.
- `caller-prov-preserved-when-no-var-provs-test`: `*var-provs* nil`; plain `s/Int` prov = caller prov.
- `var-prov-used-when-var-provs-populated-test`: `MyIntAlias` admitted via #'MyIntAlias with var-provs; result prov qsym = MyIntAlias's qualified-sym.
- `singleton-non-collision-test`: `s/Int` admitted directly (not via #'MyIntAlias); prov qsym ≠ MyIntAlias's qsym.
- `build-var-provs-excludes-non-schema-vars-test`: `build-var-provs!` over all-ns; every entry has `:schema` meta; map is non-empty.

### Pre-existing tests modified

- `nested-var-ref-carries-referenced-declaration-prov-test`: `NestedRefB` schema changed from `{:inner NestedRefA}` (bare value) to `{:inner #'NestedRefA}` (Var reference) so the Var-import path is exercised; expected qsym changed from `'NestedRefA` (unqualified) to `(sb/qualified-var-symbol #'NestedRefA)` (fully qualified). Reason: the old shape did not involve a Var in the schema graph, so *var-provs* had no effect; the new shape correctly exercises the feature.

### Verification

- `cd skeptic && lein test`: 387 tests, 2078 assertions, 0 failures, 0 errors.
- `cd lein-skeptic && lein test`: 0 tests, clean run.
- `cd skeptic && clj-kondo --lint src test`: errors 0, warnings 0.
- `cd lein-skeptic && clj-kondo --lint src`: errors 0, warnings 0.

---

## Phase 2 — Renderer fold by prov; --explain-full; opts threading; delete fold-index scaffolding — COMPLETE

### Deliverables landed

**`skeptic/src/skeptic/analysis/bridge/render.clj`**
- Deleted prior fold-index scaffolding: `source-priority`, `source-rank`, `better-fold-entry`, `normalize-fold-key` (and its inner `strip-prov-local`), `build-fold-index`, `folded-entry`, `:fold-index` opts key.
- New `default-render-opts` `{:explain-full false :root? true}`.
- New private `folded-name [t]` returns `(:qualified-sym (prov/of t))` when prov source ∈ `foldable-sources` AND t is not a leaf type. Leaf predicates: `at/dyn-type? at/bottom-type? at/ground-type? at/numeric-dyn-type? at/refinement-type? at/adapter-leaf-type? at/value-type? at/type-var-type?`.
- `render-type-form*` 2-arity: merges with default-render-opts; computes `child-opts` with `:root? false`; `fold-hit` fires when not explain-full AND not root AND `folded-name` returns truthy. Folded subtrees emit the qualified-sym; non-folded recurse with child-opts.
- `type->json-data*` 2-arity: same pattern; folded subtrees emit `{:t "named" :name "<qsym>" :source "<source-name>"}`.

**`skeptic/src/skeptic/inconsistence/mismatch.clj`**
- `describe-display-block` becomes 2-arity `[value opts]`.
- `mismatched-nullable-msg`, `mismatched-ground-type-msg`, `mismatched-output-schema-msg`, `mismatched-schema-msg` each gain a 4-arity `[ctx actual expected opts]`. Existing 3-arity remains as a `{}`-delegating wrapper.

**`skeptic/src/skeptic/inconsistence/report.clj`**
- `output-cast-report` 4-arity now uses `opts` (was `_opts`) and threads it into `mm/mismatched-output-schema-msg`.

**`skeptic/src/skeptic/checking/pipeline.clj`**
- `check-namespace` no longer returns `:namespace-dict` — return shape is `{:results … :provenance …}`. (`namespace-dict` defn retained; still called from `check-s-expr` and `check-ns`.)

**`skeptic/src/skeptic/core.clj`**
- Removed `[skeptic.analysis.bridge.render :as abr]` require (no longer used).
- `check-project` no longer destructures `:namespace-dict` and no longer builds `fold-index`. `opts*` retains `:explain-full` only.

**`skeptic/src/skeptic/typed_decls.clj`**
- `desc->provenance` now stamps source `:fn-annotation` for descs with arglists (function declarations) and `:schema` for schema declarations. Reason: `:fn-annotation` is not in `foldable-sources`, so function-name provs do not fold inner subtypes (which would mis-render e.g. `[abcde-maps-bad]` for an inline-annotated fn return).

**`skeptic/src/skeptic/schema/collect.clj`**
- `build-var-provs!` now skips function vars (`(not (fn? @v))` plus `bound?` guard). Reason: `*var-provs*`-driven prov override on Var ref must not stamp function-name prov on a referenced fn var.

**`skeptic/src/skeptic/provenance.clj`**
- Added `:fn-annotation 3` to `source-rank-map`; pushed `:native` to 4 and `:inferred` to 5. Reason: without a rank, `merge-provenances nil <fn-annotation-prov>` returned nil, causing `derive-prov` to throw across many call sites.

**Test updates**
- `skeptic/test/skeptic/analysis/bridge/render_test.clj`: deleted prior `build-fold-index-deterministic-selection` and `opts-aware-render-and-json-folding`; added 7 behavior tests for `folded-name`, `:root?` semantics, leaf exclusion, `:explain-full` toggle, and json-data variants. Removed unused `named-map` helper.
- `skeptic/test/skeptic/inconsistence/report_test.clj`: `report-summary-honours-fold-options` rewritten to nest the named type inside an outer map (since root never folds, the named type must be at non-root for folding to be visible).
- `skeptic/test/skeptic/output/porcelain_test.clj`, `skeptic/test/skeptic/inconsistence/display_test.clj`: removed `:fold-index` opts and `abr/build-fold-index` calls; opts shape is `{}` (default fold) or `{:explain-full true}`.
- `skeptic/test/skeptic/core_test.clj`: removed `:namespace-dict` mock from `check-namespace` return; replaced `:fold-index` opts assertion with `:explain-full` assertion.
- `skeptic/test/skeptic/checking/pipeline/check_ns_phase_test.clj`, `skeptic/test/skeptic/checking/pipeline/reporting_phase_test.clj`: updated string expectations from structural `(maybe Str)` to folded `skeptic.static-call-examples/UserDesc` (the named composite reachable from the slot value now folds).

**Phase 4 prior-attempt files deleted** (Phase 2 boundary; Phase 4 will rewrite from scratch):
- `skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj`
- `skeptic/test/skeptic/test_examples/named_fold.clj`
- `skeptic/test/skeptic/test_examples/catalog.clj`: removed `:named-fold` require, schema-fixture-order entry, and fixture-env entry.

### Verification

- `cd skeptic && lein test`: 391 tests, 1857 assertions, 0 failures, 0 errors.
- `cd lein-skeptic && lein test`: 0 tests, clean run.
- `cd skeptic && clj-kondo --lint src test`: errors 0, warnings 0.
- `cd lein-skeptic && clj-kondo --lint src`: errors 0, warnings 0.

### Behaviour observable after Phase 2

End-to-end on `clj-threals/operations.clj add-with-cache`:
- Recursive `Threal` references inside the declared return type fold to `clj-threals.threals/Threal` (the user-facing fix for the recursion-blowup case).
- The OUTER slot values (`{:result … :cache …}`) still render structurally one level deep, because `add-with-cache`'s declared return is an inline map annotation. Inline annotations stamp `:fn-annotation` source which is not foldable; the slot values inherit that prov. Folding kicks in only when admission re-encounters a Var ref or Named wrapper inside the values (which happens for the recursive Threal self-references but not for the top-level slot values themselves).
- This is significantly better than the pre-Phase-2 baseline (no recursion blow-up) but does not fully reach the user's stated goal of `{:result Threal :cache ThrealCache}`. Phase 2b will close that gap by extending the bare-symbol-capture mechanism to nested positions in source forms.

### Pending
Phase 2b — nested-source-form bare-symbol capture. Phase 3 (porcelain) and Phase 4 (regression test) follow.
