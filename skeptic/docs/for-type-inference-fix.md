# Plan: Fix `for` Type Inference

## Context

The failing test is:

```
for-declared-str-seq-output-fails-when-body-is-int-seq (pipeline_test.clj:170)
expected output mismatch errors; got none
```

The test checks that this function is flagged as a type error:

```clojure
(s/defn for-declared-str-seq-body-int-seq :- [s/Str]
  []
  (for [x [1 2 3]] (inc x)))  ; body is Int, declared output is [Str]
```

The companion test that must continue to pass:

```clojure
(s/defn for-declared-int-seq-output :- [s/Int]
  []
  (for [x [1 2 3]] (inc x)))  ; body is Int, declared output is [Int] — should be OK
```

---

## Root Cause

### How `for` is analyzed

`clojure.tools.analyzer.jvm` expands the `for` macro before analysis. The expansion for `(for [x coll] body)` is roughly:

```clojure
((fn iter [s]
   (lazy-seq                          ; → (new LazySeq (fn [] ...))
     (loop [s (seq s)]
       (when s
         (if (chunked-seq? s)
           (let [c (chunk-first s) ...]
             (chunk-append b body)    ; body type is lost here (void return)
             (chunk-cons ...))
           (let [x (first s)]
             (cons body (iter ...)))  ; ← body type is arg[0] of cons
           )))))
 coll)
```

`annotate_test.clj:153` confirms this produces `:loop` and `:recur` nodes.

### How `lazy-seq-new-type` works

`annotate-new` calls `lazy-seq-new-type` when it sees `(new LazySeq (fn [] body))`. It extracts the element type from the body:

```clojure
(let [body (:body (first (:methods (first args))))
      t    (-> body :type ato/normalize-type)
      ...]
  (if (ato/unknown-type? t) at/Dyn t))
```

The body is the `:loop` node. The loop's type is the type of the loop body expression — a `(when s (if ...))` — which resolves to `Dyn` because `cons` and `chunk-cons` are unknown functions in the dict. So `t = Dyn`, and `lazy-seq-new-type` returns `SeqT([Dyn], true)`.

### Why no error is reported

`def-output-results` in `pipeline.clj` calls `output-cast-report` comparing actual vs declared output type.

- Actual: `SeqT([Dyn], true)`
- Declared `[s/Str]`: `SeqT([Str], true)`

`check-seq-cast` compares element types: `Dyn → Str`. In `check-leaf-cast`:

```clojure
(or (at/dyn-type? source-type)
    (at/placeholder-type? source-type))
(ascs/cast-ok source-type target-type :residual-dynamic)
```

`Dyn → Str` passes unconditionally. No error is reported. Same for `Dyn → Int`, which is why the first test passes for the wrong reason.

---

## Fix: Two Changes Required Together

Neither change alone is sufficient.

### Change 1 — Native function dict: type `inc` as `Int → Int`

Without this, `(inc x)` where `x : Dyn` produces `Dyn`. The AST walk (Change 2) would then find `cons(Dyn, ...)` and return `SeqT([Dyn], true)` — still wrong.

With this, `(inc x)` where `x : Dyn` produces output type `Int` (declared output; `Dyn → Int` input check passes via residual-dynamic).

**File**: `src/skeptic/analysis/native_fns.clj` — already created.

```clojure
(ns skeptic.analysis.native-fns
  (:require [skeptic.analysis.types :as at]))

(def ^:private int-type (at/->GroundT :int 'Int))

(def native-fn-dict
  {'clojure.core/inc {:name 'clojure.core/inc
                      :output-type int-type
                      :arglists {1 {:arglist '[n]
                                    :types [{:name 'n :type int-type :optional? false}]
                                    :count 1}}}})
```

### Change 2 — AST walk in `lazy-seq-new-type` to find `cons` first-arg type

