# Refactor: Split `skeptic.analysis.annotate` into sub-namespaces

## Goal

`src/skeptic/analysis/annotate.clj` is ~1100 lines. Split pure helper groups into two
new sub-namespaces, keeping the recursive dispatch and public API in the main file.

**Constraints:**
- No re-exports (sub-namespaces are standalone; main ns uses them via aliases)
- No `declare` anywhere — including the existing `(declare annotate-node)` at line 18,
  which must be removed as part of this refactor (see Recursive Runner Pattern below)
- All currently-private functions that move become **public** in their new namespace (no
  leading `-`), since they will be called from `annotate.clj`.
- No new tests for helpers already covered by integration tests in `annotate_test.clj`;
  new unit tests are required for each new namespace because the helpers become
  independently callable.

---

## New files to create

### 1. `src/skeptic/analysis/annotate/coll.clj`

Namespace: `skeptic.analysis.annotate.coll`

Contains all collection element-type inference helpers. None of these call `annotate-node`.

**Requires:**
```clojure
(ns skeptic.analysis.annotate.coll
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av])
  (:import [clojure.lang LazySeq]))
```

(`for-body-element-type` uses `sac/ast-nodes`.)

**Functions to define (in this order — each uses only what precedes it):**

| Function | Source lines | Currently |
|---|---|---|
| `const-long-value` | 81–85 | `defn-` |
| `vec-homogeneous-items?` | 186–188 | `defn-` |
| `seqish-element-type` | 62–70 | `defn-` |
| `vector-to-homogeneous-seq-type` | 72–79 | `defn-` |
| `vector-slot-type` | 159–165 | `defn-` |
| `instance-nth-element-type` | 167–184 | `defn-` |
| `coll-first-type` | 190–200 | `defn-` |
| `coll-second-type` | 202–212 | `defn-` |
| `coll-last-type` | 214–224 | `defn-` |
| `coll-rest-output-type` | 226–243 | `defn-` |
| `coll-butlast-output-type` | 245–250 | `defn-` |
| `coll-drop-last-output-type` | 252–260 | `defn-` |
| `coll-take-prefix-type` | 262–270 | `defn-` |
| `coll-drop-prefix-type` | 272–282 | `defn-` |
| `coll-same-element-seq-type` | 284–287 | `defn-` |
| `concat-output-type` | 289–296 | `defn-` |
| `into-output-type` | 298–313 | `defn-` |
| `invoke-nth-output-type` | 315–318 | `defn-` |
| `for-body-element-type` | 916–927 | `defn-` |
| `lazy-seq-new-type` | 929–947 | `defn-` |

Copy each function body verbatim. Change `defn-` to `defn`. Preserve docstrings if any.

**Internal call graph within this file:**
- `instance-nth-element-type` calls `const-long-value`, `vector-slot-type`
- `coll-rest-output-type`, `coll-butlast-output-type`, `coll-drop-last-output-type`,
  `coll-take-prefix-type`, `coll-drop-prefix-type` all call `vec-homogeneous-items?`
- `coll-same-element-seq-type`, `concat-output-type`, `into-output-type` call `seqish-element-type`
- `invoke-nth-output-type` calls `instance-nth-element-type`
- `for-body-element-type` uses `sac/ast-nodes` and `ac/var->sym`
- `lazy-seq-new-type` calls `for-body-element-type`

No circular dependencies. No `declare` needed.

---

### 2. `src/skeptic/analysis/annotate/numeric.clj`

Namespace: `skeptic.analysis.annotate.numeric`

Contains all integral/numeric type-narrowing helpers. None call `annotate-node`.

**Requires:**
```clojure
(ns skeptic.analysis.annotate.numeric
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))
```

**Functions/defs to define (in this order):**

| Symbol | Source lines | Currently |
|---|---|---|
| `bool-type` | 20–21 | `def` (public) |
| `integral-arg-classes` | 87–88 | `def ^:private` |
| `integral-ground-type?` | 90–98 | `defn-` |
| `inc-dec-narrow-int-output?` | 100–103 | `defn-` |
| `binary-integral-locals-narrow?` | 105–111 | `defn-` |
| `invoke-integral-math-narrow-type` | 113–135 | `defn-` |
| `narrow-static-numbers-output` | 137–157 | `defn-` |

Copy each body verbatim. Change `defn-` to `defn`. `def ^:private` becomes `def`.
`bool-type` was already public — keep it public.

**Internal call graph:**
- `integral-ground-type?` uses `integral-arg-classes`
- `inc-dec-narrow-int-output?` and `binary-integral-locals-narrow?` call `integral-ground-type?`
- `invoke-integral-math-narrow-type` calls `inc-dec-narrow-int-output?`, `binary-integral-locals-narrow?`
- `narrow-static-numbers-output` calls `inc-dec-narrow-int-output?`, `binary-integral-locals-narrow?`

No circular dependencies. No `declare` needed.

---

## Recursive Runner Pattern for `annotate-node`

