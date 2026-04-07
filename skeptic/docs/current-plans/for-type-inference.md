# for Type Inference Fix

## Problem

`(for [x [1 2 3]] (inc x))` declared as `[s/Str]` produces no type errors.
It should fail: the body is `Int`, the declared output is `[Str]`.

One test is currently failing:
```
for-declared-str-seq-output-fails-when-body-is-int-seq (pipeline_test.clj:170)
expected output mismatch errors; got none
```

Full root cause analysis: `docs/for-type-inference-fix.md`.

Summary: `for` expands to `(new LazySeq (fn [] (loop ...)))`. `lazy-seq-new-type` extracts the element type from the loop body type, which is `Dyn` because `cons` is unknown. `Dyn → Str` passes via `:residual-dynamic`. No error.

Fix requires two things together:
1. Type `inc` as `Int → Int` so `(inc x)` produces `Int` instead of `Dyn`
2. Walk the annotated body tree to find `(cons Int ...)` and extract `Int` as the element type

---

## Phase 1: Native Function Lookup

**Deliverable**: `native_fns.clj` exists and is wired into annotation so `inc` resolves to `Int → Int`.

### Files
- `src/skeptic/analysis/native_fns.clj` — create (already scaffolded, verify contents)
- `src/skeptic/analysis/annotate.clj` — add require + merge in `annotate-form-loop`

### Steps

1. **Verify `native_fns.clj`** contains exactly:
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

2. **Add require** to `annotate.clj` ns declaration:
   ```clojure
   [skeptic.analysis.native-fns :as anf]
   ```

3. **Update `annotate-form-loop`** (lines ~654–660) to merge native dict:
   ```clojure
   (defn annotate-form-loop
     ([dict form]
      (annotate-form-loop dict form {}))
     ([dict form opts]
      (annotate-ast (merge anf/native-fn-dict dict)
                    (analyze-form form opts)
                    opts)))
   ```
   `anf/native-fn-dict` is first so user entries override it.

### Code Style Checklist
- [ ] Functions < 20 lines (annotate-form-loop stays at 3 lines)
- [ ] No dead code: `native-fn-dict` is immediately used in annotate-form-loop
- [ ] No defensive coding, no TODOs

### Subagent
`kiss-code-generator`

### Phase Completion Gate

```bash
lein test
```
- All existing tests pass (no regressions)
- `for-declared-int-seq-output-must-type-check` still passes (no false positives introduced)
- The failing test still fails at this stage (Phase 2 fixes it)

No linter for this project (Clojure, `lein test` covers compilation).

Update `docs/current-plans/for-type-inference_IMPLEMENTATION_STATUS.md`.

Commit: `git add src/skeptic/analysis/native_fns.clj src/skeptic/analysis/annotate.clj && git commit -m "Phase 1 (native fn dict): wire inc into annotation dict"`

**STOP AND WAIT.**

---

## Phase 2: `for` Element Type Inference via AST Walk

**Deliverable**: `lazy-seq-new-type` falls back to walking the annotated body tree for a `cons` call when the body type is `Dyn`. Both `for` tests pass.

### Files
- `src/skeptic/analysis/annotate.clj` — add `for-body-element-type`, update `lazy-seq-new-type`

### Steps

1. **Add private helper** `for-body-element-type` just above `lazy-seq-new-type` (currently at line ~496):
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

2. **Update `lazy-seq-new-type`**: change the `(if (ato/unknown-type? t) at/Dyn t)` line to:
   ```clojure
   (if (ato/unknown-type? t)
     (or (some-> (for-body-element-type body) ato/normalize-type)
         at/Dyn)
     t)
   ```
   Full updated function:
   ```clojure
   (defn- lazy-seq-new-type
     [class-node args]
     (when (and (= :const (:op class-node))
                (= LazySeq (:val class-node)))
       (let [elem (if (and (seq args)
                           (= :fn (:op (first args)))
                           (= 1 (count (:methods (first args))))
                           (empty? (:params (first (:methods (first args))))))
                    (let [body (:body (first (:methods (first args))))
                          t (-> body :type ato/normalize-type)
                          t (if (at/maybe-type? t)
                              (ato/normalize-type (:inner t))
                              t)]
                      (if (ato/unknown-type? t)
                        (or (some-> (for-body-element-type body) ato/normalize-type)
                            at/Dyn)
                        t))
                    at/Dyn)]
         (at/->SeqT [elem] true))))
   ```

