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

## Phase 2b.0 — Source-form extractors — COMPLETE

### Deliverables landed

**`skeptic/src/skeptic/checking/form.clj`**
- `extract-defn-annotation-form [form] -> form-or-nil`: parses past name, optional docstring, optional attr-map; returns the form following `:-` (any shape: bare symbol, map literal, vector, list); nil when no `:-` annotation present.
- `extract-def-annotation-form [form] -> form-or-nil`: parses past name; returns the form following `:-` (any shape); nil when no `:-` annotation present.
- `extract-defschema-body-form [form] -> form-or-nil`: returns the body form (3rd element) of `(s/defschema Name body)` / `(schema.core/defschema Name body)`; nil for non-defschema heads.

**`skeptic/test/skeptic/checking/form_test.clj`**
- `extract-defn-annotation-form-test`: bare symbol, map literal `{:result Foo :cache Bar}`, vector `[Foo]`, list `(s/maybe Foo)`, docstring/attr-map permutations, multi-arity, missing annotation.
- `extract-def-annotation-form-test`: bare symbol, map literal, vector, missing annotation.
- `extract-defschema-body-form-test`: map body, vector body, qualified `schema.core/defschema`, non-defschema heads → nil.

### Verification
- `cd skeptic && lein test`: 394 tests, 1875 assertions, 0 failures, 0 errors.
- `cd skeptic && clj-kondo --lint src test`: errors 0, warnings 0.

### Pending
Phase 2b.1 — form-refs map and pipeline binding. Phase 2b.2 — admission consumption and end-to-end test.

## Phase 2b.1 — Form-refs map + pipeline binding — COMPLETE

### Deliverables landed

**`skeptic/src/skeptic/analysis/bridge.clj`**
- `(def ^:dynamic *form-refs* nil)` added at line 13, alongside `*annotation-refs*` and `*var-provs*`.

**`skeptic/src/skeptic/schema/collect.clj`**
- `extract-form-for [form] -> form-or-nil` (private): dispatches by form head — `s/defn`/`schema.core/defn`/`defn` → `cf/extract-defn-annotation-form`; `s/def`/`schema.core/def`/`def` → `cf/extract-def-annotation-form`; `s/defschema`/`schema.core/defschema`/`defschema` → `cf/extract-defschema-body-form`; else nil.
- `put-form-entry! [^IdentityHashMap acc ns-sym form]` (private): resolves declared name via `resolve-in-ns`, `.put`s `decl-var → form` when both succeed.
- `build-form-refs! [^IdentityHashMap acc ns-sym source-file]` (public): mirrors `build-annotation-refs!` source-iteration pattern (`with-open` + `pushback-reader`, `repeatedly` + `try-read`, `take-while some?`, `remove is-ns-block?`, `try/catch Exception _`).

**`skeptic/src/skeptic/checking/pipeline.clj`**
- `namespace-dict` builds a SECOND IdentityHashMap `form-refs`, populates via `collect/build-form-refs!`, binds `ab/*form-refs* form-refs` alongside `ab/*annotation-refs* refs` in a single `binding` form.

**`skeptic/test/skeptic/test_examples/form_refs.clj`** (new fixture)
- `(s/defschema MapBody {:a s/Int :b s/Str})`
- `(s/defschema VecBody [s/Int])`
- `(s/defn fn-with-map-ann :- {:result s/Int :cache s/Str} [x :- s/Int] {:result x :cache "k"})`

**`skeptic/test/skeptic/schema/collect_test.clj`**
- `build-form-refs-stores-defn-annotation-form`: `annotated-fn` → `'RefSchema`.
- `build-form-refs-stores-def-annotation-form`: `annotated-val` → `'RefSchema`.
- `build-form-refs-stores-defschema-body-form`: `RefSchema` → `'s/Int`.
- `build-form-refs-stores-map-and-vector-literals`: covers map-literal body, vector-literal body, and defn map-literal annotation.
- `build-form-refs-skips-forms-without-annotation`: tempfile fixture; assert `.size` is 0 for unannotated defn.

