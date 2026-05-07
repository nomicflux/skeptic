# Type Domain

> *Snapshot of state as of 2026-05-06.*

A Type is the value Skeptic checks. It is a Clojure value with a record shape,
provenance, and fields that downstream code can inspect. This spoke walks the
Type shapes that appear in the worked example first, then places them in the
larger Type family.

## The Smallest Useful Type Walk

Start with the output declaration in `classify`:

```clojure
:- s/Keyword
```

Admission converts it into a ground Type. The ground is Keyword. The provenance
is schema provenance for `classify`. When checking later asks what output
`classify` promised, this is the expected Type.

Now look at the body:

```clojure
(cond
  (zero? n) :zero
  (even? n) :even
  :else     "odd")
```

Annotation assigns exact value Types to `:zero` and `:even`. Each exact keyword
value still has an inner Keyword shape. The string literal receives a String
shape. The body joins those alternatives into one result Type. The joined result
preserves the fact that two alternatives are acceptable and one is not.

That is the key service the Type domain provides: it carries enough structure
for checking to recurse into the body result instead of reducing the body to an
opaque "mixed" label.

## The Maybe Type In `double-or-zero`

`double-or-zero` starts with:

```clojure
[n :- (s/maybe s/Int)]
```

Admission creates a maybe Type whose inner Type is Int. That shape is not just a
display convention. Narrowing recognizes maybe Types and knows how to split them
when a predicate proves the value is present.

Inside the positive branch of `(some? n)`, the maybe Type gives up the nil case
and leaves the inner Int. That Int Type is what the multiplication sees.

## Function Types And Method Selection

A function Type is a `FunT` containing method Types. A method has inputs and an
output. `classify` has one method:

```text
inputs:  [Int]
output:  Keyword
```

When checking the function body, Skeptic selects the declared method whose arity
matches the analyzed method. The selected method's output becomes the expected
Type for the body. That is how the `s/Keyword` declaration reaches the output
check.

This same structure supports multi-arity functions. The method selection step is
what keeps a two-argument body from being checked against a one-argument method.

## Families Of Types

| Family | Kinds | Role in checking |
|---|---|---|
| Leaves | `DynT`, `BottomT`, `GroundT`, `NumericDynT`, `ValueT` | Values with no structural children or exact values. |
| Wrappers | `RefinementT`, `AdapterLeafT`, `OptionalKeyT`, `VarT` | A semantic shape wrapped around another Type. |
| Collections | `MapT`, `VectorT`, `SetT`, `SeqT` | Structural values whose members can be checked recursively. |
| Functions | `FnMethodT`, `FunT` | Callable boundaries with inputs and outputs. |
| Branching | `MaybeT`, `UnionT`, `IntersectionT`, `ConditionalT` | Alternative or branch-sensitive shapes. |
| References | `PlaceholderT`, `InfCycleT` | Recursive or unresolved references. |
| Quantified | `TypeVarT`, `ForallT`, `SealedDynT` | Abstract boundaries and sealed dynamic values. |

The families are not a taxonomy for its own sake. They decide which cast branch
runs, which narrowing operation is legal, and which renderer path can display
the value.

## Composite Types Preserve Their Children

A composite Type owns its children. A union keeps its members. A map keeps key
and value Types. A vector keeps item Types and tail shape. A function keeps
methods.

For `classify`, this is why the output check can say:

```text
body result has members:
  exact value :zero
  exact value :even
  string
```

The cast does not have to rediscover those alternatives from source text. They
are already in the Type.

## Semantic Equality

Types carry provenance, so ordinary Clojure equality is stricter than semantic
shape equality. Two Int Types can have different provenance and still represent
the same semantic shape for checking.

Skeptic uses semantic Type equality when it needs to compare shapes. That affects
union deduplication, exhaustiveness checks, and tests that compare expected and
actual Types. Provenance remains available for reporting, but it does not make
two otherwise identical Type shapes incompatible.

## Normalization

Normalization prepares Types for later work. It collapses redundant unions,
handles nil-bearing shapes, and gives dispatch a cleaner source and target. The
cast entry point normalizes before choosing a rule, so rule implementations can
focus on their own structural case.

In the worked example, the branch result is normalized into a usable set of
alternatives. The later source-union cast depends on that normalized shape.

## Source Pointers

- `skeptic/analysis/types.clj:->MaybeT` - constructs a maybe Type.
- `skeptic/analysis/types.clj:type=?` - compares semantic Type shape.
- `skeptic/analysis/types.clj:dedup-types` - removes semantically duplicate Types.
- `skeptic/analysis/type_ops.clj:normalize` - canonicalizes Type shapes.
- `skeptic/analysis/types.clj:select-method` - chooses a function method by arity.
- `skeptic/analysis/types.clj:semantic-type-value?` - recognizes Type values.
