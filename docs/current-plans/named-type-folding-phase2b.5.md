# Phase 2b.5 — Named-type folding

## The contract

**The behavioural examples below are the contract.** They come through
on provenance: every Type carries a prov that records where its shape
came from, and the renderer reads provs and prints. 

"Where things came from" is a property the Type carries by construction
on both the admission side (declared schemas) and the analyze side
(inferred values). Both input and output positions are in scope.

Every Type has a fully-formed prov. No exceptions for any reason. A prov contains both where the type comes from
(schema, malli, native, inferred) and what it comes from.

If a behavioural example below does not produce its stated result, the
phase is incomplete regardless of what other tests pass.

## Behavioural examples (the entire contract)

### On-declaration cases (prov stamped on the declared Var's Type)

```
(def Foo (s/named s/Int 'Foo))
(def Bar (s/named s/Int 'Foo))

(s/def x :- Foo)                            ; x carries prov(#'Foo)
(s/def y :- Bar)                            ; y carries prov(#'Bar)
(s/def z :- (s/named s/Int 'Foo))           ; z carries prov of inline
                                             ; Named at z's annotation site
(def MyInt s/Int) (s/def w :- MyInt)        ; w carries prov(#'MyInt)
(s/def q :- s/Int)                          ; q carries prov(s/Int)
```

### Rendered-output cases (renderer prints provs of the value's Type)

Real gate target: `add-with-cache` in
`/Users/demouser/Code/clj-threals/src/clj_threals/operations.clj`.

**Case A — `add-with-cache` analogue:**

```clojure
(s/defschema Threal [#{Threal} #{Threal} #{Threal}])
(s/defschema ThrealCache {Threal Threal})

(s/defn compute-result :- Threal [] [#{} #{} #{}])
(s/defn compute-cache :- ThrealCache [] {})

(s/defn add-with-cache :- {:result Threal :cache ThrealCache}
  []
  {:result (compute-result) :cache (compute-cache)})
```

Required actual-side render:
`{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`.

Function names like `compute-result` MUST NOT appear. Ever.

**Case B — bare call to a fn returning an inline-declared composite:**

```clojure
(s/defschema RecursiveNamed [#{RecursiveNamed}])
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})

(s/defn fn-with-call :- {:result RecursiveNamed}
  []
  {:result (produce-inner-set)})
```

Required actual-side render: `{:result #{RecursiveNamed}}`.

**Case C — call wrapped in a literal vector:**

```clojure
(s/defn fn-with-composed :- {:result RecursiveNamed}
  []
  {:result [(produce-inner-set)]})
```

Required actual-side render: `{:result [#{RecursiveNamed}]}`.

**Case D — pure literal, no calls, no named schemas reachable:**

```clojure
(s/defn fn-with-literal :- {:result RecursiveNamed}
  []
  {:result [#{1 2 3}]})
```

Required actual-side render: `{:result [#{Int}]}`.

## The mechanism (by construction, not by special-casing)

**Provenance preservation by construction at every Type construction
site.** Both admission (`bridge.clj`) and analyze (`analyze.clj`) build
Types. Every site that constructs a Type — leaf or composite, on the
input side or the output side — must produce a prov that records
where that Type came from. Composite Types whose own shape was
inline-declared (no named schema attached) carry a prov with no
qualified-sym, but with `:refs` populated from the constituent Types'
provs.

The known collapse points at admission today are
`bridge.clj:151,189,321,322,326,327` (MapT, ConditionalT, VectorT,
SetT, plus FunT/FnMethodT for fn schemas). They reuse the surrounding
context prov directly, dropping per-node identity and `:refs`. This
is the bug to fix by construction.

`function-import-type` (`bridge.clj:154`) handles fn schemas. Both its
output-schema recursion AND its input-schemas recursion must preserve
provs by the same rule. Inputs are not deferred.

Phase 2b.4 already populated `:refs` on inferred composites in the
analyze pipeline. This phase mirrors that on the admission side and
verifies it covers the input position too.

**Renderer is structural-recursive printing.** Single function. At each
Type node:
- If the node's prov has a qualified-sym, print that symbol and stop.
- Else dispatch on Type kind and recurse into children:
  - `MapT`: `{k₁ v₁, k₂ v₂, …}`.
  - `VectorT`: `[v₁]` (single-element form) or `[v₁ v₂ …]`.
  - `SetT`: `#{v₁}` (single-element form) or `#{v₁ v₂ …}`.
  - `GroundT` / scalar: prov's qualified-sym (always present for
    named scalars).
  - Other kinds: existing structural form, recursing into children.

No `find-foldable-prov`. No `:refs`-walking in the renderer (refs are
for diagnostics; the renderer reads the Type structure directly). No
candidate ranking. No root-vs-non-root special case.

## Phase 0 — Trace before designing

Before any code change:

1. `cd /Users/demouser/Code/clj-threals && lein skeptic -n
   clj-threals.operations` — capture the current rendered actual
   block for `add-with-cache`.
2. Add a temporary diagnostic test that, for each behavioural case
   above (on-declaration AND rendered-output), walks the produced
   Type and dumps `(juxt prov/source :qualified-sym (comp count
   :refs))` at every node.
3. Record the dumps in
   `docs/current-plans/named-type-folding_IMPLEMENTATION_STATUS.md`
   under "Phase 2b.5 trace".