The existing `(declare annotate-node)` at line 18 exists because the form annotators
(`annotate-binding`, `annotate-fn-method`, `annotate-let`, etc.) are defined before
`annotate-node` but call it by name. Per AGENTS.md Rule 2, the fix is to pass the
recursive runner as a parameter rather than forward-declaring it.

### Mechanism

Add a `:recurse` key to `ctx`. Every form annotator that currently calls `annotate-node`
directly will instead call `((:recurse ctx) ctx node)`.

`annotate-node` injects itself into `ctx` before dispatching, so `:recurse` is always
present when a helper needs to recurse:

```clojure
(defn annotate-node [ctx node]
  (let [ctx (assoc ctx :recurse annotate-node)]
    (abl/with-error-context (node-error-context node)
      (abr/strip-derived-types
        (case (:op node)
          :binding (annotate-binding ctx node)
          ...)))))
```

Since no helper references `annotate-node` by name, no `declare` is needed.
`annotate-node` referring to itself in its own body is ordinary self-recursion — valid
in Clojure without `declare`.

### Call sites to update in `annotate.clj`

Every occurrence of `(annotate-node ctx ...)` inside a form annotator becomes
`((:recurse ctx) ctx ...)`. The full list of functions containing such calls:

- `annotate-children` — `(annotate-node ctx %)` in the reduce body
- `annotate-binding` — `(annotate-node ctx init)`
- `annotate-fn-method` — `(annotate-node ...)` for the body
- `annotate-fn` — calls `annotate-fn-method` (which itself uses `:recurse`; no direct call)
- `annotate-instance-call` — `(annotate-node ctx (:instance node))` and args
- `annotate-static-call` — `(annotate-node ctx %)` for args
- `annotate-invoke` — multiple `(annotate-node ctx ...)` calls
- `annotate-do` — statements and ret
- `annotate-let` — `(annotate-node ...)` for bindings body
- `annotate-loop` — `(annotate-node ...)` for body (two passes)
- `annotate-recur` — exprs
- `annotate-if` — test, then, else
- `annotate-case` — test, then bodies, default
- `annotate-def` — meta-node, init-node
- `annotate-vector` — items
- `annotate-set` — items
- `annotate-map` — keys and vals
- `annotate-new` — class-node and args
- `annotate-with-meta` — meta-node and expr-node
- `annotate-throw` — exception
- `annotate-catch` — class-node and body
- `annotate-try` — body, catches, finally
- `annotate-quote` — expr

The `(assoc (annotate-children ctx node) ...)` default branch in `annotate-node` itself
also calls `annotate-node` via `annotate-children` — that call goes through `:recurse`
correctly since `annotate-children` uses it.

`annotate-ast` and `annotate-form-loop` call `annotate-node` directly and are defined
**after** it — no change needed there.

### Remove the `declare`

Delete line 18: `(declare annotate-node)`

---

## Changes to `src/skeptic/analysis/annotate.clj`

### A. Update the `ns` form

Add two new requires; remove the `LazySeq` import (it moves to `coll.clj`).

**Before (abbreviated):**
```clojure
(ns skeptic.analysis.annotate
  (:require ...
            [skeptic.analysis.type-ops :as ato]
            ...)
  (:import [clojure.lang LazySeq]))
```

**After:**
```clojure
(ns skeptic.analysis.annotate
  (:require ...
            [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.type-ops :as ato]
            ...))
```

Keep all other requires. Remove the `(:import [clojure.lang LazySeq])` clause entirely.

### B. Delete all moved functions

Remove these from `annotate.clj` (they now live in sub-namespaces):

**Moved to `coll`:** `const-long-value`, `seqish-element-type`,
`vector-to-homogeneous-seq-type`, `vec-homogeneous-items?`, `vector-slot-type`,
`instance-nth-element-type`, `coll-first-type`, `coll-second-type`, `coll-last-type`,
`coll-rest-output-type`, `coll-butlast-output-type`, `coll-drop-last-output-type`,
`coll-take-prefix-type`, `coll-drop-prefix-type`, `coll-same-element-seq-type`,
`concat-output-type`, `into-output-type`, `invoke-nth-output-type`,
`for-body-element-type`, `lazy-seq-new-type`.

**Moved to `numeric`:** `bool-type`, `integral-arg-classes`, `integral-ground-type?`,
`inc-dec-narrow-int-output?`, `binary-integral-locals-narrow?`,
`invoke-integral-math-narrow-type`, `narrow-static-numbers-output`.

### C. Update all call sites in `annotate.clj`

Every bare reference to a moved symbol must be prefixed with the appropriate alias.

**`aac/` prefix (coll):**

In `annotate-static-call`:
- `seqish-element-type` → `aac/seqish-element-type` (inside `(ac/seq-call? node)` branch)
- `vector-to-homogeneous-seq-type` → `aac/vector-to-homogeneous-seq-type`

In `annotate-instance-call`:
- `instance-nth-element-type` → `aac/instance-nth-element-type` (for `nth` on instance)

