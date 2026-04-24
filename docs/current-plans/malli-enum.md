# Malli `:enum` End-to-End Support

Mirror Schema-side enum handling for the MalliSpec admission path. Move `:enum` from the "Stubbed now" list (`skeptic/docs/malli-reference.md:159`) into the same union-of-exact-values shape that `:or` already uses on the Malli bridge and that `enum-schema?` uses on the Schema bridge.

## Source-of-truth references

- Malli bridge (target): `skeptic/src/skeptic/analysis/malli_spec/bridge.clj` — `form->type` is the single recursive dispatch; predicates `function-shape?`, `maybe-shape?`, `or-shape?` are the existing siblings.
- Schema-side enum (behavior to mirror): `skeptic/src/skeptic/analysis/bridge.clj:386-387` — `(ato/union-type prov (mapv #(ato/exact-value-type prov %) (sb/de-enum schema)))`.
- Malli canonical enum form: `[:enum v1 v2 ...]` or `[:enum {props} v1 v2 ...]`. Properties map (if present) sits at index 1; `m/form` preserves non-empty property maps verbatim (per the vector syntax contract at `skeptic/docs/malli-reference.md:15-19`).
- Failure shape for pipeline test: `ps/single-failure?` (`test/skeptic/checking/pipeline/support.clj:141-154`) compares `:blame`. The existing `or-output-bad` fixture encodes the expected blame in the literal return value (`test/skeptic/test_examples/malli_contracts.clj:16`); enum fixtures follow the same convention.

## Scope

In-scope (this plan):
- `[:enum & values]` and `[:enum properties & values]` admitted through `malli.core/schema`, converted to `(ato/union-type prov (mapv #(ato/exact-value-type prov %) values))` on the Malli bridge, carrying the `:malli-spec` provenance on every member.
- Bridge unit tests for the four shapes (two members, single-member short-circuit, with-properties, heterogeneous value types).
- `test/skeptic/test_examples/malli-contracts` fixtures for an enum output contract (success + bad).
- Pipeline-level regression test that parallels `malli_or_test.clj`.
- Typed-decls `desc->type` unit coverage for `:enum`.
- Reference doc cleanup: remove `:enum` from the stubbed list, list `:enum` alongside `:or` in the handled forms.

Out-of-scope:
- `[:enum {:json-schema/…} …]` or `malli.util`-derived enum variants (admitted if `m/schema` accepts them; still convert to `Dyn` only when not an `:enum` vector head).
- Reverse conversion (`Type → :enum`).
- Set/sequence/tuple and other stubbed compound forms.
- Any Schema-side or cast-side change.

## Code style (applies to every phase)

- Functions <20 lines, <10 if possible. Modifications obey the same rule.
- Helper functions, not nested logic.
- No TODOs. Write working code or don't write it.
- No defensive coding. `m/schema` rejects zero-child or malformed enums before `form->type` sees them; do not add guards for impossible states.
- No duplication: reuse `ato/exact-value-type` and `ato/union-type` as the Schema bridge does at `bridge.clj:387`.
- Pure functions. `prov` flows through every constructor; every sub-type's `prov/of` must report `:malli-spec` (as with `:or`).
- Write the test alongside the function.

## Build / test / lint commands

- Tests: `lein test` (full), `lein test :only <ns>/<deftest>` (targeted).
- Linter: `clj-kondo --lint src test` from `skeptic/`.
- Run from `/Users/demouser/Code/skeptic/skeptic/`.

## Project-specific constraints (verbatim from memory/feedback)

- `prov/unknown` forbidden — every Type has a real named-source prov.
- Never add pragmatic test-facing overloads / defaults to production fns to make tests compile.
- Targeted Edits, not whole-file reverts — when editing existing files, Edit out only the changes needed.
- Use the project's standard test suite (`lein test`), not `clojure -M:test`.
- Use `clj-kondo`, not `eastwood` (despite the `project.clj` plugin entry).
- "Helper function" = any single-place extraction; N>2 copy-pastes → extract; don't duplicate.
- No dead code. Every artifact created in a phase must be exercised in that same phase.