4. Enumerate every node whose prov reports a fn-name (e.g.
   `compute-result`, `produce-inner-set`) or whose qualified-sym is
   wrong relative to the contract. Each such node is a collapse
   point the construction-site fix must address.

## Code style (verbatim)

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

Linter is **clj-kondo**. Test runner is **lein test**.

## Agents: 2

Ordered: 1 → 2.

```
Agent 1 (modular-builder): files
    [skeptic/src/skeptic/analysis/bridge.clj,
     skeptic/test/skeptic/analysis/bridge_test.clj,
     skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj]
  — Phase 0 trace first. Dump all behavioural cases (on-declaration
    + rendered-output), record in status doc, enumerate collapse
    points.
  — At every Type construction site in import-schema-type*
    (MapT, ConditionalT, VectorT, SetT, FunT, FnMethodT — both
    output-schema and INPUT-schemas paths through
    function-import-type), construct the Type's prov by recording
    where that node came from. Composites without a named schema
    carry a prov with absent qualified-sym and :refs populated from
    constituent provs. Composites that ARE a named schema's body
    (Var-ref hit handled by var-import-type) take the named schema's
    prov as today.
  — Do NOT introduce a new prov source. Do NOT use :no-name or any
    sentinel. The qualified-sym is simply absent (nil/missing) when
    no name was declared for the node.
  — Update bridge_test.clj assertions that baked in the collapsed
    behaviour. New assertions verify per-node prov shape against the
    on-declaration cases above.
  — Re-run the diagnostic: every collapse point enumerated in the
    trace must now report either a named schema's qualified-sym or
    no qualified-sym (composite-site, refs populated).
  — complex, alone.

Agent 2 (modular-builder): files
    [skeptic/src/skeptic/analysis/bridge/render.clj,
     skeptic/test/skeptic/analysis/bridge/render_test.clj,
     skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj]
  — Replace the renderer entry with a single structural-recursive
    function: at each Type node, if prov has a qualified-sym, emit
    it; else dispatch on Type kind and recurse.
  — Delete find-foldable-prov and any helpers that supported
    fold-decision ranking.
  — render_test.clj covers one minimal Type per kind (with-name,
    without-name).
  — named_fold_regression_test.clj covers Cases A, B, C, D above
    AND the on-declaration cases. Each test:
      • Defines the fixture exactly as written.
      • Runs the checker pipeline against the fixture ns (or for
        on-declaration cases, asserts the prov directly on the
        admitted Type).
      • Asserts the rendered actual-side string EQUALS the required
        string verbatim. Substring matches are NOT acceptable.
  — complex, alone.
```

## Deliverables

- `bridge.clj` Type construction sites (input AND output positions
  through `function-import-type`) carry per-node provs by
  construction. Composites without a named schema have absent
  qualified-sym and populated `:refs`.
- Diagnostic dump in the status doc covering every behavioural case
  and enumerating every previously-collapsed node.
- `render.clj` rewritten as structural-recursive printing.
- Render tests: per-kind, with-name and without-name.
- Regression tests: every behavioural case (on-declaration +
  rendered-output A/B/C/D) asserting exact strings.

## Files

- Updated: `skeptic/src/skeptic/analysis/bridge.clj`
- Updated: `skeptic/src/skeptic/analysis/bridge/render.clj`
- Updated: `skeptic/test/skeptic/analysis/bridge_test.clj`
- Updated: `skeptic/test/skeptic/analysis/bridge/render_test.clj`
- Created: `skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`
- Created: `skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj`
- Updated: `docs/current-plans/named-type-folding_IMPLEMENTATION_STATUS.md`

## Completion gate

1. `cd skeptic && lein test` — 100% pass.
2. `cd lein-skeptic && lein test` — 100% pass.
3. `clj-kondo --lint src test` — clean in both subprojects.
4. Manual smoke against the real target:
   `cd /Users/demouser/Code/clj-threals && lein skeptic -n
   clj-threals.operations` — actual-side block for `add-with-cache`
   shows `{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`
   verbatim. No fn names anywhere.
5. Every behavioural case in this document passes its exact-string
   assertion.
6. Update status doc.
7. STOP for user approval before any subsequent phase.

## Constraints carried into this phase

- `prov/unknown` is forbidden anywhere in skeptic. Composites without
  a qualified-sym leave the field absent — they do not get a sentinel.
- No pragmatic test-facing overloads. Fix the production code, not
  the test surface.
- Function names MUST NOT appear in rendered output under any
  circumstance.
- Tests assert WHOLE rendered strings against the contract examples.
  Substring matches are not acceptable.
- Inputs are in scope. Output-only carve-outs are forbidden.
- Numbering inflation (2b.5.1, 2b.5.2, …) is the symptom of
  "unblocked the next phase" being mistaken for "complete." This
  phase is complete only when every behavioural case produces its
  required result against the real `clj-threals` target.
- `lein test` is the test runner; `clj-kondo` is the linter.
- Trace the flow: when an assertion fails, walk step-by-step the path
  that would produce the required result. The first step that doesn't
  apply to the input is the bug. State the trace before editing.
- Verification reveals plan gap: if Phase 0's trace contradicts
  anything in this document, STOP and present options. Do not
  silently expand or improvise.
