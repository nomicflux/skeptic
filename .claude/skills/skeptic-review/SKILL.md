---
name: skeptic-review
description: Code review for the skeptic repo. Use when the user asks to review pending changes, a diff, a branch, or a PR in this repo. Checks general quality plus skeptic-specific API-boundary and docs-audience rules.
---

# skeptic-review

Review pending changes in the skeptic repo against the repo's rules. Do **not** make edits — produce a written report.

## Inputs

Default scope: uncommitted changes + commits ahead of `main`.
If the user named a ref/range/PR, use that instead.

Collect once at the start, in parallel:
- `git status`
- `git diff main...HEAD` (or the named range)
- `git diff` (unstaged) and `git diff --staged`
- `git log main..HEAD --oneline` for commit messages

Read every changed file at its new contents before judging it. Do not review from the diff alone when the surrounding function matters.

## Required reference reading

Before reviewing, read:
- `skeptic/AGENTS.md` — namespace map, Core Rules, API Boundaries
- `skeptic/docs/blame-for-all.md` — **only if** the diff touches the cast engine, type-domain reasoning, blame, sealing, or anything under `skeptic.analysis.cast.*` / `skeptic.analysis.type*` / `skeptic.analysis.bridge*`

Do not rely on memory of these docs. Re-read each run.

## Checklist

Work through these in order. Cite `file:line` for every finding.

### 1. Contract

- What did the user ask for? Quote it if available (commit messages, PR body, task description the user paste in).
- Does the diff do exactly that — no more, no less?
- Scope creep (unrequested refactors, "cleanup", new abstractions) is a finding.

### 2. skeptic API boundaries

Flag any of the following as violations:

- **Annotate subsystem**: reads/writes of `:form`, `:type`, `:op`, etc. on annotated nodes from outside `skeptic.analysis.annotate.*`. All external callers must go through `skeptic.analysis.annotate.api` (`node-form`, `node-type`, `node-op`, `with-type`, …). Direct `(:type node)` / `(assoc node :type …)` is only allowed inside the per-AST-op annotators that own node shape.
- **Cast subsystem**: cast-result construction or blame/path manipulation done anywhere other than `skeptic.analysis.cast.support`. External callers use `skeptic.analysis.cast` as the dispatcher and `cast.support` helpers — not reaching into `cast.kernel` / `cast.map` internals.
- **Bridge**: schema→type conversion should go through `skeptic.analysis.bridge/schema->type`. `canonicalize` is a documented sibling, not an internal of bridge — direct requires are fine, but new canonicalization logic belongs there, not inlined at call sites.
- **No re-exports** (Core Rule 1): a namespace defining a thin wrapper that just forwards to another namespace is a violation. Require the owning namespace directly instead.
- **No `declare`** (Core Rule 2) unless it is strictly required for real mutual recursion, and even then prefer the recursive-runner pattern (small runner + non-recursive helpers taking the runner as an argument).
- **Type domain is primary** (Core Rules 3–4): new semantic reasoning written against raw Plumatic schema forms instead of the type domain is a violation. Schema forms belong at the boundary (`schema->type`), not inside analysis. Flag conversions going the wrong direction (type → schema for internal reasoning).

If a new consumer needed a helper that does not exist on the API module, the correct fix is to extend the API module — not to reach past it. Flag the latter.

### 3. Type-checking / cast changes vs. `blame-for-all.md`

Only if the diff touches cast, blame, sealing, generalize/instantiate, `nu`-binders, or the `SealedDynT` / `ForallT` / `TypeVarT` machinery:

- Do the changes match the paper's operational algorithm as summarized in `blame-for-all.md` §4–§5?
- Specifically check: `GENERALIZE` target preserves abstraction (produces a polymorphic value, does not pick a concrete witness); `INSTANTIATE` uses `?` as the witness; sealed values (`v : X => ?`) are not inspectable via `is` and do not cross `nu X:=A` binders; casts between `forall` and `?` obey the asymmetric compatibility rules.
- Per §8.1 (Skeptic adaptation note): ordinary Clojure inference must remain first-order. Flag any code that opportunistically synthesizes `forall` during normal cast checking or call-site inference — quantified types are semantic inputs, not inferred shapes.

### 4. Tests