---

## Phase 1 — Bridge recognizes `:enum`

**Deliverables**

A Malli bridge that converts `[:enum & values]` (with or without a properties map) into `(ato/union-type prov (mapv #(ato/exact-value-type prov %) values))`, covered by a dedicated bridge test file.

**Files**

- Edit: `skeptic/src/skeptic/analysis/malli_spec/bridge.clj`
  - Add `enum-shape?` predicate after `or-shape?` (line 51-53 pattern): vector with head `:enum`.
  - Add a private helper `enum-values` that takes the form and returns `(drop 2 form)` when `(map? (second form))`, else `(rest form)`. <10 lines.
  - Add a branch in `form->type` between the `or-shape?` branch (line 70) and the `:else` fallthrough: `(enum-shape? form) (ato/union-type prov (mapv #(ato/exact-value-type prov %) (enum-values form)))`.
  - `ato/exact-value-type` is already in the required `skeptic.analysis.type-ops :as ato` alias — no new require.

- Create: `skeptic/test/skeptic/analysis/malli_spec/bridge_enum_test.clj`
  - Namespace: `skeptic.analysis.malli-spec.bridge-enum-test`.
  - Requires mirror `bridge_or_test.clj` (`sut`, `prov`, `ato`, `at`).
  - `def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil)`.
  - `deftest enum-with-two-keyword-members` — `(sut/malli-spec->type tp [:enum :a :b])` equals `(ato/union-type tp [(ato/exact-value-type tp :a) (ato/exact-value-type tp :b)])`.
  - `deftest enum-with-single-member-short-circuits` — single-value enum produces a union containing exactly one exact-value (parallel to the `:or` single-member test at `bridge_or_test.clj:14-16`).
  - `deftest enum-with-properties-ignores-properties` — `[:enum {:title "c"} :x :y]` equals `[:enum :x :y]` after conversion.
  - `deftest enum-with-heterogeneous-members` — `[:enum :a "b" 1]` produces a union of three exact-value types, one per member type.

**Steps**

1. Edit `bridge.clj`: add `enum-shape?`, `enum-values`, enum branch in `form->type`. Keep each defn ≤10 lines.
2. Write `bridge_enum_test.clj` with the four deftests listed above.
3. Run `lein test :only skeptic.analysis.malli-spec.bridge-enum-test` — all pass.
4. Run `lein test` full suite — all pass.
5. Run `clj-kondo --lint src test` — zero new warnings.

**Agents: 1**
- Agent 1 (`kiss-code-generator`): files [`skeptic/src/skeptic/analysis/malli_spec/bridge.clj`, `skeptic/test/skeptic/analysis/malli_spec/bridge_enum_test.clj`] — small, tightly-scoped edit + test file; well within 3-file cap.

**Phase Completion Gate**

1. `lein test` — 100% pass.
2. `clj-kondo --lint src test` — zero new warnings.
3. Update `docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md` (create at start of Phase 1): record bridge edits, new test file, pass count.
4. `git add skeptic/src/skeptic/analysis/malli_spec/bridge.clj skeptic/test/skeptic/analysis/malli_spec/bridge_enum_test.clj docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md && git commit -m "Phase 1 malli enum bridge complete"`.
5. Run `lein test :only skeptic.analysis.malli-spec.bridge-enum-test` to confirm artifact behavior.
6. STOP AND WAIT for user approval.

---

## Phase 2 — End-to-end: test_examples fixtures, pipeline regression, typed-decls coverage

**Deliverables**

A success/failure fixture pair admitted through the existing `skeptic.test-examples.malli-contracts` namespace, a pipeline-level regression test that parallels `malli_or_test.clj`, and a typed-decls unit test that confirms `desc->type` and `typed-ns-malli-results` surface enums as union-of-exact-values types.