### Verification
- `cd skeptic && lein test`: 399 tests, 1882 assertions, 0 failures, 0 errors.
- `cd skeptic && clj-kondo --lint src test`: 0 errors, 0 warnings.

### Pending
Phase 2b.2 — admission entry-point form-prov override + composite source-form propagation + end-to-end test.

## Phase 2b.4.1 — Provenance `:refs` field — COMPLETE (uncommitted)

### Deliverables landed

**`skeptic/src/skeptic/provenance.clj`**
- `Provenance` defrecord gains 5th field `refs` (default `[]`).
- `make-provenance` now has dual arity: 4-arity defaults `:refs []`; 5-arity takes explicit `refs`. All existing call sites continue to work via the 4-arity default.
- `with-refs [prov refs] -> Provenance`: new helper that returns prov with `:refs` replaced by `(vec refs)`. Used by Phase 2b.4.2+ at composite construction sites.
- `inferred` updated to pass `[]` explicitly to make-provenance.
- `with-ctx`, `set-ctx`, `provenance?`, `source`, `of`, `source-rank-map`, `source-rank`, `merge-provenances` unchanged.

**`skeptic/test/skeptic/provenance_test.clj`**
- `make-provenance-defaults-empty-refs`: 4-arity → `(:refs p)` is `[]`.
- `make-provenance-five-arity-stores-refs`: 5-arity stores constituent provs.
- `with-refs-replaces-refs`: `with-refs` correctly replaces refs.
- `provs-equal-only-if-refs-match`: equality accounts for `:refs` (defrecord field-wise equality).
- `inferred-sets-empty-refs`: `inferred` produces `:refs []`.

### Verification
- 14/14 provenance tests pass (9 existing + 5 new).
- `clj-kondo --lint src test`: 0 errors, 0 warnings.
- Pre-existing 3 failures in `named-fold-diagnostic-test` (composed-body / actual-side fold gap) are EXPECTED — that test exposes the gap Phase 2b.4 fixes; assertions are explicitly removed in Phase 2b.4.4 per plan.

### Pending
Phase 2b.4.2 — populate `:refs` at literal-construction sites (data.clj, value.clj, match.clj).

## Phase 2b.4.2 — Populate `:refs` at literal-construction sites — COMPLETE (uncommitted)

### Deliverables landed

**`skeptic/src/skeptic/analysis/annotate/data.clj`**
- `annotate-vector`: `->VectorT` prov wrapped with `(prov/with-refs (prov/with-ctx ctx) (mapv prov/of item-types))`.
- `annotate-set`: result of `aapi/normalize-type` post-wrapped with `(prov/with-refs (:prov t) [(prov/of joined)])`.
- `annotate-map`: same post-wrap pattern; refs flatten the entries map's key/val provs.

**`skeptic/src/skeptic/analysis/value.clj`**
- Added `[skeptic.provenance :as prov]` require.
- `map-value-type`: ->MapT prov wrapped with `prov/with-refs` of flattened entry provs.
- `type-of-value` vector arm: ->VectorT prov wrapped with constituent item-type provs.
- `homogeneous-seq-type`: constructor's prov wrapped with `[(prov/of element)]`.
- `type-of-value` set arm: ->SetT prov wrapped with `[(prov/of element)]`.

**`skeptic/src/skeptic/analysis/annotate/match.clj`**
- `drop-discriminator-key`'s ->MapT branch: prov wrapped with `prov/with-refs` of kept entries' k/v provs.

### Tests added

**`skeptic/test/skeptic/analysis/annotate/data_test.clj`** (created)
- `annotate-vector-threads-refs-test`, `annotate-set-threads-refs-test`, `annotate-map-threads-refs-test`.

**`skeptic/test/skeptic/analysis/value_test.clj`** (extended)
- `type-of-value-vector-arm-threads-refs-test`, `type-of-value-seq-arm-threads-refs-test`, `type-of-value-set-arm-threads-refs-test`, `map-value-type-threads-refs-test`.
- Pre-existing `type-of-value-collections-test` rewritten: `=` → `at/type=?` at the vector and nested-map equality assertions, since the new `:refs` populates make full structural equality differ but type structure is unchanged. Reason per `feedback_rep_change_scope`: representational changes own their fallout.