### How This Works

The `for` expansion's non-chunked branch contains `(cons (inc x) (iter ...))`.
After annotation (with `inc` in the dict from Phase 1):
- `(inc x)` where `x : Dyn` → type `Int` (declared output of `inc`)
- `cons` invoke → type `Dyn` (not in dict)
- Loop body → `Dyn`

`for-body-element-type` walks via `tree-seq` using `:children` for traversal:
```
:loop → :if (when) → :if (chunked?) → else :let → :body :invoke cons
                                                           :args[0] type=Int
```
Returns `Int`. `lazy-seq-new-type` returns `SeqT([Int], true)`.

Cast `SeqT([Int], true) → SeqT([Str], true)`: element `Int → Str` → `leaf-overlap?` → false → `cast-fail` ✓

### Code Style Checklist
- [ ] `for-body-element-type`: 10 lines ✓
- [ ] `lazy-seq-new-type`: 16 lines ✓
- [ ] No dead code: helper is immediately used in `lazy-seq-new-type`
- [ ] No defensive coding
- [ ] No TODOs

### Subagent
`kiss-code-generator`

### Phase Completion Gate

```bash
lein test
```
- All 217 tests pass, 0 failures
- Specifically: `for-declared-str-seq-output-fails-when-body-is-int-seq` now PASSES

Update `docs/current-plans/for-type-inference_IMPLEMENTATION_STATUS.md`.

Commit: `git add src/skeptic/analysis/annotate.clj && git commit -m "Phase 2 (for inference): extract element type from cons call in lazy-seq body"`

**STOP AND WAIT.**

---

## Phase 3: Annotate Test for `for` Element Type

**Deliverable**: A test in `annotate_test.clj` that verifies the annotated type of a `for` expression is `SeqT([Int], true)`, not `Dyn`.

This locks in the invariant so future changes can't silently regress it.

### Files
- `test/skeptic/analysis/annotate_test.clj` — add test

### Steps

1. Find the existing `for` structural test at line 153:
   ```clojure
   (testing "for macro expands to loop/recur in the analyzer AST (structural only)"
     (let [root (atst/project-ast
                 (atst/analyze-form '(for [x [1 2]] (skeptic.test-examples/int-add x 0))))]
       (is (atst/find-projected-node root #(= :loop (:op %))))
       (is (atst/find-projected-node root #(= :recur (:op %))))))
   ```

2. Add a new `testing` block (or new `deftest`) after it:
   ```clojure
   (testing "for expression is typed as a homogeneous seq of the body type"
     (let [root (atst/project-ast
                 (atst/analyze-form atst/typed-test-examples-dict
                                    '(for [x [1 2 3]] (skeptic.test-examples/int-add x 0))))]
       (is (at/seq-type? (:type root)))
       (is (:homogeneous? (:type root)))
       (is (= (atst/T s/Int) (first (:items (:type root)))))))
   ```
   Uses `int-add` (already in dict) rather than `inc` (native dict), so it tests the AST-walk path independently of the native dict.

### Code Style Checklist
- [ ] Test covers expected behavior simply
- [ ] No obsessing over edge cases

### Subagent
`kiss-code-generator`

### Phase Completion Gate

```bash
lein test
```
- All tests pass (now 218+ assertions)

Update `docs/current-plans/for-type-inference_IMPLEMENTATION_STATUS.md` with COMPLETE status.

Commit: `git add test/skeptic/analysis/annotate_test.clj docs/current-plans/ && git commit -m "Phase 3 (for inference): add annotate test for seq element type"`

**STOP AND WAIT.**

---

## Subproject: Native Function Lookup (Future)

The user identified this as an ongoing project. `inc` is the minimum for this fix. Future entries:

| Function | Type |
|----------|------|
| `dec` | `Int → Int` |
| `+`, `-`, `*` | `[Int Int] → Int` |
| `str` | `[Any*] → Str` |
| `count` | `[Seqable] → Int` |
| `first`, `rest`, `next` | requires forall or special handling |
| `cons` | requires forall — `[A, ISeq] → SeqT([A])` |

Format decision deferred: could use internal types (current approach) or schema DSL.
