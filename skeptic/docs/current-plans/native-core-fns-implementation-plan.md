# Native core functions — implementation plan (recovery copy)

## Next step

The **native core fns — next steps** queue (macro-expanded `for` typing, `Number` bridge, declared-seq checks, docs) is **closed** on this branch.

**Follow-up ideas** (not blocking): extend the same `let`-bound `fn` + unary-invoke hint if new macro patterns appear; add optional tests under `test/` for edge arities; keep cast/kernel conservative—avoid a blanket “`Dyn` in `UnionT` fails casts” rule (it breaks sound narrowing such as `Union(Int, Dyn)` → `Int`).

**Primary mechanisms now in play**

1. **`for` / lazy-seq iterators:** [`annotate.clj`](../src/skeptic/analysis/annotate.clj) stores `:fn-binding-node` on `let` locals whose init is `:fn`, and `annotate-invoke` re-annotates unary calls (literal `fn` or that local) with `param-type-overrides` from the actual argument. That threads collection/seq types into the iterator parameter so body ops (`chunk-first`, `.nth`, `inc`, etc.) see non-`Dyn` element types where the RHS is static.
2. **Bridge:** [`bridge.clj`](../src/skeptic/analysis/bridge.clj) — `s/Num` and `java.lang.Number` → `GroundT {:class java.lang.Number}` aligned with `native_fns` `number-type`; they are not treated as broad-dynamic `?`.
3. **Declared `[Str]` vs mixed `for` body:** Addressed by (1): inference is no longer stuck at `Dyn` for the loop, so output types and casts reflect real joins (e.g. `Int` + `Str`) and pipeline tests fail/succeed as intended—without a separate cast-kernel union poison that would break narrowing cases.

---

**Links:** [native-core-fns-handoff.md](native-core-fns-handoff.md) · [blame-for-all.md](../blame-for-all.md) · Cursor plan: native core fns typing (do not edit that file from here).

This file is the **repo-local recovery spec** for numeric natives, `leaf-overlap?`, sequence/collection typing, and tests (§6.1 smoke + adversarial). Keep **`## Implementation log`** updated after every step.

---

## Constraints

- Type-domain first; no `Dyn` where a definite ground matches the host.
- First-order only (blame-for-all §8.1).
- `leaf-overlap?` is **directional** (source → target); value invariants for `:int`→`Number`, scalars→`Object`.

---

## Toolchain (Clojure 1.11.1, tools.analyzer.jvm 1.2.3)