With `inc` typed, `(inc x)` is `Int`. The `cons` call in the non-chunked for branch is `(cons Int ...)`. But `cons` is not in the dict, so the invoke node for `cons` has type `Dyn`. The loop and lazy-seq body type remain `Dyn`.

The fix: when `lazy-seq-new-type` finds `t = Dyn`, walk the annotated body tree to find the first `:invoke` node whose fn resolves to `clojure.core/cons`, and extract the type of its first arg.

The for expansion's non-chunked branch is:
```
:loop → :if (when) → :if (chunked?) → else: :let → :body: :invoke cons
                                                              :args[0]: (inc x) type=Int
```

`tree-seq` with `:children`-based child extraction will reach the `cons` invoke.

**New private helper** in `annotate.clj`:

```clojure
(defn- for-body-element-type
  [body]
  (let [children (fn [node]
                   (mapcat (fn [k]
                             (let [v (get node k)]
                               (cond (vector? v) v (map? v) [v] :else [])))
                           (:children node)))]
    (->> (tree-seq map? children body)
         (keep (fn [node]
                 (when (= :invoke (:op node))
                   (let [fn-sym (or (ac/var->sym (:var (:fn node)))
                                    (-> node :fn :form))]
                     (when (contains? #{'clojure.core/cons 'cons} fn-sym)
                       (some-> node :args first :type))))))
         first)))
```

**Updated `lazy-seq-new-type`** — add fallback after the `unknown-type?` check:

```clojure
(if (ato/unknown-type? t)
  (or (some-> (for-body-element-type body) ato/normalize-type)
      at/Dyn)
  t)
```

### Change 3 — Merge native dict in `annotate-form-loop`

The native dict must be visible during annotation so `annotate-var-like` can find `inc`.

```clojure
(defn annotate-form-loop
  ([dict form]
   (annotate-form-loop dict form {}))
  ([dict form opts]
   (annotate-ast (merge anf/native-fn-dict dict)   ; native entries, overridable by user dict
                 (analyze-form form opts)
                 opts)))
```

`anf/native-fn-dict` comes first so user entries take precedence.

---

## Full Type-Check Trace After Fix

For `(for [x [1 2 3]] (inc x))` declared as `[s/Str]`:

1. `for` expansion analyzed; `inc` found in dict with output `Int`
2. `(inc x)` where `x : Dyn` → type `Int`
3. `cons(Int, iter(...))` → `cons` not in dict, invoke type `Dyn`
4. Loop body type → `Dyn` (from when/if join)
5. `lazy-seq-new-type`: `t = Dyn` → unknown → walk body → finds `cons` invoke → first arg type `Int`
6. `for` → `SeqT([Int], true)`
7. Cast `SeqT([Int], true) → SeqT([Str], true)`: element cast `Int → Str` → `check-leaf-cast` → `leaf-overlap? Int Str` → false → `cast-fail`
8. Error reported ✓

For `[s/Int]`: element cast `Int → Int` → exact match → OK ✓

---

## Files to Modify

| File | Change |
|------|--------|
| `src/skeptic/analysis/native_fns.clj` | **Created** — native function dict (currently has `inc`) |
| `src/skeptic/analysis/annotate.clj` | Add `anf` require, add `for-body-element-type`, update `lazy-seq-new-type`, update `annotate-form-loop` |

---

## Subproject Note: Native Function Lookup

The user identified that a proper solution requires building out a native function type lookup. `inc` is the minimum needed for this test. Future candidates:

- Numeric: `dec`, `+`, `-`, `*`, `/`, `mod`, `rem`, `quot`, `abs`
- Seq: `cons`, `first`, `rest`, `next`, `seq`, `count`
- String: `str`, `subs`, `clojure.string/*`

The format question ("however that needs to look") is deferred — current format uses internal types directly (same as typed-decls output). Schema-based DSL is possible future work.

---

## Verification

Run `lein test` — expect all 217 tests pass, 0 failures.
