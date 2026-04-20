# Malli slice — strict Schema/Malli domain separation cleanup

## Prior architectural error

Two earlier shipped phases (commits `b988171`, `18f3ef5`) kept and codified a `(merge malli-entries schema-entries)` step in `skeptic.typed-decls/typed-ns-results`, plus a `schema-wins-on-malli-conflict` regression test that locked in silent override as desired behavior. Those artifacts violated the architectural rule that Schema and Malli never come into contact: they are two separate input domains, each independently converted to Type, and only Type interacts.

## Cleanup performed in this step

- `src/skeptic/typed_decls.clj` — removed Malli requires (`amb`, `mcollect`); deleted `malli-fun-type->arglists` and `malli-desc->typed-entry`; deleted the `(some? malli-spec)` branch from `desc->typed-entry`; rewrote `typed-ns-results` to call `collect/ns-schema-results` only. The file no longer references Malli in any form.
- `test/skeptic/typed_decls_test.clj` — deleted the `schema-wins-on-malli-conflict` deftest.
- `test/skeptic/test_examples/conflict.clj` — file removed.
- `test/skeptic/checking/pipeline/malli_test.clj` — file removed.
- `AGENTS.md` — replaced the "Conflict policy: Schema wins (silently)" bullet with a "Strict separation" bullet stating that `typed-ns-results` returns Schema-derived entries only and that pipeline-level wiring of Malli is intentionally absent.

## What survives untouched

- `src/skeptic/malli_spec/collect.clj` — domain-pure Malli collector. Standalone.
- `src/skeptic/analysis/malli_spec/bridge.clj` — domain-pure Malli→Type bridge. Standalone.
- `test/skeptic/malli_spec/collect_test.clj` and `test/skeptic/analysis/malli_spec/bridge_test.clj` — domain-internal tests for the surviving modules.
- `test/skeptic/test_examples/malli.clj` — fixture still consumed by the surviving Malli tests.

## Pipeline-level Malli wiring is intentionally absent

This cleanup leaves the analyzer with no source for Malli-derived types at use sites. That is by design and is the subject of a future, separately-authored plan: the analyzer must consume Schema-derived, Malli-derived, and native / dyn / numericdyn-derived types as three independent type sources. Reconciliation across those sources, when they disagree on the same symbol, is itself a further concern — out of scope for this step and the next.

## Source-level proof

`grep -ni 'malli' src/skeptic/typed_decls.clj` returns no matches. `typed-ns-results` calls `collect/ns-schema-results` only.

## Test counts

`lein test`: 290 tests, 1585 assertions, 0 failures, 0 errors.
`clj-kondo --lint src test`: 0 errors, 0 warnings.