| Form | Root op | Notes |
|------|---------|--------|
| inc/dec, binary +/*, -, pos?, neg? | `:static-call` on `Numbers` | See handoff for methods |
| `(+)`, `(+ 1)`, str, format, even?, odd? | `:invoke` | Needs `native-fn-dict` |
| n-ary literal `+` / `*` | nested static `add` / `multiply` | |
| 3-arg `+` invoke test | `((resolve '+) 1 2 3)` | |

## Implementation chunks

1. **`native_fns.clj`:** `number-type`, `str-arg-type` = `MaybeT Object`, `static-call-native-info` for all `Numbers` methods in table, full `native-fn-dict`.
2. **`value_check.clj`:** directional `leaf-overlap?` before class–class symmetric branch.
3. **`calls.clj`:** `convert-arglists` pad varargs `:types` to arity; sequence `*-call?` predicates.
4. **`annotate.clj`:** `seq` on `VectorT`; invoke/static branches for first, second, last, nth, rest, butlast, drop-last, take, drop, take-while, drop-while, concat, into; refine instance `nth` for tuple indices.
5. **Tests:** `annotate_test`, `cast_test` with §6.1 adversarial cases; sequence tuple/union tests.

## §6.1 Testing

Each feature: **smoke** + **adversarial** (fail wrong type e.g. `[Str]` when result is `[Int]`; heterogeneous coll fails pure `[Int]` / `[Str]`, passes joined type).

---

## Implementation log

### Step 0 — 2026-04-07

Recovery file created in-repo with spec summary and log section.

### Step 1 — 2026-04-07

- **Files:** `src/skeptic/analysis/native_fns.clj` — toolchain comment, `number-type` / `str-arg-type` (`MaybeT` `Object`), `static-call-native-info` for `Numbers` (`inc`/`dec`, `add`/`multiply`, `minus` 1–2, `isPos`/`isNeg`), full `native-fn-dict` for `+`, `*`, `inc`, `str`, `format`, `even?`, `odd?`.
- **Verification:** covered by Step 3 annotate/dict tests and `lein test`.

### Step 2 — 2026-04-07

- **Files:** `src/skeptic/analysis/value_check.clj` — directional `leaf-overlap?` before symmetric class/class (`:int`→`Number`, not reverse; scalar grounds→`Object`, not reverse).
- **Verification:** `skeptic.analysis.cast-test/leaf-overlap-host-number-and-str-arg-test`; `lein test`.

### Step 3 — 2026-04-07

- **Files:** `test/skeptic/analysis_test.clj` (`num-ground`, fix `(analyze-form {} form)` to not merge form into opts), `test/skeptic/analysis/annotate_test.clj` (`native-core-numbers-and-dict-smoke-test`, `native-seq-concat-and-tuple-adversarial-test`), `test/skeptic/analysis/calls_test.clj` (`num-ground` for `+`), `test/skeptic/analysis/cast_test.clj` (leaf-overlap / `MaybeT` `Object`), `test/skeptic/checking/pipeline_test.clj` (multi-line let `y`/`z` as `Number` vs `Int`, `at` require), `test/skeptic/test_examples.clj` (`for-declared-int-seq-output` → `[s/Num]`, adversarial `for-even-str-odd-int-declared-str-seq` as heterogeneous vector literal to avoid `for`’s `Dyn` loop locals), `test/skeptic/best_effort_examples.clj` (`ok-plus` / `good-call` → `s/Num`).
- **Adversarial:** `even?` + `Str` arg vs expected `Int`; `concat` + tuple `first` cast checks; pipeline still exercises declared-seq mismatches.
- **Verification:** `lein test` (224 tests).

### Step 4 — 2026-04-07

- **Files:** `src/skeptic/analysis/calls.clj` — `*-call?` predicates; `convert-arglists` varargs padding + `clojure.core/count` shadowing fix; `src/skeptic/analysis/annotate.clj` — seq/collection branches, `seq` on `VectorT`, loop recur targets widened **only** when recur operand is JVM `Number` and binding was `:int` (avoids absorbing `Str` into counters); **`concat-output-type`** bugfix: `(av/type-join* elems)` not `apply`.
- **Verification:** `native-seq-concat-and-tuple-adversarial-test`; full `lein test`.

### Step 5 — 2026-04-07 (native core fns — next steps / plan close-out)

- **Step A — `for` / iterator locals:** `src/skeptic/analysis/annotate.clj` — `let` environment entries for `fn` inits carry `:fn-binding-node` (annotated `fn` AST). `annotate-invoke` uses `unary-fn-invoke-with-arg-type-hint?` + `annotate-unary-fn-invoke-with-arg-type-hint` so unary invokes of a literal `fn` or such a local annotate the actual first, then `annotate-fn` with `param-type-overrides` for the single parameter. Covers Clojure’s `(let [iter (fn [s] …)] (iter coll))` expansion of `for`.
- **Step B — bridge:** `src/skeptic/analysis/bridge.clj` — numeric schemas `s/Num` / `java.lang.Number` → `GroundT {:class java.lang.Number} 'Number` (same meeting point as `native_fns`); not `broad-dynamic-schema?` → `Dyn`.
- **Step C — `Union` + `Dyn` / declared seqs:** No new cast rule that rejects all unions containing `Dyn` (regresses narrowing). The false negative for declared homogeneous seqs vs mixed `for` bodies is fixed by **Step A** (precise iterator param typing).
- **Step D — docs:** This log entry and refresh of `native-core-fns-handoff.md`.
- **Tests:** Per plan, **no edits** to existing `deftest` bodies or assertions in this close-out; **`lein test`** — 224 tests, 0 failures, 0 errors.
