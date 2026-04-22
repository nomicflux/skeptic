# Named-Type Folding — Phase 2b: Nested-Annotation Bare-Symbol Capture

## Context

Phase 2 completed the renderer-side folding mechanism but did not fully reach
the user's stated end-goal:

```
add-with-cache declared return:
  {:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}
```

After Phase 2 the rendering is:

```
{:result
 [#{[#{clj-threals.threals/Threal} #{Threal} #{Threal}]} ...]
 :cache
 {[[#{[...]} ...]] [#{[...] ...]] ...}}
```

The recursive `Threal` self-references inside the schema bodies do fold (good
— recursion no longer blows up). But the OUTER slot values (`:result`'s value,
`:cache`'s value) render structurally one level deep, because they were never
stamped with a foldable prov.

## Evidence (option #2 grounding)

`/Users/demouser/Code/clj-threals/src/clj_threals/operations.clj:197`:

```clojure
(s/defschema ThrealCache
  {[threals/Threal threals/Threal] threals/Threal})

(s/defn add-with-cache :- {:result threals/Threal :cache ThrealCache}
  ([gt_fn x y] ...))
```

`/Users/demouser/Code/clj-threals/src/clj_threals/threals.clj:6`:

```clojure
(s/defschema Threal
  [#{Threal}
   #{Threal}
   #{Threal}])
```

The annotation literal `{:result threals/Threal :cache ThrealCache}` is a map
whose VALUES are bare symbols. At read time these are Symbols. At evaluation
time, `s/defn`'s macroexpansion deref's them to schema VALUES (the deref'd
NamedSchema or vector value). The bare-symbol identity is lost by the time
admission sees the schema value.

This is the same problem Phase 0 solved for the top-level annotation
(`:- Foo` → captured as bare symbol from source forms). Phase 0 only handles
the top-level (one symbol per `:- ...`). Phase 2b extends the mechanism to
NESTED positions inside annotation forms.

## Behaviour contract

For a function or schema declaration whose annotation contains bare-symbol
references at nested positions, each such reference's admitted Type carries
the resolved Var's prov.

Examples (each line is one declaration → expected effect on subtree provs):

```
(s/defn f :- {:a Foo :b Bar} [...])
  → :a slot value type carries prov(#'Foo, :schema)
  → :b slot value type carries prov(#'Bar, :schema)

(s/defn g :- [Foo] [...])
  → element type at index 0 carries prov(#'Foo, :schema)

(s/defn h :- (s/maybe Foo) [...])
  → inner type carries prov(#'Foo, :schema)

(s/defschema X {:result Foo :cache Bar})
  → at admission of #'X's body, :result value type carries prov(#'Foo, :schema)
  → :cache value type carries prov(#'Bar, :schema)

(s/defschema Threal [#{Threal} #{Threal} #{Threal}])
  → vector items carry prov(#'Threal, :schema)
  → set members carry prov(#'Threal, :schema)
```

A bare symbol that resolves to a Var WITHOUT `:schema` meta (e.g. random
function names, non-schema vars) is ignored — fallback to parent prov.

A non-symbol form at a position (literal class, regex, function call like
`(s/named …)`) doesn't fire form-capture for that position — fallback to
parent prov, which in the `(s/named …)` case is the existing admission's
named-import branch.

## Mechanism

The capture site is the **source form** of the declaration (annotation site
for `s/defn`/`s/def`, body site for `s/defschema`). At admission, we walk the
source form in parallel with the schema value, propagating a "current source
sub-form" through `import-schema-type*`'s ctx. When the sub-form is a bare
symbol resolvable to a `:schema`-meta Var, we override the child's prov with
that Var's prov.

### Pieces

1. **Form capture (Phase 0 extension)** — `form.clj` gains
   `extract-defn-annotation-form [form]` and
   `extract-def-annotation-form [form]` returning the FULL annotation form
   (not just bare symbol) — i.e. the value after `:-`.
   `extract-defschema-body-form [form]` returns the body (the schema literal
   passed to `s/defschema`).

2. **Annotation form refs (Phase 0 extension)** —
   `schema/collect.clj/build-annotation-refs!` continues to put a Var (the
   resolved annotation Var if annotation is a bare symbol — Phase 0
   behavior). NEW: `build-form-refs!` populates a separate IdentityHashMap
   `{declared-Var → annotation-form-or-body-form}`. Forms can be ANY shape
   (symbol, map, vector, set, list).

3. **Dynamic ctx for source form** — `bridge.clj` gains
   `(def ^:dynamic *form-refs* nil)`. Pipeline binds it for the admission
   scope, mirroring `*annotation-refs*` and `*var-provs*`.

4. **Admission entry-point form lookup** — `typed_decls.clj/convert-desc`
   resolves the declared Var, looks up `*form-refs*[declared-var]` to get the
   top-level annotation/body form. Passes form to `desc->type` which
   threads it into the initial ctx as `:source-form`.

5. **Form-driven prov override in `import-schema-type*`** — at the entry of
   the cond, BEFORE the existing branches:
   ```
   (let [form (:source-form ctx)
         var-from-form (when (symbol? form) (resolve form))
         form-prov (when (and var-from-form
                              (:schema (meta var-from-form))
                              (not (:macro (meta var-from-form))))
                     (prov/make-provenance :schema
                                           (sb/qualified-var-symbol var-from-form)
                                           (some-> var-from-form .ns ns-name)
                                           (meta var-from-form)))]
     (if form-prov
       (run (assoc ctx :prov form-prov :source-form nil))
       <existing cond>))
   ```
   The `:source-form nil` reset prevents re-firing on the recursion (the
   override has been applied; child positions use the new prov).

6. **Composite imports propagate child source-forms** —
   - `map-import-type`: for each (k, v) entry, look up `(get form k)` (when
     form is a map and k is a literal key). Pass to child admission as
     `:source-form` for v. Same for k via `(get-form-key-form form k)`.
   - `collection-import-type` (vector/set/seq): for vector form, child i's
     source-form is `(nth form i nil)`. For set form, child source-form is
     the matching element by value-identity (fragile; for now, set members
     share the form by iterating both in parallel — best effort).
   - `function-import-type`, `branch-import-type`, `conditional-import-type`,
     `refinement-import-type`, `adapter-leaf-import-type`,
     `unary-child-result`, `named-import-type` — no form propagation needed
     in Phase 2b; the existing prov-stamping mechanisms take precedence at
     these special shapes. (These all consume form-prov override at their
     entry via the entry-point lookup if applicable.)
   - `var-import-type`: when `*var-provs*` hits, the existing override wins;
     when source-form is also present at a Var ref, prefer the form-prov
     (they should be equivalent if both fire — both name the same Var — so
     this is a no-op in practice).
   - `one-step-schema-node`: form propagation here is trivial (it doesn't
     descend into composites; just unwraps Var$Unbound).

7. **`s/defschema` body capture** — `s/defschema X body` desugars to a
   `def`/`s/def` whose value is `(s/named body 'X)` (or similar). The
   annotation slot of the resulting `s/def` is NOT `body` — it's the
   `s/named` wrapper around body. Phase 2b's `extract-def-annotation-form`
   should still return `body` (the inner literal) when the form is
   `s/defschema`.

   Detection: `(s/defschema X body)` is shaped `(s/defschema NAME BODY)` —
   reader form is a list with `s/defschema` head. `extract-defschema-body-form`
   recognizes this and returns BODY.

   `build-form-refs!` invokes whichever extractor matches the form head
   (`s/def`, `s/defn`, `s/defschema`).

## Files

### Source
- `skeptic/src/skeptic/checking/form.clj` — add three extractors.
- `skeptic/src/skeptic/schema/collect.clj` — add `build-form-refs!`; pipeline
  binds the resulting IdentityHashMap.
- `skeptic/src/skeptic/checking/pipeline.clj` — bind `*form-refs*` alongside
  `*annotation-refs*` in `namespace-dict`.
- `skeptic/src/skeptic/analysis/bridge.clj` —
  - `(def ^:dynamic *form-refs* nil)`.
  - Form-prov-override entry in `import-schema-type*`.
  - Add `:source-form` to ctx.
  - Composite imports (map, collection) propagate child source-forms.
  - New private helpers: `form-prov [form]` returning prov-or-nil for a bare
    symbol resolving to a schema Var; `child-form-for-map [form k]` and
    `child-form-for-collection [form i]`.
- `skeptic/src/skeptic/typed_decls.clj` — `convert-desc` looks up
  `*form-refs*` for the declared-Var; threads as `:source-form` to
  `desc->type`. `desc->type` passes it into the initial ctx of
  `ab/schema->type`.

### Tests
- `skeptic/test/skeptic/checking/form_test.clj` — extractor tests
  (annotation-form for s/defn / s/def shapes; body-form for s/defschema).
- `skeptic/test/skeptic/schema/collect_test.clj` — `build-form-refs!` test
  using a fixture ns.
- `skeptic/test/skeptic/analysis/bridge_test.clj` — admission tests with
  `*form-refs*` bound: map slot → bare symbol → folds; vector index → bare
  symbol → folds; non-symbol form (e.g. `(s/maybe Foo)`) → no form-prov,
  falls through.
- `skeptic/test/skeptic/typed_decls_test.clj` — end-to-end: convert-desc
  with form-refs populated produces Types whose nested provs reflect the
  bare-symbol resolution.
- `skeptic/test/skeptic/test_examples/nested_annotation.clj` — fixture with
  shapes mirroring `add-with-cache`.

## Code style (verbatim, applies to every phase)

- Functions must be <20 lines, <10 if possible.
- Write helper functions instead of nested logic.
- Write for the current specification.
- Do not code defensively. Trust internal types.
- Prefer pure functions.
- Modify existing functions in place; update ALL callers.
- Never create `foo_v2`, `new_foo`, "for compatibility" shims.
- Delete obsolete code made redundant.
- No dead code. No TODOs.
- Every new function gets a test.
- 100% test pass rate.
- `prov/of` / `prov/source` throw on missing prov — do NOT guard.
- `prov/unknown` is forbidden anywhere in the codebase.

## Project commands

```
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src
```

## Phase overview

| Phase | Topic | Agents | Live use of new code |
|-------|-------|--------|----------------------|
| 2b.0 | Form extractors (annotation-form, body-form) | 1 | Phase 2b.0 unit tests assert extracted forms |
| 2b.1 | Form-refs map + pipeline binding | 1 | Phase 2b.1 unit tests assert map population |
| 2b.2 | Admission entry-point form-prov override + composite propagation | 1 | Phase 2b.2 admission tests assert nested-position prov; end-to-end smoke on add-with-cache |

Three phases, one agent each (file count fits the PSP rule per phase).

---

## Phase 2b.0 — Form extractors

**Goal**: pure parsers returning the FULL annotation form / defschema body,
preserving bare symbols at nested positions.

### Mechanism

`form.clj` gains:
- `extract-defn-annotation-form [form] -> form-or-nil` — for `(s/defn name [...
  args]? :- ANN ...)`, returns ANN (the whole annotation form, may be a map,
  vector, list, symbol, anything).
- `extract-def-annotation-form [form] -> form-or-nil` — for
  `(s/def name :- ANN body)`, returns ANN.
- `extract-defschema-body-form [form] -> form-or-nil` — for
  `(s/defschema name BODY)`, returns BODY.

These are siblings of the existing `extract-defn-annotation-symbol` /
`extract-def-annotation-symbol` (which return the bare symbol or nil). The
new functions return the WHOLE form (not just the symbol).

### Files
- Updated: `skeptic/src/skeptic/checking/form.clj`
- Updated: `skeptic/test/skeptic/checking/form_test.clj`

### Agents: 1
```
Agents: 1
  Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/checking/form.clj,
     skeptic/test/skeptic/checking/form_test.clj]
    — Pure parser additions; sibling to existing extractors.
```

### SUBAGENT PRE-FLIGHT — Phase 2b.0 Agent 1

- **Goal**: Add three new pure parsers that return the FULL form at the
  annotation/body position. Do not modify the existing
  `extract-*-annotation-symbol` helpers.
- **Broader context**: Phase 2b.0 of 3. Phase 2b.1 will use these to build
  the form-refs IdentityHashMap; Phase 2b.2 will consult it during admission.
- **Tech stack/build/test/lint**: as global.
- **Existing**: `form.clj` already has extractors that return the bare
  symbol when the annotation is a bare symbol. The new extractors return
  the WHOLE form regardless of shape.
- **Constraints**:
  - Pure functions; no var resolution.
  - `extract-defschema-body-form` only returns when the form head is
    `s/defschema` (or unqualified `defschema`); else nil.
  - For `s/defn`, the annotation may be present in either single-arity or
    multi-arity shapes. Match the same parsing as the existing
    `extract-defn-annotation-symbol` for skipping name/docstring/attr-map.
  - Non-`s/def`/`s/defn`/`s/defschema` forms: return nil.
- **Deliverables**:
  1. `extract-defn-annotation-form [form] -> form-or-nil`
  2. `extract-def-annotation-form [form] -> form-or-nil`
  3. `extract-defschema-body-form [form] -> form-or-nil`
  4. Tests covering each form shape (bare symbol, map literal, vector literal,
     `(s/maybe …)` list, missing).
- **Verification**: lein test + clj-kondo.
- **Every referenced name**: stdlib only (clojure.core / clojure.string).

### Completion gate (Phase 2b.0)
1. lein test → 100% pass.
2. clj-kondo → 0/0.
3. Update status doc.
4. Commit: `Phase 2b.0 (form extractors) complete`.
5. STOP for user approval before Phase 2b.1.

---

## Phase 2b.1 — Form-refs map + pipeline binding

**Goal**: populate `*form-refs*` with `{declared-Var → annotation-or-body-form}`
for every `s/def`/`s/defn`/`s/defschema` in a namespace's source file.

### Mechanism

`schema/collect.clj`:
- `build-form-refs! [^IdentityHashMap acc ns-sym source-file]`. Reads source
  forms via `file/pushback-reader`; for each top-level form, dispatches by
  head:
  - `s/defn` / `defn` → `extract-defn-annotation-form`
  - `s/def` / `def` → `extract-def-annotation-form`
  - `s/defschema` / `defschema` → `extract-defschema-body-form`
  - else → skip
  - On non-nil form result: resolve the declared NAME to a Var; `.put` the
    Var → form mapping.

`bridge.clj`: add `(def ^:dynamic *form-refs* nil)`.

`pipeline.clj/namespace-dict`: build a fresh IdentityHashMap, populate via
`build-form-refs!`, bind `ab/*form-refs*` for the admission scope (alongside
existing `*annotation-refs*`).

### Files
- Updated: `skeptic/src/skeptic/checking/form.clj` (no changes; just
  consumed)
- Updated: `skeptic/src/skeptic/schema/collect.clj`
- Updated: `skeptic/src/skeptic/analysis/bridge.clj` (add dynamic var)
- Updated: `skeptic/src/skeptic/checking/pipeline.clj`
- Updated: `skeptic/test/skeptic/schema/collect_test.clj`

### Agents: 2
```
Agents: 2
  Agent 1 (modular-builder): files
    [skeptic/src/skeptic/schema/collect.clj,
     skeptic/src/skeptic/analysis/bridge.clj,
     skeptic/src/skeptic/checking/pipeline.clj]
    — Cross-cutting binding wiring; touches collect, bridge dynamic, pipeline.
  Agent 2 (kiss-code-generator): files
    [skeptic/test/skeptic/schema/collect_test.clj]
    — Tests asserting form-refs map population shape.
```

### SUBAGENT PRE-FLIGHT — Phase 2b.1 Agent 1

- **Goal**: Add `build-form-refs!` in collect.clj, the dynamic
  `ab/*form-refs*` in bridge.clj, and the binding in pipeline's
  namespace-dict. NO admission-side consumption yet (Phase 2b.2 does that).
- **Broader context**: Phase 2b.1 of 3. Phase 2b.0 (extractors) is done;
  Phase 2b.2 will read this map during admission.
- **Constraints**:
  - Mirror the existing `build-annotation-refs!` pattern for source-file
    iteration, ns binding, and resilience (try/catch around reader).
  - Skip ns blocks via `file/is-ns-block?` as in `build-annotation-refs!`.
  - The form value stored is whatever the extractor returned (could be a
    bare symbol — we still store it; admission will treat symbol-form as a
    direct override).
  - Phase 2b.1 is FUNCTIONAL only via the test in Agent 2; no admission
    behavior changes yet.
- **Deliverables**:
  1. `collect.clj`: `build-form-refs! [^IdentityHashMap acc ns-sym source-file]`
     plus dispatch helper `extract-form-for [form]` returning
     `[declared-name source-form-or-nil]`.
  2. `bridge.clj`: `(def ^:dynamic *form-refs* nil)` near `*var-provs*`.
  3. `pipeline.clj/namespace-dict`: build IdentityHashMap, call
     `build-form-refs!`, bind `ab/*form-refs*` around the
     `typed-decls/typed-ns-results` invocation. Update all callers
     consistently (already done for `*annotation-refs*`).
- **Verification**: lein test (existing tests pass; no regressions) +
  clj-kondo.
- **Every referenced name**:
  - `file/pushback-reader`, `file/try-read`, `file/is-ns-block?`.
  - Phase 2b.0 extractors.
  - `the-ns`, `ns-resolve`.

### SUBAGENT PRE-FLIGHT — Phase 2b.1 Agent 2

- **Goal**: Tests asserting `build-form-refs!` populates the map correctly
  for s/def, s/defn, s/defschema fixture forms.
- **Constraints**:
  - Use a tiny test fixture ns (or reuse existing `annotation_refs.clj`).
  - Assert that the value for each entry is the EXACT form (e.g. for
    `(s/defn f :- {:a Foo} [...])`, the entry value is `{:a Foo}`).
- **Deliverables**: 4-5 deftests covering each declaration shape +
  no-annotation case.
- **Verification**: lein test + clj-kondo.

### Completion gate (Phase 2b.1)
1. lein test → 100% pass.
2. clj-kondo → 0/0.
3. Update status doc.
4. Commit: `Phase 2b.1 (form-refs map + pipeline binding) complete`.
5. STOP for user approval before Phase 2b.2.

---

## Phase 2b.2 — Admission form-prov override + composite propagation

**Goal**: at admission, consult `:source-form` in ctx; if it's a bare symbol
resolvable to a schema Var, override the prov. Composite imports propagate
child source-forms by structural correspondence with the value.

### Mechanism

`bridge.clj`:
- New private `form-prov [form]` returning `Provenance` when `form` is a
  bare symbol resolving (in `*ns*`) to a Var with `:schema` meta and not
  `:macro`; else nil.
- `import-schema-type*` entry: BEFORE the existing cond, lookup
  `(:source-form ctx)`; if `(form-prov ...)` returns truthy, recurse with
  the new prov and `:source-form nil`.
- `map-import-type`, `collection-import-type` propagate child
  `:source-form` based on structural correspondence with the value.

`typed_decls.clj/convert-desc`:
- Look up `*form-refs*[declared-var]`; pass to `desc->type` which adds
  `:source-form` to the initial ctx of `ab/schema->type`.

`bridge.clj/schema->type`: add an arity (or modify existing) that accepts
optional `source-form`. Initial ctx includes `:source-form source-form`.

### Form-to-value correspondence rules

- **Map**: form is a literal map. For each value-position in the admitted
  map, the child form is `(get form k)`. For each key-position, the child
  form is the matching key in the form (use `=` lookup; if absent, nil).
- **Vector**: form is a literal vector. Child i's form is
  `(nth form i nil)`.
- **Set**: form is a literal set. For each member, child form is the
  matching member in the form (best-effort `=` lookup; if no match, nil).
- **List/Seq form (e.g. `(s/maybe Foo)` / `(s/recursive #'Foo)`)**: cannot
  reliably correspond to admitted value structure — treat the WHOLE form
  as nil for child propagation. Only the entry-point form-prov override
  applies (and these forms aren't bare symbols, so it doesn't fire). The
  existing admission's named/var/etc. branches handle these.
- **Bare symbol form**: form-prov override at entry; child propagation gets
  nil thereafter.
- **Anything else (literal class, regex, number)**: nil child forms.

### Files
- Updated: `skeptic/src/skeptic/analysis/bridge.clj`
- Updated: `skeptic/src/skeptic/typed_decls.clj`
- Updated: `skeptic/test/skeptic/analysis/bridge_test.clj`
- Updated: `skeptic/test/skeptic/typed_decls_test.clj`
- New: `skeptic/test/skeptic/test_examples/nested_annotation.clj`

### Agents: 2
```
Agents: 2
  Agent 1 (modular-builder): files
    [skeptic/src/skeptic/analysis/bridge.clj,
     skeptic/src/skeptic/typed_decls.clj,
     skeptic/test/skeptic/analysis/bridge_test.clj]
    — Admission core; tightly coupled.
  Agent 2 (kiss-code-generator): files
    [skeptic/test/skeptic/typed_decls_test.clj,
     skeptic/test/skeptic/test_examples/nested_annotation.clj]
    — End-to-end test against fixture mirroring add-with-cache shape.
```

### SUBAGENT PRE-FLIGHT — Phase 2b.2 Agent 1

- **Goal**: Add the form-prov override at the entry of `import-schema-type*`,
  thread `:source-form` through ctx, and propagate child source-forms in
  composite imports per the correspondence rules.
- **Broader context**: Phase 2b.2 of 3 (final phase of 2b). Phase 2b.0
  delivered extractors; Phase 2b.1 populated `*form-refs*`; this phase
  consumes it.
- **Constraints**:
  - DO NOT remove or modify the existing `*var-provs*` mechanism. Form-prov
    override fires BEFORE the existing branches; if no form-prov, the
    existing behavior is unchanged.
  - DO NOT propagate `:source-form` through non-collection composite
    imports (function/branch/conditional/refinement/adapter-leaf/named).
    Set `:source-form nil` when entering those branches, so any nested form
    structure doesn't get mis-attributed.
  - Form-prov resolution uses `(binding [*ns* ...] (resolve form))` — the ns
    binding is set by pipeline. If `*ns*` isn't bound during a test run,
    resolution may fail; don't error — return nil.
  - Functions <20 lines.
- **Deliverables**:
  1. `bridge.clj`:
     - `form-prov [form] -> prov-or-nil` (private).
     - `child-form-for-map [form k]` and `child-form-for-collection [form i]`
       (private helpers).
     - `import-schema-type*` entry-point form-prov override (4-6 lines).
     - `map-import-type` and `collection-import-type` propagate child forms.
     - `(def ^:dynamic *form-refs*)` if not already added in Phase 2b.1
       (verify; should be there).
  2. `typed_decls.clj`:
     - `convert-desc` looks up `*form-refs*` for declared-Var; passes form
       to `desc->type`.
     - `desc->type` accepts form, threads into initial ctx via
       `ab/schema->type` (extend ab/schema->type to accept optional form
       arg, defaulting to nil).
  3. `bridge_test.clj`: 6-8 tests covering:
     - Map slot value position with bare symbol → folds.
     - Vector index position with bare symbol → folds.
     - Set member with bare symbol → folds (best-effort).
     - Non-symbol form (s/maybe list) → no form-prov; falls through.
     - Symbol resolving to non-:schema-meta var → no form-prov.
     - Form is nil / *form-refs* nil → existing behavior unchanged.
     - End-to-end: admit a fixture's fn declaration via convert-desc with
       *form-refs* bound; assert nested provs reflect bare-symbol resolution.
- **Verification**: lein test + clj-kondo.

### SUBAGENT PRE-FLIGHT — Phase 2b.2 Agent 2

- **Goal**: End-to-end test fixture and test asserting the user's stated
  end-goal: `add-with-cache`-shape fn renders folded as
  `{:result <inner-name> :cache <inner-name>}`.
- **Existing**: Phase 4 will write a regression test for the original Threal
  shape; Phase 2b.2 Agent 2's test is more targeted — it asserts the form-
  capture mechanism works end-to-end via convert-desc + render.
- **Constraints**:
  - Fixture mirrors the add-with-cache shape: a defschema with map of two
    keys, each value being a bare-symbol reference to a separate defschema.
  - Use the project-convention test approach (check-fn + test-dict; see
    memory rule "Regression test approach").
- **Deliverables**:
  1. `nested_annotation.clj` fixture with shapes:
     - `(s/defschema Inner [#{Inner}])` (recursive named composite)
     - `(s/defschema Wrap {:a Inner :b Inner})` (nested bare-symbol refs)
     - `(s/defn user-fn :- {:result Inner :cache Wrap} [...])`
  2. `typed_decls_test.clj`: end-to-end test bound `*form-refs*`,
     `*var-provs*`, `*annotation-refs*`; convert-desc for user-fn; render
     the declared output type via `abr/render-type-form*`; assert the
     rendered string contains `Inner` and `Wrap` at the right positions.
- **Verification**: lein test + clj-kondo. Manual smoke on
  `cd /Users/demouser/Code/clj-threals && lein skeptic -n
  clj-threals.operations` — the `add-with-cache` blame block should show
  `{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`.

### Completion gate (Phase 2b.2)
1. lein test → 100% pass in skeptic and lein-skeptic.
2. clj-kondo → 0/0 in both subprojects.
3. End-to-end smoke on clj-threals operations: declared-return rendering
   matches the user's stated goal.
4. Update status doc with end-to-end output capture.
5. Commit: `Phase 2b (nested-annotation form capture) complete`.
6. STOP — Phase 2b complete. Resume original Phase 3 (porcelain) next.

---

## End-to-end verification (post Phase 2b)

```
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src

cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations
```

Expected: `add-with-cache` blame block shows
`{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`
in place of the partial-fold output that Phase 2 produced.
