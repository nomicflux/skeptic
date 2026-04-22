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

Phase 1 — Reference-identity prov capture (var-prov + admission Named
branch + canonicalize outer-strip-only). Awaiting user approval.
