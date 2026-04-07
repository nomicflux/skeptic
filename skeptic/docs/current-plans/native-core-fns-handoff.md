# Handoff: native `clojure.core` arithmetic and sequence typing

## Status (shipped on branch)

- **[`native_fns.clj`](../../src/skeptic/analysis/native_fns.clj)** — `number-type` (`GroundT {:class java.lang.Number}`), `static-call-native-info` for `clojure.lang.Numbers`, `native-fn-dict` for `+`, `*`, `inc`, `str`, `format`, `even?`, `odd?`, etc.
- **[`value_check.clj`](../../src/skeptic/analysis/value_check.clj)** — directional `leaf-overlap?` (e.g. `:int` → `Number`, scalars → `Object` where value-safe).
- **[`bridge.clj`](../../src/skeptic/analysis/bridge.clj)** — `s/Num` and `java.lang.Number` bridge to the same `Number` ground as natives; not broad-dynamic `?`.
- **[`annotate.clj`](../../src/skeptic/analysis/annotate.clj)** — seq/collection natives, integral narrowing where applicable, **`let` + unary `fn` invoke** typing for macro-expanded **`for`**: locals bound to `fn` carry `:fn-binding-node`; unary invoke annotates the real argument first, then re-runs `annotate-fn` with `param-type-overrides` so iterator parameters are not stuck at `Dyn` when the collection is static.
- **Casts:** No blanket “union containing `Dyn` always fails” kernel rule (would break sound narrowing). Declared-seq vs mixed-body issues from `for` are addressed by correct iterator param typing.

## Next

See **[`native-core-fns-implementation-plan.md`](native-core-fns-implementation-plan.md)** § *Next step* for optional follow-ups and the full **implementation log**.

## Theory and pitfalls

When changing casts or overlap, keep **[`docs/blame-for-all.md`](../blame-for-all.md)** (§8.1 first-order preference) and project **[`AGENTS.md`](../../AGENTS.md)** in mind.

| Pitfall | Why |
|--------|-----|
| Typing JVM math as **`:int` only** | Host returns wider `Number` implementations. |
| **`at/Dyn`** for numeric or iterator params when a **definite ground** exists | Removes checking; bridge and `for` expansion need static facts. |
| **`+` / `*` invoke arities** | Dict needs `0`–`2` and `:varargs`; literals may still show as nested `Numbers/add`. |
| **`str` args** | `nil` + objects; compose `MaybeT` / `UnionT` with `Object` as needed. |

## References

1. [`docs/blame-for-all.md`](../blame-for-all.md)
2. [`src/skeptic/analysis/cast.clj`](../../src/skeptic/analysis/cast.clj), [`cast/kernel.clj`](../../src/skeptic/analysis/cast/kernel.clj)
3. [`src/skeptic/analysis/annotate.clj`](../../src/skeptic/analysis/annotate.clj) — annotation dispatch, `annotate-invoke`, `annotate-let`
