# For Heterogeneous Body Fix

## Failing tests
- `for-even-str-odd-int-declared-int-seq-fails`
- `for-even-str-odd-int-declared-str-seq-fails`

## What the agent already fixed (uncommitted)
- `for-body-element-type`: joins ALL cons first-arg types (`type-join*`) instead of only returning the first
- `seq-call?` passthrough when arg is already SeqT

## What is still missing (outside native dict)

### 1. `seq` of VectorT → SeqT, element type = type ALL vector items
  - NOT just selected items - join of item types if items are produced individually,
  function output type if produced via function, etc. Full type checking. No heuristics allowed.

Extend the `seq-call?` case to also handle VectorT input:

```clojure
(and (ac/seq-call? node)
     (= 1 (count args))
     (at/vector-type? (:type (first args))))
(let [items (:items (:type (first args)))]
  (at/->SeqT [(av/type-join* items)] true))
```

`type-join*` over ALL items — not just the first — gives the element type.
`[1 2 3]` → `VectorT([Int,Int,Int])` → `SeqT([Int], true)`.

Same applies in both `annotate-static-call` and `annotate-invoke`.

### 2. Propagate SeqT element type to loop variable `x`

**Need**: when iterating a `SeqT([T])`, the bound variable gets type `T`.

The correct fix is at the **loop binding level**: when the loop variable `s` has type `SeqT([T])`,
propagate `T` as the type of any binding initialized from element access on `s`.

Exact mechanism TBD — needs user guidance on where/how to hook into the loop annotation.

## Trace after fix (with `str` in native dict)

1. `[1 2 3]` → `VectorT([Int,Int,Int])`
2. `(seq [1 2 3])` → `SeqT([Int], true)` ← fix #1
3. `x : Int` (from SeqT element type) ← fix #2
4. `(str x)` → `Str` (native dict)
5. else: `x : Int`
6. if type → `UnionT({Str, Int})`
7. for element type → `UnionT({Str, Int})`

Casts:
- `→ [Int]`: `Str→Int` fails → error ✓
- `→ [Str]`: `Int→Str` fails → error ✓
- `→ [cond-pre(Int,Str)]`: both members find passing branch → ok ✓
