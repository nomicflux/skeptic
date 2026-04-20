---
name: new-test-case
description: Add a failing regression test from a code sample the user saw fail (or incorrectly pass) the checker in another codebase, then diagnose and fix the root cause. Use when the user pastes a code fragment and says "this is wrong under skeptic" / "skeptic misses this" / "reproduce this failure".
---

# new-test-case

TDD workflow for turning an external checker failure into a skeptic fixture + regression test + root-cause fix.

## 0. Understand the sample

Before touching files:
- Read the code sample the user pasted. State in one sentence what the checker should say about it (mismatch? missed error? spurious error?).
- **Do not modify the sample.** The function under test ships as the user wrote it. If something looks wrong (typo, arity mismatch, apparent bug in the sample itself), **ASK**. Do not silently correct.
- If the expected checker behavior is ambiguous (e.g. "should this be an error or just a warning?", "is `nil` here intended?"), **ASK** before proceeding.

## 1. Locate the right test bucket

Enumerate the current layout every run — it keeps evolving:

```
ls skeptic/test/skeptic/test_examples/
ls skeptic/test/skeptic/checking/pipeline/
```

Decide two homes:

- **Fixture home** in `skeptic/test/skeptic/test_examples/<topic>.clj` — where the user's function gets defined (verbatim) as an `s/defn` (or plain `defn` if that's how the user wrote it).
- **Test home** in `skeptic/test/skeptic/checking/pipeline/<topic>_test.clj` — where the `deftest` asserting the expected checker output lives. Shared helpers are in `skeptic.checking.pipeline.support` (aliased `ps`).

Match topic to meaning (control flow, collections, contracts, nullability, resolution, …). If the sample is a genuinely new category, propose a new sibling file (`<new-topic>.clj` + `<new-topic>_test.clj`) and **ASK** before creating it. Do not enlarge an unrelated bucket.

If a new `test_examples` namespace is added, also wire it into `skeptic/test/skeptic/test_examples/catalog.clj` (`fixture-order` + `fixture-envs`).

## 2. Prefer existing fixtures

Before defining new helper schemas/functions inside the fixture file, grep for equivalents:

- `int-add`, `PosInt` live in `basics.clj`
- common narrowing / maybe helpers live in `contracts.clj` / `nullability.clj`
- shared schemas are usually in the topic file that already uses them

If an existing fixture helper matches, require it and use it. Add a new helper only when nothing existing fits — and keep it minimal and topic-scoped. **The function under test itself is never substituted** — it must appear in the fixture file as the user wrote it.

## 3. Red test first (mandatory)

Write the `deftest` asserting the expected checker output. Model it on neighbours in the same `_test.clj` file — typically `are` + `ps/result-pairs` + `ps/check-fixture` + `incm/mismatched-*-msg` helpers.

Run just that test:

```
cd skeptic && lein test :only skeptic.checking.pipeline.<topic>-test/<deftest-name>
```

It **must fail**. Confirm the failure matches the problem the user described (missing error, wrong error, wrong shape). If the test passes immediately:

- The current skeptic may already handle the case → tell the user, show the actual output, and ask whether this is still a bug.
- The test may be too weak to catch the issue → work with the user on a stronger assertion before proceeding.

Do not continue to step 4 until there is a confirmed red test that reflects the real bug.

## 4. Diagnose before fixing

Read, don't patch. Before editing any source file:

- Re-read `skeptic/AGENTS.md` — note which subsystems own what, and respect the API boundaries:
  - Annotate node reads/writes go through `skeptic.analysis.annotate.api` outside of `annotate.*` internals.
  - Cast-result construction / blame paths live in `skeptic.analysis.cast.support`; the dispatcher is `skeptic.analysis.cast`.
  - Schema→type conversion goes through `skeptic.analysis.bridge/schema->type`.
  - No re-exports, no `declare` except for genuine mutual recursion (prefer recursive-runner pattern).
- If the bug involves cast/blame/sealing/`forall`/`nu`/sealed-dyn, re-read `skeptic/docs/blame-for-all.md` and check the paper's operational rules (GENERALIZE, INSTANTIATE-with-`?`, sealed values, tamper checks).

Then trace: where does the wrong behavior originate? Which annotator produces the bad node type? Which cast rule fires (or fails to fire)? Which blame path is emitted?

When unsure, **add temporary probing tests** in the same `_test.clj` that inspect intermediate state (e.g. what type does the annotate pipeline assign to a specific sub-expression?). Probes beat ad-hoc `println` / REPL edits because they're reproducible and visible. Delete the probes once the root cause is identified.

## 5. Principled fix

Before editing:
- State the root cause in one or two sentences. If you can only state "this case isn't handled", keep diagnosing — that's a symptom, not a cause.
- Identify whether the existing logic should be **generalized to subsume this case** or whether this is a genuinely distinct case that needs its own branch. Prefer unifying with existing logic. Splitting into a new special-case branch is a last resort; if you find yourself reaching for one, reconsider whether an existing branch's predicate is simply too narrow.

Then edit the owning module (annotate per-op file, cast kernel/map, bridge canonicalize/localize, narrowing, etc.). Respect the API boundaries from step 4. Keep the change small and type-domain-first.

## 6. Green + no regressions

- Run the targeted test: it must now pass.
- Run the full suite: `cd skeptic && lein test`. All tests must pass.
- Run the linter: `cd skeptic && clj-kondo --lint src test`. Address warnings the change introduced.
- Remove any temporary probe tests added in step 4.

If any previously-green test is now red, stop and diagnose — either the fix over-reaches or the old test encoded a behavior that conflicts with the new one. Bring the conflict to the user rather than mechanically "fixing" the old test.

## Outputs

When done, report:
- Fixture file(s) touched and the symbol name of the new example.
- Test file and deftest name.
- Root cause in one or two sentences.
- Files changed for the fix, with the principle (generalization / boundary correction / missing case in an existing rule).
- Confirmation that full `lein test` and `clj-kondo` are clean.

## Hard stops — ask the user

- Sample looks buggy / ambiguous.
- Expected checker output is unclear.
- Fits no existing topic and a new file/namespace is proposed.
- Red test unexpectedly passes.
- Fix requires crossing an API boundary (reaching past `annotate.api` / `cast.support` / `bridge`).
- A fix makes an existing test red.