In `annotate-invoke`:
- `coll-first-type` → `aac/coll-first-type`
- `coll-second-type` → `aac/coll-second-type`
- `coll-last-type` → `aac/coll-last-type`
- `invoke-nth-output-type` → `aac/invoke-nth-output-type`
- `coll-rest-output-type` → `aac/coll-rest-output-type`
- `coll-butlast-output-type` → `aac/coll-butlast-output-type`
- `coll-drop-last-output-type` → `aac/coll-drop-last-output-type`
- `const-long-value` → `aac/const-long-value` (two-arg `drop-last`, and two-arg `take` / `drop` branches)
- `coll-take-prefix-type` → `aac/coll-take-prefix-type`
- `coll-drop-prefix-type` → `aac/coll-drop-prefix-type`
- `coll-same-element-seq-type` → `aac/coll-same-element-seq-type`
- `concat-output-type` → `aac/concat-output-type`
- `into-output-type` → `aac/into-output-type`
- `seqish-element-type` → `aac/seqish-element-type` (in `chunk-first-call?` branch)
- `vector-to-homogeneous-seq-type` → `aac/vector-to-homogeneous-seq-type` (in `seq-call?` branch)

In `annotate-vector`:
- `vec-homogeneous-items?` → `aac/vec-homogeneous-items?` (homogeneous flag on literal vectors)

In `annotate-new`:
- `lazy-seq-new-type` → `aac/lazy-seq-new-type`

**`aan/` prefix (numeric):**

In `annotate-static-call`:
- `narrow-static-numbers-output` → `aan/narrow-static-numbers-output` (line 475)

In `annotate-invoke`:
- `invoke-integral-math-narrow-type` → `aan/invoke-integral-math-narrow-type` (after the main `output-type` cond; narrow overlay)

In `annotate-node` dispatch:
- `bool-type` → no direct use in `annotate-node`; it appears inside `annotate-static-call` and `annotate-invoke`

In `annotate-static-call`:
- `bool-type` → `aan/bool-type` (line 463, `(ac/static-contains-call? node)` branch)

In `annotate-invoke`:
- `bool-type` → `aan/bool-type` (line 562, `(ac/contains-call? fn-node)` branch)

**Precise list of all substitutions needed:**

Go through `annotate.clj` after deleting the moved definitions and do a literal search for
each bare symbol. There should be **zero** unresolved references to moved symbols after the
substitutions above.

---

## New test files to create

### `test/skeptic/analysis/annotate/coll_test.clj`

Namespace: `skeptic.analysis.annotate.coll-test`

Requires:
```clojure
(ns skeptic.analysis.annotate.coll-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.types :as at]))
```

Write one `deftest` per tested function. Test expected behavior directly — not edge cases.
Use literal type values from `at/` (e.g. `at/->VectorT`, `at/->SeqT`, `at/Dyn`).

Required tests (minimum):

**`seqish-element-type`**
- returns element type of a homogeneous `VectorT`
- returns element type of a homogeneous `SeqT`
- returns nil for a non-collection type

**`coll-first-type`**
- returns type of first element of a `VectorT`
- returns element type of a homogeneous `SeqT`
- returns nil for empty vector

**`coll-rest-output-type`**
- returns tail `VectorT` for a 3-element vector
- returns a `SeqT` for a 1-element vector (rest of a 1-element vector is empty seq)
- returns a `SeqT` for a homogeneous `SeqT`

**`coll-take-prefix-type`**
- returns a prefix `VectorT` of length n for a long vector
- returns the full vector when n >= length

**`coll-drop-prefix-type`**
- returns suffix `VectorT` after skipping n items
- returns empty `VectorT` when n >= length

**`concat-output-type`**
- returns a homogeneous `SeqT` joining element types of two seq args
- returns `(SeqT [Dyn] true)` for empty args

**`into-output-type`**
- returns `VectorT` when target is a vector
- returns `SeqT` when target is a seq

**`invoke-nth-output-type`**
- returns element type at a known integer index
- returns nil when index is out of range

### `test/skeptic/analysis/annotate/numeric_test.clj`

Namespace: `skeptic.analysis.annotate.numeric-test`

Requires:
```clojure
(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.types :as at]))
```

Required tests (minimum):

**`integral-ground-type?`**
- true for `(at/->GroundT :int 'Int)`
- true for `(at/->ValueT 42)`
- false for `(at/->GroundT :str 'Str)`

**`invoke-integral-math-narrow-type`**
- returns `Int` ground type for `inc` of a non-const integral local
- returns nil for a const argument (narrowing does not apply)
- returns nil for non-integral arg type

---

## Directory structure after refactor

```
src/skeptic/analysis/
  annotate.clj                  ← updated (smaller)
  annotate/
    coll.clj                    ← new
    numeric.clj                 ← new

test/skeptic/analysis/
  annotate_test.clj             ← unchanged
  annotate/
    coll_test.clj               ← new
    numeric_test.clj            ← new
```

---

## Verification

After all changes, run the full test suite. The pass count must not decrease and
`annotate_test.clj` must continue to pass without modification.

```
lein test
```

If any test fails, the failure is in a reference substitution or a missing require — do not
skip or delete tests to achieve a passing run.