**Files**

- Edit: `skeptic/test/skeptic/test_examples/malli_contracts.clj`
  - Append two defns (after line 27, preserving existing order):
    ```clojure
    (defn ^{:malli/schema [:=> [:cat :int] [:enum :ok :bad]]} enum-output-success
      [_x] :ok)

    (defn ^{:malli/schema [:=> [:cat :int] [:enum :ok :bad]]} enum-output-bad
      [_x] :not-an-enum-member)
    ```
  - The `:blame` on the bad fixture will be `:not-an-enum-member` (the literal return-value expression), matching the `or-output-bad` convention at line 16.

- Create: `skeptic/test/skeptic/checking/pipeline/malli_enum_test.clj`
  - Namespace: `skeptic.checking.pipeline.malli-enum-test`.
  - Requires mirror `malli_or_test.clj` (`clojure.test`, `skeptic.checking.pipeline.support :as ps`).
  - `deftest enum-output-success-passes` — `(is (empty? (ps/result-errors (ps/check-fixture 'skeptic.test-examples.malli-contracts/enum-output-success))))`.
  - `deftest enum-output-bad-fails` — `(is (ps/single-failure? 'skeptic.test-examples.malli-contracts/enum-output-bad :not-an-enum-member))`.

- Edit: `skeptic/test/skeptic/typed_decls/malli_test.clj`
  - Add `deftest desc->type-enum-returns-union-of-exact-values` after `desc->type-non-callable-returns-ground-type` (after line 24): calls `(tdm/desc->type tp {:name 'foo/e :malli-spec [:enum :a :b]})` and asserts the result is `(ato/union-type tp [(ato/exact-value-type tp :a) (ato/exact-value-type tp :b)])` using `at/type=?`.
  - Add `deftest desc->type-enum-in-=>-output` — calls `(tdm/desc->type tp {:name 'foo/f :malli-spec [:=> [:cat :int] [:enum :ok :bad]]})` and asserts `(at/fun-type? t)` is true, `(at/fn-method-output (first (at/fun-methods t)))` equals the enum union.
  - No new requires (both `ato` and `at` already in scope via existing test `:require`s — verify before adding; if `ato` missing, add `[skeptic.analysis.type-ops :as ato]` to the ns form).

**Steps**

1. Edit `malli_contracts.clj`: append the two enum fixtures.
2. Create `malli_enum_test.clj`: write the two pipeline deftests.
3. Edit `malli_test.clj`: add the two typed-decls deftests; adjust `:require` vector only if `ato` is missing.
4. Run `lein test :only skeptic.checking.pipeline.malli-enum-test` — passes.
5. Run `lein test :only skeptic.typed-decls.malli-test` — passes (all old + new).
6. Run full `lein test` — passes.
7. Run `clj-kondo --lint src test` — zero new warnings.

**Agents: 1**
- Agent 1 (`modular-builder`): files [`skeptic/test/skeptic/test_examples/malli_contracts.clj`, `skeptic/test/skeptic/checking/pipeline/malli_enum_test.clj`, `skeptic/test/skeptic/typed_decls/malli_test.clj`] — new file + two edits, each small and coordinated (all three reference the same `:enum` fixture pair); 3-file cap.

**Phase Completion Gate**

1. `lein test` — 100% pass.
2. `clj-kondo --lint src test` — zero new warnings.
3. Update `docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md`: record fixtures added, pipeline test, typed-decls coverage.
4. `git add skeptic/test/skeptic/test_examples/malli_contracts.clj skeptic/test/skeptic/checking/pipeline/malli_enum_test.clj skeptic/test/skeptic/typed_decls/malli_test.clj docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md && git commit -m "Phase 2 malli enum end-to-end complete"`.
5. Run `lein test :only skeptic.checking.pipeline.malli-enum-test` to confirm the end-to-end artifact reports success+failure correctly.
6. STOP AND WAIT for user approval.

