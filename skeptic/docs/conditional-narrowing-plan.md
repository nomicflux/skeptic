# Plan: Principled `s/conditional` Narrowing for `case` Branches

## Problem

`bridge.clj:101` discards predicate functions from `s/conditional`, producing a bare `union-type`.
`annotate-case` creates no assumption for `(kw local)` discriminants because `local-root-origin`
only works on direct locals — so `x` stays `union(HasA, HasB)` in all branches.

## Fix: 5 files, 5 focused changes

---

### Step 1 — `src/skeptic/analysis/types.clj`: New `ConditionalT` type

Add one new type tag and constructor storing `[[pred-fn type] ...]`:

```clojure
(def conditional-type-tag :skeptic.analysis.types/conditional-type)
(defn ->ConditionalT [branches] {semantic-type-tag-key conditional-type-tag :branches branches})
(defn conditional-type? [t] (tagged-map? t semantic-type-tag-key conditional-type-tag))
```

Add to `known-semantic-type-tags`. In `strip-runtime-closures`, strip pred-fns (they're closures; can't serialize):

```clojure
(conditional-type? t)
(update t :branches (fn [bs] (mapv (fn [[_ typ]] [nil (strip-runtime-closures typ)]) bs)))
```

---

### Step 2 — `src/skeptic/analysis/bridge.clj:101`: Preserve predicates

```clojure
;; Before:
(sb/conditional-schema? schema)
(ato/union-type (map (comp import-schema-type second) (:preds-and-schemas schema)))

;; After:
(sb/conditional-schema? schema)
(at/->ConditionalT (mapv (fn [[pred s]] [pred (import-schema-type s)])
                         (:preds-and-schemas schema)))
```

---

### Step 3 — `src/skeptic/analysis/type_ops.clj`: Flatten ConditionalT in `normalize-type`

Add one case at the top of `normalize-type` so all existing type operations are unaffected.
ConditionalT only survives in the `locals` map as the declared type of a variable.

```clojure
(at/conditional-type? type)
(union-type (map second (:branches type)))
```

---

### Step 4 — `src/skeptic/analysis/annotate.clj`: Detect `(kw local)` and create `:conditional-branch` assumption

In `annotate-case`, after the existing `root` binding, add:

```clojure
kw-root-info
(when (and (= :invoke (:op test-node))
           (ac/keyword-invoke-on-local? test-node))
  (let [kw   (ac/literal-node-value (:fn test-node))
        targ (first (:args test-node))]
    (when-let [r (ao/local-root-origin targ)]
      {:kw kw :root r :type (:type targ)})))
```

When `kw-root-info` is present and the local's type is (or contains) a `ConditionalT`:
- For each branch with literals `lits`, evaluate each predicate:
  `(try (pred {kw lit}) (catch Exception _ false))` for each lit
- Collect matching branch types into a `union-type`
- Create assumption:

```clojure
{:kind :conditional-branch
 :root (:root kw-root-info)
 :narrowed-type <computed-union>
 :polarity true}
```

For the **default branch**: keep ConditionalT branches whose predicate matched NO case value
across any branch (i.e., not matched by any lit in any non-default branch).

---

### Step 5 — `src/skeptic/analysis/origin.clj`: Handle `:conditional-branch` in `apply-assumption-to-root-type`

```clojure
:conditional-branch
(:narrowed-type assumption)
```

The narrowed type was pre-computed at annotation time; no further computation needed here.

---

## Tests

```
lein test :only skeptic.checking.pipeline-test/handles-ab-case-routing
lein test :only skeptic.checking.pipeline-test
```

Both must pass. `handles-ab` must produce `[]` errors. No regressions on existing tests.

---

## Why this is general

Any `s/conditional` schema used as a declared parameter type, where the `case` discriminant is
`(kw local)`, will narrow `local` correctly using the schema's own predicates. Predicate
evaluation is sound: `(pred {kw val})` directly tests whether the conditional branch accepts a
value with that key set to the case literal.