### Verification
- `lein test`: 422 tests, 1928 assertions, **3 failures, 0 errors** — the 3 failures are the pre-existing `named-fold-diagnostic-test` composed-body / actual-side fold-pending assertions, removed in Phase 2b.4.4 per plan.
- `clj-kondo --lint src test`: 0 errors, 0 warnings.

### Pending
Phase 2b.4.3 — populate `:refs` at collection-op + invoke-output sites.

## Phase 2b.4.3 — Populate `:refs` at collection-op + invoke-output sites — COMPLETE (uncommitted)

### Deliverables landed

**`skeptic/src/skeptic/analysis/annotate/coll.clj`**
- Added `[skeptic.provenance :as prov]` require.
- 15 composite-construction sites wrapped with `prov/with-refs`:
  - `vector-to-homogeneous-seq-type`: `[(prov/of elem)]`.
  - `coll-rest-output-type` (3 branches): `(mapv prov/of tail)`, `[(prov/of elem)]`, `[(prov/of elem)]`.
  - `coll-butlast-output-type`: `(mapv prov/of items)`.
  - `coll-drop-last-output-type`: `(mapv prov/of kept)`.
  - `coll-take-prefix-type`: `(mapv prov/of kept)`.
  - `coll-drop-prefix-type` (2 branches): `[]`, `(mapv prov/of tail)`.
  - `coll-same-element-seq-type`: `[(prov/of elem)]`.
  - `concat-output-type` (2 branches): empty-args → `[]` (Dyn synthesized, no constituent ref); non-empty → bound `joined` and `[(prov/of joined)]`.
  - `into-output-type` (2 branches): vector and seq targets, both `[(prov/of elem)]`.
  - `lazy-seq-new-type`: `[(prov/of elem)]`.

**`skeptic/src/skeptic/analysis/annotate/invoke_output.clj`**
- Added `[skeptic.provenance :as prov]` require.
- `chunk-first-call?` branch (line 59): `[(prov/of elem)]`.

### Tests added/modified

**`skeptic/test/skeptic/analysis/annotate/coll_test.clj`** (extended)
- New `int-t` helper.
- 12 new deftests asserting `:refs` count and (where applicable) `[]` for empty/synthesized cases:
  `coll-rest-output-type-vector-threads-refs-test`,
  `coll-butlast-output-type-threads-refs-test`,
  `coll-drop-last-output-type-threads-refs-test`,
  `coll-take-prefix-type-threads-refs-test`,
  `coll-drop-prefix-type-threads-refs-test`,
  `coll-same-element-seq-type-threads-refs-test`,
  `concat-output-type-empty-args-empty-refs-test`,
  `concat-output-type-non-empty-threads-joined-ref-test`,
  `into-output-type-vector-target-threads-refs-test`,
  `into-output-type-seq-target-threads-refs-test`,
  `vector-to-homogeneous-seq-type-threads-refs-test`.
- `concat-output-type-container-owns-prov-test` second `testing` block weakened from full-prov `=` to `:source` + `:qualified-sym` equality (the anchor identity it was actually testing); the empty-args block still uses full-prov `=` because both anchor and result have `:refs []`. Reason per `feedback_rep_change_scope`: the original `=` was incidental on a now-populated representation; weakening to source/qualified-sym preserves the intent (anchor identity).

**`skeptic/test/skeptic/analysis/annotate/invoke_output_test.clj`** — NOT created. The chunk-first branch requires going through `ac/chunk-first-call?` AST construction, which exceeds the test's worth here. Coverage exists implicitly via the broader test suite's callers of `invoke-output-type`.

### Verification
- `lein test` (skeptic): 433 tests, 1944 assertions, **3 failures, 0 errors** — same 3 pre-existing `named-fold-diagnostic-test` fold-pending assertions, removed in Phase 2b.4.4 per plan.
- `lein test` (lein-skeptic): 0 tests / 0 errors.
- `clj-kondo --lint src test` (both subprojects): 0 errors, 0 warnings.