- Every new/changed function should have a matching test in `skeptic/test/...` mirroring the source path, `*-test` suffix.
- Regression-style tests live under `skeptic/test/skeptic/checking/pipeline/` (split by topic: `call_test`, `collection_test`, `control_flow_test`, `contract_test`, `resolution_test`, `reporting_test`, …, with shared helpers in `support.clj`). Use `check-fn` + a `test-dict` entry — not `check-project`. Place the test in the sub-namespace that matches its topic; add a new sibling sub-namespace rather than enlarging an unrelated one if nothing fits. List the current sub-namespaces with `ls skeptic/test/skeptic/checking/pipeline/` at review time, since the split is expected to keep evolving.
- Example fixtures used by those regression tests live under `skeptic/test/skeptic/test_examples/` (split by topic: `basics`, `catalog`, `collections`, `contracts`, `control_flow`, `fixture_flags`, `nullability`, `resolution`). New example code belongs in the topic-matching file, or a new sibling file — again, enumerate the directory at review time rather than trusting this list.
- **Run the full suite** from `skeptic/`: `lein test`. For a single namespace, `lein test :only ns/name`.
- 100% pass rate required; any failure is a blocker. Quote the failing test name(s) and the first few lines of failure output in the report.
- If new/changed functions lack tests, that is a blocker even if the existing suite is green.

### 4a. Lint

- **Run `clj-kondo --lint src test`** from `skeptic/`, and again from `lein-skeptic/` if that project was touched.
- Every warning clj-kondo reports is a finding in this review. The review takes ownership of the whole lint state on the current tree — not just the diff.
- Unused bindings policy: destructured names → remove; function params with callers → underscore-prefix **and** flag; dead defs → delete.

### 5. Style / simplicity

- Functions ≤20 lines (ideally ≤10), including modifications to existing ones.
- Pure functions preferred; side effects at the edges.
- No dead code, no TODOs, no future-proofing, no defensive coding for impossible inputs.
- No comments explaining *what* — only *why*, and only when non-obvious.
- No ad-hoc runner scripts or `clojure -M:test`; tooling is `lein` + `clj-kondo`.

### 6. Docs — audience matters

This is a repeat failure mode; be strict. Match artifact to audience:

- **`README.md`** (repo root): end users consuming Skeptic as a tool. Installation, usage, stable version references.
- **`CHANGELOG.md`**: user-visible changes per release. Any behavior change a user would notice needs an entry.
- **`skeptic/AGENTS.md`**: maintainer guidance — namespace map, rules, API boundaries. New namespaces, moved responsibilities, or new API-module helpers must be reflected here.
- **`skeptic/docs/`**:
  - `annotate-function-map.md` / `cast-function-map.md` — when functions move, are added, or change contract in annotate/cast.
  - `annotate-algorithm.md` / `cast-algorithm.md` — when algorithmic behavior changes.
  - `blame-for-all.md` — reference doc for the paper; do not edit lightly.
  - `current-plans/` / `design/` — planning material.
- **`docs/releasing.md`** (repo root): release mechanics, credentials, CI.

Flags:
- Release mechanics / credentials / internal rationale written into `README.md`.
- User-facing behavior change with no `CHANGELOG.md` entry.
- New/moved/renamed namespace not reflected in `AGENTS.md` namespace map.
- Cast or annotate functional change with no update to the corresponding function map or algorithm doc.
- Doc that describes a *different-shaped* deliverable than what was requested (AGENTS.md §"Deliverable matching").

### 7. Versioning / release

If `project.clj` versions changed, verify `script/verify-monorepo-versions.sh` still passes and that both the `skeptic` and `lein-skeptic` projects moved together. Don't run it unless the user asks.

## Output format

Produce a single report. No edits, no commits.

```
## Summary
<1-3 sentences: does this merit landing as-is, landing with fixes, or reworking?>

## Blockers
- <file:line> — <what and why>

## Should-fix
- <file:line> — <what and why>

## Nits
- <file:line> — <what and why>

## Docs audit
- <which docs were / were not updated, and whether the audience is right>

## Tests
- <coverage summary; any missing tests named explicitly>
```

If there are no items in a section, write `- none` rather than deleting the section.

## Do not

- Do not edit files.
- Do not re-run `git diff` / `git log` after the initial collection — work from what you already have.
- Do not use memory of `AGENTS.md` or `blame-for-all.md`; re-read them each run.