---

## Phase 3 — Reference doc update and final status

**Deliverables**

`skeptic/docs/malli-reference.md` reflects the new reality: `:enum` is a handled form alongside `:or` and `:maybe`; not stubbed. Status doc marks the plan complete.

**Files**

- Edit: `skeptic/docs/malli-reference.md`
  - At line 147-148 (the handled-forms bullets), add a bullet after the `:or` line:
    ```
    - `[:enum & values]` → `ato/union-type` over per-value `ato/exact-value-type` results (so dedup / singleton-collapse / ordering match the Schema-side enum behavior). Optional properties map at index 1 is ignored.
    ```
  - At line 159 (the "Stubbed now" bullet), remove `` `:enum`, `` from the enumerated list of types still converting to `Dyn`. Preserve surrounding items verbatim.

- Edit: `docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md`
  - Add a "Phase 3 complete" entry with final test count and a note that remaining stubs (`:map`, `:tuple`, `:vector`, `:sequential`, `:set`, `:fn`, `:and`, refs, refinement leaves, `:->`, `:function`, `:catn`, repetition operators) are unchanged.

**Steps**

1. Edit `malli-reference.md`: add `:enum` handled-forms bullet; remove `:enum,` from the stubbed list.
2. Edit `malli-enum_IMPLEMENTATION_STATUS.md`: record Phase 3 completion.
3. Full `lein test` — still passes (documentation-only phase; sanity check).
4. `clj-kondo --lint src test` — still clean.

**Agents: 1**
- Agent 1 (`kiss-code-generator`): files [`skeptic/docs/malli-reference.md`, `docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md`] — targeted documentation edits; 2-file load.

**Phase Completion Gate**

1. `lein test` — 100% pass.
2. `clj-kondo --lint src test` — zero warnings.
3. Final update to `docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md`: mark feature complete.
4. `git add skeptic/docs/malli-reference.md docs/current-plans/malli-enum_IMPLEMENTATION_STATUS.md && git commit -m "Phase 3 malli enum reference doc complete"`.
5. Smoke run: `lein test :only skeptic.checking.pipeline.malli-enum-test skeptic.analysis.malli-spec.bridge-enum-test skeptic.typed-decls.malli-test` — all pass.
6. STOP AND WAIT for user final acknowledgement.

---

## Parallels and anti-duplication

- Conversion shape `(ato/union-type prov (mapv #(ato/exact-value-type prov %) values))` is the literal Schema-side form at `bridge.clj:387`; reusing it keeps union dedup / singleton-collapse / ordering parity — no new helper module warranted (single call site on the Malli bridge).
- `enum-shape?` + `enum-values` are the same predicate+destructurer shape already present for `function-shape?` / `or-shape?` / `maybe-shape?` — follow that local convention, no separate namespace.
- Fixture file conventions (malli-contracts + pipeline support module) are established by `:or` and `:maybe`. No new test infrastructure.

## Canonicalization evidence (verified)

Observed in `lein repl` against the pinned Malli 0.20.1:

```
user=> (m/form (m/schema [:enum {:title "c"} :x :y]))
[:enum {:title "c"} :x :y]
user=> (m/form (m/schema [:enum :a :b]))
[:enum :a :b]
user=> (m/form (m/schema [:enum {} :a :b]))
[:enum :a :b]
```

Consequences fixed into Phase 1:
- Non-empty properties survive `m/form`, so `enum-values`'s `(map? (second form))` test is the correct detection.
- Empty `{}` properties are elided by `m/form`, so the properties branch is never taken on trivially-propertied input — no additional guard needed.

No other open items. The Schema-side implementation predates this plan and the Malli-side bridge already carries the `:malli-spec` prov discipline end-to-end (per `malli-reference.md:142-144`), so every sub-type produced by the new branch inherits it for free.
