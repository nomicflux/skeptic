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