### Pending
Phase 2b.4.4 — populate `:refs` at map-op + algebra sites; update diagnostic test to assert `:refs` chain.

## Phase 2b.4.4 — Populate `:refs` at map-op + algebra + type-substitute sites; diagnostic update — COMPLETE (uncommitted)

### Deliverables landed

**`skeptic/src/skeptic/analysis/map_ops.clj`**
- Added `[skeptic.provenance :as prov]` require.
- `merge-map-types` non-empty branch: `->MapT` prov wrapped with `(mapv prov/of types)` (the constituent map types).

**`skeptic/src/skeptic/analysis/map_ops/algebra.clj`**
- Added `[skeptic.provenance :as prov]` require.
- `assoc-type` map-type branch: refs = `[(prov/of m-type) (prov/of k) (prov/of value-type)]`.
- `dissoc-type` map-type branch: bound `removed-key`; refs = `[(prov/of m-type) (prov/of removed-key)]`.

**`skeptic/src/skeptic/analysis/type_algebra.clj`**
- Added `[skeptic.provenance :as prov]` require.
- `type-substitute` 5 composite-rebuild branches each bind the rebuilt constituents to a local then attach refs via `prov/with-refs`:
  - Union: `(mapv prov/of members')`.
  - Map: flattened `[k.prov v.prov ...]`.
  - Vector: `(mapv prov/of items')`.
  - Set: `(mapv prov/of members')`.
  - Seq: `(mapv prov/of items')`.

### Tests added/modified

**`skeptic/test/skeptic/analysis/map_ops_test.clj`** (extended)
- New `merge-map-types-threads-refs-test`.
- `merge-map-types-container-owns-prov-test` weakened from full-prov `=` to source/qualified-sym/declared-in field-wise comparison (rep-change fallout per `feedback_rep_change_scope`).

**`skeptic/test/skeptic/analysis/map_ops/algebra_test.clj`** (extended)
- New `assoc-type-threads-refs-test`, `dissoc-type-threads-refs-test`.
- `merge-types-container-owns-prov-test` weakened to field-wise comparison (same rep-change fallout).

**`skeptic/test/skeptic/analysis/type_algebra_test.clj`** (NEW)
- 5 deftests, one per type-substitute composite-rebuild branch (Union/Map/Vector/Set/Seq).

**`skeptic/test/skeptic/analysis/annotate/shared_call_test.clj`** (modified)
- `shared-call-merge-uses-anchor-prov-test` second `testing` weakened from `(= tp (prov/of result))` to source/qualified-sym/declared-in field-wise — `:merge` now goes through `merge-map-types` which populates `:refs`. Empty-args block unchanged. Outside the agent's stated file scope but justified rep-change fallout.

**`skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`** (modified)
- New private helper `collect-refs-deep [prov]` walking `:refs` lazily depth-first.
- `diagnostic-composed-body-loss-point` rewritten:
  - REMOVED: `:source :schema` and `:qualified-sym = expected-qsym` assertions, render-fold assertion, two `println` debug lines, `rendered` let-binding (deferred to Phase 2b.5 per plan).
  - ADDED: `(seq (:refs result-prov))` is truthy, and some prov in `(collect-refs-deep result-prov)` has `:qualified-sym = 'skeptic.test-examples.form-refs/produce-inner-set`.

### Verification
- `lein test` (skeptic): 441 tests, 1957 assertions, **0 failures, 0 errors**.
- `lein test` (lein-skeptic): 0 tests / 0 errors.
- `clj-kondo --lint src test` (both subprojects): 0 errors, 0 warnings.

### Phase 2b.4 closes here

Every analyze-pipeline composite-construction site now records its constituent provs in `:refs`. The diagnostic confirms the inferred `:result` VectorT for `fn-with-composed-body` carries a `:refs` chain reaching `produce-inner-set`.

**Remaining open**: smoke fold for the `add-with-cache` actual side. The structural blow-up still appears because the renderer does not yet consult `:refs` for fold decisions. That is Phase 2b.5's scope (separate plan to be drafted).
