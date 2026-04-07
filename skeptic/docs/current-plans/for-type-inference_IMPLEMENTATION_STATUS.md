# for type inference — implementation status

## Phase 1 — Native function lookup (complete)

- `native_fns.clj`: `native-fn-dict` for `clojure.core/inc`; `static-call-native-info` for JVM `Numbers/inc` (analyzer uses `:static-call`, not `:invoke`).
- `annotate.clj`: `annotate-form-loop` merges `anf/native-fn-dict` into the user dict; `annotate-static-call` uses `static-call-native-info` when applicable.
- Test: `native-inc-annotates-via-dict-test` in `annotate_test.clj`.
- Gate: full `lein test` except `for-declared-str-seq-output-fails-when-body-is-int-seq` still failing (Phase 2).

## Phase 2 — `for` element type via AST walk (complete)

- `ast_children.clj` (`skeptic.analysis.ast-children`): shared `child-nodes` / `ast-nodes` delegating to `clojure.tools.analyzer.ast`; `checking.ast`, `checking.pipeline`, and `annotate` require it directly (no re-exports).
- `annotate.clj`: `for-body-element-type` uses `sac/ast-nodes` to find `cons` / `clojure.core/cons` and the first arg type; `lazy-seq-new-type` uses it when the loop body type is unknown.
- Gate: full `lein test` green, including `for-declared-str-seq-output-fails-when-body-is-int-seq`.

## Phase 3 — Annotate regression test for `for` seq type (pending)
