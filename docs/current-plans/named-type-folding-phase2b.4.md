# Named-Type Folding — Phase 2b.4

## The principle

A provenance is a record of the **user-written source references** that
gave a Type its identity. `:schema` provs already do this — `form-prov`
resolves the annotation symbol the user wrote (e.g., `s/Int`) and the prov
carries that Var's qualified-sym. The prov says "this type comes from
THIS symbol in source."

`:inferred` is also a provenance. By the same definition, it must record
the references it drew on — the user-written source elements (call symbols,
literal sub-forms, sub-expressions) the inference traversed to produce the
type. Currently inferred provs throw this away (`qualified-sym nil`,
nothing else).

Phase 2b.4 makes `:inferred` provs maintain their references the same way
`:schema` provs do.

## What is broken (proven by diagnostic)

`skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`'s
`diagnostic-composed-body-loss-point` reproduces the gap. For
`fn-with-composed-body` whose body is `[(produce-inner-set)]`:

```
inferred VectorT prov ->
  #Provenance{:source :inferred, :qualified-sym nil,
              :declared-in skeptic.test-examples.form-refs, :var-meta nil}
```

The inferred VectorT carries no record of WHAT it was inferred from. The
call result `(produce-inner-set)` (which DOES carry a foldable schema-source
prov via Phase 2b.2's mechanism) is invisible to the outer VectorT's prov.

Every analyze-pipeline composite-construction site stamps a fresh prov via
`(prov/with-ctx ctx)` and drops the constituents' references. The sites:
- `analyze/annotate/data.clj:22-46` (vector/set/map literal annotation)
- `analyze/value.clj:44-64` (raw-value typing)
- `analyze/annotate/match.clj:121` (destructure MapT)
- `analyze/annotate/coll.clj:36–235` (collection ops)
- `analyze/annotate/invoke_output.clj:58` (invoke output coercion)
- `analyze/map_ops.clj:284`, `analyze/map_ops/algebra.clj:31,47` (assoc/dissoc/merge)
- `analyze/type_algebra.clj:109+` (type-substitute rebuilds)

Every one of these is a place the user wrote source code that the inference
walked over. The constituent provs at each site ARE the references that
location drew on.

## Behavior contract (HARD spec)

After Phase 2b.4:

1. `Provenance` has an additional `:refs` field — a vector of `Provenance`
   values that this prov drew its identity from. Default `[]`.

2. **`:schema` provs are unchanged**: their reference is recorded in the
   existing `:qualified-sym` slot (from `form-prov`'s Var resolution).
   `:refs` stays `[]` because the reference is direct/single.

3. **`:inferred` provs at composite construction sites carry `:refs`** =
   vector of `(prov/of constituent-Type)` for every constituent the
   composite was built from:
   - Vector literal of items → `:refs = (mapv prov/of item-types)`.
   - Set literal of joined element → `:refs = [(prov/of element-type)]`.
   - Map literal of entries → `:refs` is the flattened vector
     `[k1.prov v1.prov k2.prov v2.prov …]`.
   - Seq construction → analogous to vector.
   - Map ops (assoc/dissoc/merge) → `:refs` includes the base map's prov
     plus the key/value provs being added/removed.
   - Type-substitute composite rebuilds → `:refs` is the new constituents'
     provs.

4. `:inferred` provs for raw scalar values (e.g., `(class->type prov 1)`)
   keep `:refs []` — there are no constituent references, the value IS its
   own source element.

5. `:source`, `:qualified-sym`, `:declared-in`, `:var-meta` are unchanged
   in semantics. `prov/of`, `prov/source`, `prov/merge-provenances` are
   unchanged.

6. Two `Provenance` records are `=` iff all five fields are `=`. Falls out
   of defrecord; verify with a test.

7. Phase 2b.4 does NOT change rendering or fold logic. The diagnostic test's
   fold assertions on the composed-body case are removed in this phase
   (deferred to Phase 2b.5, which gets its own plan); a NEW assertion
   asserts the `:refs` chain reaches the expected references.

The principle: every prov records the user-written source references it was
built from. `:schema` already did this with one slot (`:qualified-sym`).
`:inferred` now does the same with the parallel slot (`:refs`).

## Mechanism

### Schema change in `provenance.clj`

```clojure
(defrecord Provenance [source qualified-sym declared-in var-meta refs])

(defn make-provenance
  ([source qualified-sym declared-in var-meta]
   (make-provenance source qualified-sym declared-in var-meta []))
  ([source qualified-sym declared-in var-meta refs]
   (->Provenance source qualified-sym declared-in var-meta (vec refs))))

(defn with-refs
  "Return prov with :refs replaced by the given constituent provs."
  [prov refs]
  (assoc prov :refs (vec refs)))
```

`inferred` sets `:refs []` by default. The 4-arity `make-provenance` keeps
every existing call site valid — they all default to `:refs []`, no behavior
change. New construction-site code calls `with-refs` to attach references.

### Construction-site population

For each construction site in the analyze pipeline, derive the constituent
provs locally and call `with-refs`:

```clojure
;; before
(at/->VectorT (prov/with-ctx ctx) item-types ...)
;; after
(at/->VectorT (prov/with-refs (prov/with-ctx ctx)
                              (mapv prov/of item-types))
              item-types ...)
```

For map/seq/set/intersection/union: same shape. For map ops: `:refs`
includes the base map's prov plus the operation's key/value provs.

### What does NOT change in this phase

- bridge.clj admission code: leaves `:refs []` (form-prov already supplies
  the single reference via `:qualified-sym`; no constituents needed).
- Renderer: unchanged. Folds by `:source` + `:qualified-sym` exactly as
  today.
- Cast/check/report layers: unchanged.

## Code style (verbatim, applies to every sub-phase)

- Functions <20 lines, <10 if possible.
- Helper functions instead of nested logic.
- Pure functions where possible.
- Modify in place; update ALL callers.
- No `_v2` / "for compatibility" shims.
- Delete obsolete code made redundant.
- No dead code, no TODOs.
- Every new function gets a test.
- 100% test pass rate.
- `prov/of` / `prov/source` throw on missing — do NOT guard.
- `prov/unknown` is forbidden anywhere.

## Project commands

```
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src
```

## Phase overview

Phase 2b.4 is split into four sub-phases. The schema change in 2b.4.1 is
backward-compatible; sub-phases 2b.4.2/3/4 progressively populate `:refs`
at more construction sites. Each gate is a working intermediate state with
100% pass rate.

| Sub-phase | Topic | Files | Agents |
|-----------|-------|-------|--------|
| 2b.4.1 | Provenance schema: add `:refs`, default `[]`; add `with-refs` helper. All existing tests pass unchanged. | provenance.clj, provenance_test.clj | 1 |
| 2b.4.2 | Populate `:refs` at literal-construction sites (vector/set/map literals + raw-value typing + destructure MapT). | analyze/annotate/data.clj, analyze/value.clj, analyze/annotate/match.clj | 1 |
| 2b.4.3 | Populate `:refs` at collection-op + invoke-output sites. | analyze/annotate/coll.clj, analyze/annotate/invoke_output.clj | 1 |
| 2b.4.4 | Populate `:refs` at map-op + type-substitute sites; update diagnostic test to assert `:refs` chain. | analyze/map_ops.clj, analyze/map_ops/algebra.clj, analyze/type_algebra.clj, test/.../named_fold_diagnostic_test.clj | 2 |

**Phase 2b.4 explicitly does NOT deliver smoke fold.** Smoke on clj-threals
will still show the structural blow-up on the actual side. Fold delivery —
how render translates `:refs`-tracked references into a fold decision — is
Phase 2b.5's scope and gets its own plan.

---

## Phase 2b.4.1 — Provenance schema: add `:refs` field

### Goal

Extend `Provenance` with a `:refs` slot holding a vector of constituent
provs (default `[]`). All existing callers continue to work via the 4-arity
`make-provenance` default. No behavior change.

### Mechanism

`provenance.clj`:
- `(defrecord Provenance [source qualified-sym declared-in var-meta refs])`
- `make-provenance` 4-arity (compat, defaults `:refs []`) + 5-arity (new).
- `(defn with-refs [prov refs] (assoc prov :refs (vec refs)))`.
- `inferred` sets `:refs []`.
- `merge-provenances` unchanged. Equality semantics fall out from defrecord.

### Files

- Updated: `skeptic/src/skeptic/provenance.clj`
- Updated/created: `skeptic/test/skeptic/provenance_test.clj`

### Agents: 1

```
Agents: 1
  Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/provenance.clj,
     skeptic/test/skeptic/provenance_test.clj]
    — Schema change + helper + tests asserting default :refs=[],
      with-refs sets it, equality is field-wise, inferred sets [].
```

### Subagent pre-flight

- **Goal**: Add `:refs` field to `Provenance`, default `[]`, add `with-refs`
  helper. Preserve every existing call site and behavior.
- **Broader context**: First sub-phase of Phase 2b.4. Subsequent sub-phases
  populate `:refs` at construction sites in the analyze pipeline.
- **Tech stack**: Clojure, lein, clj-kondo.
- **Build/test/lint**:
  - `cd /Users/demouser/Code/skeptic/skeptic && lein test`
  - `cd /Users/demouser/Code/skeptic/lein-skeptic && lein test`
  - `cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test`
  - `cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src`
- **Existing**: `provenance.clj` (59 lines): `Provenance` defrecord with 4
  fields, `make-provenance` (4-arity), `inferred`, `with-ctx`, `set-ctx`,
  `provenance?`, `source`, `of`, `merge-provenances`. There may already be
  a `provenance_test.clj`; if absent, create.
- **Constraints**:
  - Add `:refs` as the 5th field of the defrecord.
  - 4-arity `make-provenance` MUST remain (defaults `:refs []`); add a
    5-arity overload that takes `refs`.
  - `inferred` constructor sets `:refs []`.
  - Add `with-refs [prov refs]` returning prov with `:refs` updated.
  - Do NOT change `with-ctx`, `set-ctx`, `source`, `of`,
    `merge-provenances`.
  - Do NOT add `prov/unknown`.
- **Deliverables**:
  1. `provenance.clj`: schema change + 5-arity `make-provenance` +
     `with-refs`. `inferred` updated to set `[]`.
  2. `provenance_test.clj` (create if absent): tests for
     - `(make-provenance s qs di vm)` produces prov with `:refs []`.
     - `(make-provenance s qs di vm [c1 c2])` → `:refs [c1 c2]`.
     - `(with-refs p [c1 c2])` → `:refs [c1 c2]`.
     - Two provs are `=` iff all five fields match (test by constructing
       two provs differing only in `:refs` and asserting `not=`).
     - `(:refs (inferred {:name 'x :ns 'y}))` is `[]`.
- **Verification**: `lein test` 100% pass; `clj-kondo --lint src test`
  clean. Pre-existing tests must not break — the 4-arity default keeps all
  sites valid.
- **Every referenced name**:
  - `Provenance` defrecord (5 fields).
  - `make-provenance [source qualified-sym declared-in var-meta]` and
    `[source qualified-sym declared-in var-meta refs]`.
  - `with-refs [prov refs] -> Provenance` (NEW).
  - `inferred [{:keys [name ns]}] -> Provenance` (modify body to pass `[]`).

### Completion gate

1. `lein test` in both subprojects — 100% pass.
2. `clj-kondo --lint` clean.
3. Update `docs/current-plans/named-type-folding_IMPLEMENTATION_STATUS.md`
   with sub-phase outcome.
4. Commit: `Phase 2b.4.1 (provenance :refs field) complete`.
5. STOP for user approval.

---

## Phase 2b.4.2 — Populate `:refs` at literal-construction sites

### Goal

At every site in the analyze pipeline that builds a composite Type from a
literal source-form (vector/set/map literal, raw-value typing, destructure
MapT), thread the constituent provs into the new prov via `prov/with-refs`.

### Mechanism

For each construction site, compute `constituent-provs` as a vector of
`(prov/of constituent-type)` values and wrap the prov:

```clojure
;; before
(at/->VectorT (prov/with-ctx ctx) item-types ...)
;; after
(at/->VectorT (prov/with-refs (prov/with-ctx ctx)
                              (mapv prov/of item-types))
              item-types ...)
```

Sites in scope:
- `analyze/annotate/data.clj:22` `annotate-vector`: `:refs (mapv prov/of item-types)`.
- `analyze/annotate/data.clj:30` `annotate-set`: `:refs [(prov/of joined)]`.
- `analyze/annotate/data.clj:38+` `annotate-map`: read `node` to determine
  the entry constituents; `:refs` is the flattened `[k1-prov v1-prov …]`
  vector.
- `analyze/value.clj:44` `map-value-type`: `:refs` is the flattened
  `[exact-runtime-key-prov, exact-runtime-val-prov, ...]`.
- `analyze/value.clj:62-64` `type-of-value` vec/seq/set arms: `:refs` is the
  vector of constituent provs (same pattern as annotate-vector).
- `analyze/annotate/match.clj:121` MapT in destructure: `:refs` is the
  entry-prov flattening as for annotate-map.

### Files

- Updated: `skeptic/src/skeptic/analysis/annotate/data.clj`
- Updated: `skeptic/src/skeptic/analysis/value.clj`
- Updated: `skeptic/src/skeptic/analysis/annotate/match.clj`
- Updated: matching test files (verify existence; add tests where missing).

### Agents: 1

```
Agents: 1
  Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/analysis/annotate/data.clj,
     skeptic/src/skeptic/analysis/value.clj,
     skeptic/src/skeptic/analysis/annotate/match.clj]
    — Mechanically identical edits at composite-construction lines.
      Tests added to existing test files (one assertion per construction
      shape).
```

### Subagent pre-flight

- **Goal**: At every literal/raw-value composite construction site in the
  three target files, thread the constituent provs into the new prov via
  `prov/with-refs`.
- **Broader context**: Phase 2b.4.2 of 4. Phase 2b.4.1 added the `:refs`
  field with default `[]`. This phase populates it at literal sites only.
  Subsequent phases handle collection ops and map ops.
- **Build/test/lint**: as global.
- **Existing**:
  - `analyze/annotate/data.clj` lines 22-46 (`annotate-vector`,
    `annotate-set`, `annotate-map`).
  - `analyze/value.clj` lines 44-64 (`map-value-type`, `type-of-value`'s
    vector/seq/set arms).
  - `analyze/annotate/match.clj` line 121 (MapT in destructure).
  - `prov/with-refs` from Phase 2b.4.1.
  - `prov/of` returns the prov of a Type or throws.
- **Constraints**:
  - Compute constituent provs locally at each site.
  - For map sites, `:refs` is the flattened `[k1-prov v1-prov k2-prov
    v2-prov ...]` vector — order matches map iteration.
  - Do NOT change the construction's other arguments (item-types, count,
    homogeneous?, etc.).
  - Do NOT touch any file outside the three listed.
- **Deliverables**:
  1. Three production files updated; every composite construction site in
     them populates `:refs`.
  2. Tests in matching test files asserting the resulting Type's
     `(prov/of t) :refs` matches the expected constituent provs for each
     construction shape (one test per shape: vector, set, map, raw-vec,
     raw-seq, raw-set, raw-map, destructure-map).
- **Verification**: `lein test` 100% pass — no existing test should break.
  New tests confirm `:refs` is populated.
- **Every referenced name**:
  - `prov/with-refs`, `prov/of`, `prov/with-ctx`.
  - `at/->VectorT`, `at/->SetT`, `at/->MapT`, `at/->SeqT`.
  - `aapi/normalize-type`, `aapi/dyn`, `(:recurse ctx)`,
    `av/type-join*`, `coll/vec-homogeneous-items?`.

### Completion gate

1. `lein test` 100% pass; `clj-kondo` clean.
2. Update status doc.
3. Commit: `Phase 2b.4.2 (populate :refs at literal sites) complete`.
4. STOP for user approval.

---

## Phase 2b.4.3 — Populate `:refs` at collection-op + invoke-output sites

### Goal

Same population mechanism, applied to collection-operation construction
sites in `analyze/annotate/coll.clj` and `analyze/annotate/invoke_output.clj`.

### Mechanism

Each `at/->VectorT` / `at/->SetT` / `at/->SeqT` construction in these files
threads constituent provs via `prov/with-refs`. Constituent provs come from
the `tail` / `kept` / `[elem]` items being constructed into the new
composite.

Sites in scope:
- `analyze/annotate/coll.clj` lines 36, 112, 118, 124, 133, 142, 150, 160,
  161, 166, 173, 174, 192, 193, 235.
- `analyze/annotate/invoke_output.clj` line 58.

### Files

- Updated: `skeptic/src/skeptic/analysis/annotate/coll.clj`
- Updated: `skeptic/src/skeptic/analysis/annotate/invoke_output.clj`
- Updated: matching test files.

### Agents: 1

```
Agents: 1
  Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/analysis/annotate/coll.clj,
     skeptic/src/skeptic/analysis/annotate/invoke_output.clj]
    — Mechanically identical population at every composite construction
      line in these two files. Tests added per construction shape.
```

### Subagent pre-flight

- **Goal**: Populate `:refs` at every composite construction in `coll.clj`
  and `invoke_output.clj`.
- **Broader context**: Phase 2b.4.3 of 4.
- **Build/test/lint**: as global.
- **Constraints**:
  - At every `at/->VectorT/SetT/SeqT` call, compute constituent provs
    locally and wrap the prov with `prov/with-refs`.
  - Where the constituents are `elem` and `tail` (e.g., conj-shape),
    `:refs` is `(into [(prov/of elem)] (mapv prov/of tail))`.
  - Where the constituents are a single joined element (e.g.,
    `(av/join anchor-prov elems)`), `:refs` is `[(prov/of joined)]`.
  - Do not change other arguments.
- **Deliverables**:
  1. Both files updated; every composite construction populates `:refs`.
  2. Per-construction unit tests in matching test files.
- **Verification**: lein test 100% pass; clj-kondo clean.
- **Every referenced name**:
  - `prov/with-refs`, `prov/of`, `prov/with-ctx`, `ato/derive-prov`,
    `ato/normalize-type`, `ato/normalize`.
  - `at/->VectorT`, `at/->SetT`, `at/->SeqT`, `at/Dyn`.
  - `av/join`, `coll/vec-homogeneous-items?`.

### Completion gate

1. `lein test` 100% pass; `clj-kondo` clean.
2. Update status doc.
3. Commit: `Phase 2b.4.3 (populate :refs at collection-op sites) complete`.
4. STOP for user approval.

---

## Phase 2b.4.4 — Populate `:refs` at map-op + algebra sites; diagnostic update

### Goal

Cover the remaining construction sites: map-op functions (assoc/dissoc/merge
in `map_ops.clj` and `map_ops/algebra.clj`), and `type-substitute` in
`type_algebra.clj`. Then update the diagnostic test to assert the `:refs`
chain on `fn-with-composed-body`'s actual `:result` VectorT.

### Mechanism

Apply `prov/with-refs` at every remaining composite construction:
- `analyze/map_ops.clj:284` (merge-shaped MapT).
- `analyze/map_ops/algebra.clj:31, 47` (assoc/dissoc shaped MapT).
- `analyze/type_algebra.clj:109+, 115+, 123+, 128+, 133+` (substitute
  rebuilds Union/Map/Vector/Set/Seq).

For map ops, `:refs` includes the base map's prov plus the key/value provs
involved in the operation. For type-substitute, `:refs` is the rebuilt
constituents' provs.

### Diagnostic test update

In `skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`:

`diagnostic-composed-body-loss-point`:
- REMOVE the assertions that check `(= :schema (:source result-prov))`,
  `(= expected-qsym (:qualified-sym result-prov))`, and the rendered-output
  fold assertion. They are deferred to Phase 2b.5.
- ADD assertions:
  - `(seq (:refs result-prov))` is truthy — the inferred VectorT's prov
    records its constituent.
  - The single ref in `:refs` corresponds to the call result of
    `(produce-inner-set)`. Verify by walking `:refs` and finding a prov
    whose `:qualified-sym` equals
    `'skeptic.test-examples.form-refs/produce-inner-set`. (This is the
    direct constituent reference; deeper `:refs` chains are not asserted in
    this phase.)

### Files

- Updated: `skeptic/src/skeptic/analysis/map_ops.clj`
- Updated: `skeptic/src/skeptic/analysis/map_ops/algebra.clj`
- Updated: `skeptic/src/skeptic/analysis/type_algebra.clj`
- Updated: `skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`

### Agents: 2

```
Agents: 2
  Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/analysis/map_ops.clj,
     skeptic/src/skeptic/analysis/map_ops/algebra.clj,
     skeptic/src/skeptic/analysis/type_algebra.clj]
    — Populate :refs at remaining composite construction sites in these
      three files.

  Agent 2 (kiss-code-generator): files
    [skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj]
    — Replace fold-pending assertions in diagnostic-composed-body-loss-point
      with :refs assertions reaching produce-inner-set.
```

### Subagent pre-flight (Agent 1)

- **Goal**: Populate `:refs` at the remaining composite construction sites
  in `map_ops.clj`, `map_ops/algebra.clj`, `type_algebra.clj`.
- **Broader context**: Last source-code sub-phase of Phase 2b.4. After this,
  every analyze-pipeline composite carries its references in prov.
- **Build/test/lint**: as global.
- **Constraints**: same as 2b.4.2's constraints.
- **Deliverables**:
  1. Three files updated; every composite construction populates `:refs`.
  2. Per-construction unit tests in matching test files.
- **Verification**: lein test 100% pass; clj-kondo clean.
- **Every referenced name**:
  - `prov/with-refs`, `prov/of`.
  - `at/->VectorT`, `at/->SetT`, `at/->MapT`, `at/->SeqT`, `at/->UnionT`.
  - `entries-without-key` (map_ops/algebra.clj).
  - `type-substitute` (type_algebra.clj).

### Subagent pre-flight (Agent 2)

- **Goal**: Update the diagnostic test to assert `:refs` chain on the
  composed-body's actual `:result` VectorT. Remove the fold-pending
  assertions that this phase intentionally does not deliver.
- **Broader context**: Closing Phase 2b.4. The actual-side fold gap remains
  open; Phase 2b.5 will reintroduce its diagnostic test.
- **Build/test/lint**: as global.
- **Constraints**:
  - Do NOT modify `diagnostic-declared-side-folds`.
  - Do NOT modify `diagnostic-actual-side-loss-point`.
  - In `diagnostic-composed-body-loss-point`:
    - REMOVE the `(= :schema (:source result-prov))`,
      `(= expected-qsym (:qualified-sym result-prov))`, and rendered-fold
      assertions.
    - REMOVE the println debug lines.
    - ADD: `(seq (:refs result-prov))` is truthy.
    - ADD: assert that some prov in `(:refs result-prov)` has
      `:qualified-sym = 'skeptic.test-examples.form-refs/produce-inner-set`.
- **Deliverables**: updated test file, all assertions in it pass.
- **Verification**: lein test 100% pass; clj-kondo clean.
- **Every referenced name**:
  - `prov/of`, `:refs`, `:qualified-sym`, `:source`.
  - `at/value-type?`, `at/map-type?`.

### Completion gate (closes Phase 2b.4)

1. `lein test` in both subprojects — 100% pass.
2. `clj-kondo` clean.
3. Update `docs/current-plans/named-type-folding_IMPLEMENTATION_STATUS.md`
   with full Phase 2b.4 outcome and the explicit note: smoke fold for the
   actual side remains unaddressed; deferred to Phase 2b.5.
4. Commit: `Phase 2b.4 (provenance :refs for inferred provs) complete`.
5. STOP for user approval.

---

## End-to-end verification (Phase 2b.4 only)

Phase 2b.4's verification is purely the lein/lint suite plus the diagnostic
test:

```
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src
```

PLUS the diagnostic confirms the inferred `:result` VectorT now records its
constituent reference in `:refs`.

The clj-threals smoke (`lein skeptic -n clj-threals.operations`) will STILL
show the structural blow-up on the actual side. That is Phase 2b.5's
responsibility (a separate plan that uses the `:refs` chain to drive fold).
Document this explicitly in the Phase 2b.4 commit message.
