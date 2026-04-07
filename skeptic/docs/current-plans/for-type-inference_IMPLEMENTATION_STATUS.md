# for type inference — implementation status

## Phase 1 — Native function lookup (complete)

- `native_fns.clj`: `native-fn-dict` for `clojure.core/inc`; `static-call-native-info` for JVM `Numbers/inc` (analyzer uses `:static-call`, not `:invoke`).
- `annotate.clj`: `annotate-form-loop` merges `anf/native-fn-dict` into the user dict; `annotate-static-call` uses `static-call-native-info` when applicable.
- Test: `native-inc-annotates-via-dict-test` in `annotate_test.clj`.
- Gate: full `lein test` except `for-declared-str-seq-output-fails-when-body-is-int-seq` still failing (Phase 2).

## Phase 2 — `for` element type via AST walk (pending)

## Phase 3 — Annotate regression test for `for` seq type (pending)
