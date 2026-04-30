# Provenance Reference

A complete, cite-backed reference for Skeptic's provenance system. Every
claim below maps to specific file:line citations; read them if in doubt.

## 1. What provenance is

A **Provenance** records where a Type came from — specifically, the named
declaration (schema, malli spec, type-override, native-fn registry, or
inferred def) in whose admission the Type was constructed. Every semantic
Type in Skeptic carries a `:prov` field as its first record slot.

Provenance is metadata, not identity: two Types with the same structure but
different provs are `type=?`. See §9.

Record definition — `skeptic/src/skeptic/provenance.clj:7`:

```clojure
(defrecord Provenance [source qualified-sym declared-in var-meta refs])
```

| Field           | Meaning                                                       |
|-----------------|---------------------------------------------------------------|
| `source`        | One of five source keywords (§2). Governs rank.               |
| `qualified-sym` | The qualified symbol of the declaration (or `sym`/`fn` name). |
| `declared-in`   | The namespace symbol (or `nil` for `:native`, `:type-override`). |
| `var-meta`      | The declaring var's metadata (or `nil` in most cases).        |
| `refs`          | Constituent provenances referenced by a derived provenance.   |

## 2. The five sources

All five live in `provenance.clj:9-14` (`source-rank-map`). Ranked
high-to-low priority (lower number = higher priority):

| Rank | Source           | Origin                                               |
|------|------------------|------------------------------------------------------|
| 0    | `:type-override` | Explicit user override via `:skeptic/type-overrides` |
| 1    | `:malli`         | Malli `:malli/schema` on a var                       |
| 2    | `:schema`        | Plumatic `s/defn` / `s/defschema` on a var           |
| 3    | `:native`        | Skeptic's built-in native-fn registry                |
| 4    | `:inferred`      | Flow analysis (no declared type)                     |

### 2.1 Admit sites — where each source's prov is constructed

- **`:schema`** — `skeptic/src/skeptic/typed_decls.clj:11-13`
  ```clojure
  (defn- desc->provenance
    [_desc ns qualified-sym]
    (prov/make-provenance :schema qualified-sym ns nil))
  ```
  Called from `convert-desc` (L15-21), invoked per declaration collected by
  `skeptic.schema.collect/ns-schema-results`
  (`skeptic/src/skeptic/schema/collect.clj:223-239`). Each var with
  `:schema` metadata yields one entry keyed on its qualified-sym.

- **`:malli`** — `skeptic/src/skeptic/typed_decls/malli.clj:13-17`
  ```clojure
  (prov/make-provenance :malli qualified-sym ns nil)
  ```
  Gathered from vars carrying `:malli/schema` metadata by
  `skeptic.malli-spec.collect/ns-malli-spec-results`
  (`skeptic/src/skeptic/malli_spec/collect.clj:18-36`).

- **`:type-override`** — three admit sites, all share shape
  `(prov/make-provenance :type-override sym nil nil)`:
  - `skeptic/src/skeptic/typed_decls.clj:74-75` — applied in
    `typed-ns-results` for `(:skeptic/type-overrides opts)`.
  - `skeptic/src/skeptic/config.clj:42`
  - `skeptic/src/skeptic/analysis/annotate.clj:77` — applied during flow
    analysis when an annotation-layer override is present.

- **`:native`** — `skeptic/src/skeptic/analysis/native_fns.clj:14`
  ```clojure
  (defn- native-prov [sym] (prov/make-provenance :native sym nil nil))
  ```
  `native-fn-provenance` (L100-101) maps every key in `native-fn-dict` to
  one of these. Consumed via `pipeline/native-result`
  (`skeptic/src/skeptic/checking/pipeline.clj:382-386`).

- **`:inferred`** — `skeptic/src/skeptic/provenance.clj:46-49`
  ```clojure
  (defn inferred
    [{:keys [name ns]}]
    (make-provenance :inferred name ns nil []))
  ```
  Used when no declared type exists. Seed sites:
  - `skeptic/src/skeptic/analysis/annotate.clj:99,106` — the outer ctx for
    analyzing a def body is seeded with
    `(prov/inferred {:name name :ns ns})`.
  - `skeptic/src/skeptic/checking/pipeline.clj:402` — accessor-summary Dyn
    carries inferred prov.

## 3. Public API (`skeptic.provenance`)

From `skeptic/src/skeptic/provenance.clj`:

| Function                                      | Purpose                                                                        |
|-----------------------------------------------|--------------------------------------------------------------------------------|
| `make-provenance [src qsym ns vmeta]`         | Build a `Provenance` with empty `refs`.                                        |
| `make-provenance [src qsym ns vmeta refs]`    | Build a `Provenance` with explicit constituent `refs`.                         |
| `with-refs [prov refs]`                       | Return `prov` with `:refs` replaced.                                           |
| `inferred [{:keys [name ns]}]`                | Build an `:inferred` prov.                                                     |
| `provenance? [x]`                             | Predicate — true iff `x` is a `Provenance` record.                             |
| `source [p]`                                  | Return `:source` of a prov.                                                    |
| `of [t]`                                      | Return `t`'s `:prov`. **Throws** if `t` is not a Type or `:prov` is nil.       |
| `with-ctx [ctx]`                              | Read the current prov out of an analyzer ctx. **Throws** if ctx lacks a prov.  |
| `set-ctx [ctx p]`                             | Return ctx with `p` installed.                                                 |
| `merge-provenances [p1 p2]`                   | Return the higher-ranked of the two provs. §4.                                 |

The ctx key is private: `:skeptic.provenance/ctx-provenance` (L51).

## 4. Merge semantics

`merge-provenances` (provenance.clj:87-92):

```clojure
(defn merge-provenances
  [p1 p2]
  (if (<= (source-rank p1) (source-rank p2))
    p1
    p2))
```

- Picks the **lower numeric rank** (higher-priority source).
- Reduces across many provs via `reduce`. See tests
  `skeptic/test/skeptic/provenance_test.clj:31-45` — three-way reduce, order
  independent.
- **Verified examples**:
  - `:schema` vs `:malli` → wins `:malli` (test L18-23).
  - `:schema` vs `:native` → wins `:schema` (test L25-29).
  - `:schema` vs `:inferred` → wins `:schema` either order (test L55-59).

Used in:
- `skeptic/src/skeptic/typed_decls.clj:51` — `merge-type-dicts` reduces
  over provs that share a qualified-sym across multiple result dicts
  (schema + malli + native).
- `skeptic/src/skeptic/analysis/type_ops.clj:12` — `derive-prov` reduces
  over the provs of input Types when a new composite Type is constructed
  from several existing Types (e.g. `union-type`, `intersection-type`,
  `normalize`).

## 5. The prov slot on every Type

`skeptic/src/skeptic/analysis/types.clj:123-193` — 24 Type records, each
with `prov` as the **first constructor arg**:

```
DynT BottomT GroundT NumericDynT RefinementT AdapterLeafT OptionalKeyT
FnMethodT FunT MaybeT UnionT IntersectionT MapT VectorT SetT SeqT VarT
PlaceholderT InfCycleT ValueT TypeVarT ForallT SealedDynT ConditionalT
```

There is no "empty" or "provless" Type constructor. Every constructor call
in the codebase is expected to pass a real prov.

## 6. The hard invariant

**Every Type has a real prov. `prov/of` throws on missing. Do not guard.**

- `prov/of` is written to throw by design (provenance.clj:74-81).
- `derive-prov` (type_ops.clj:9-16) also throws if called with inputs that
  have no prov — "signalling a caller without real provenance context".
- `prov/with-ctx` throws if the ctx lacks a prov (provenance.clj:53-58).
- `prov/unknown` does not exist and is forbidden anywhere in the codebase.

A missing prov is an **upstream defect to surface**, never a renderer /
consumer concern. Do not wrap reads in `some->`, `when-let`, `or`, or
`try`.

## 7. How provs propagate

### 7.1 Bridge admission (`schema.core` → Skeptic Types)

`skeptic/src/skeptic/analysis/bridge.clj:197-302` — `import-schema-type*`
receives a ctx that carries `:prov`. Every composite constructor destructures
`prov` from ctx and stamps it on the produced Type:

| Constructor       | Line | Source                                |
|-------------------|------|---------------------------------------|
| `->GroundT`       | 37-44 | `primitive-ground-type`              |
| `->RefinementT`   | 94   | `refinement-import-type`              |
| `->AdapterLeafT`  | 106  | `adapter-leaf-import-type`            |
| `->MapT`          | 137  | `map-import-type`                     |
| `->FnMethodT`     | 152  | `function-import-type`                |
| `->FunT`          | 161  | `function-import-type`                |
| `->ConditionalT`  | 178  | `conditional-import-type`             |
| `->VectorT`       | 281,283 | `collection-import-type` (vector arm) |
| `->SetT`          | 289,291 | `collection-import-type` (set arm)    |
| `->SeqT`          | 297,299 | `collection-import-type` (seq arm)    |
| `->MaybeT`        | 206,244 | explicit arms                         |
| `->OptionalKeyT`  | 228  | via `unary-child-result`              |
| `->ValueT`        | 267  | valued-schema arm                     |
| `->VarT`          | 271  | via `unary-child-result`              |
| `->PlaceholderT`  | 212,195 | placeholder arms                    |
| `->InfCycleT`     | 186  | cycle-break arm                       |
| `Dyn` / `BottomType` / `NumericDyn` | 206/209/216/219 | dyn arms |
| `ato/union-type` / `ato/intersection-type` | 248,251,254,260,263 | via `branch-import-type` / `enum-schema?` |

The ctx's `:prov` is set once at the entry point `import-schema-type`
(bridge.clj:475-483) and **threaded unchanged** through the entire
admission recursion. Bridge does not consult the prov at any decision
point — it only stamps.

### 7.2 Annotation (analyzer AST → Skeptic Types during flow)

`skeptic/src/skeptic/analysis/annotate/` — every Type constructed during
analysis reads the current prov from ctx via `prov/with-ctx`:

Representative sites:
- `annotate/fn.clj:92,105` — `->FnMethodT`, `->FunT` with `prov/with-ctx`.
- `annotate/data.clj:18,28,34,52,72,90,97` — `->VarT`, `->VectorT`, union
  results, `->MapT`, catch types, try types, value types.
- `annotate/control.clj:93,273` — `->MaybeT`, if-join types.
- `annotate/base.clj:25` — literal value types.
- `annotate/match.clj:202,229` — match anchor/joined types.

The ctx prov is seeded once when a def is analyzed
(`skeptic/src/skeptic/analysis/annotate.clj:99-106`):

```clojure
(annotate-node (prov/set-ctx {:dict (or dict {}) ...}
                             (prov/inferred {:name name :ns ns}))
               ...)
```

So every Type constructed while analyzing `foo/bar`'s body carries
`:inferred`-sourced prov with `qualified-sym = bar`, `declared-in = foo`.

An `annotate/api.clj` wrapper layer (L8-38) re-exports common Type
constructors (`dyn`, `bottom`, `numeric-dyn`, `union`, `intersection`,
`exact-value`, `de-maybe`, `unknown?`, `normalize`,
`normalize-for-declared-type`) as ctx-aware variants that pull prov from
`prov/with-ctx` automatically.

### 7.3 Type-ops (derived Types)

`skeptic/src/skeptic/analysis/type_ops.clj`:

- `normalize-type` / `normalize-type-for-declared-type` (L87-129) —
  reconstruct composites under the **caller-supplied prov**. Used when a
  raw schema-literal (`{} [] #{} ()`) reaches a type-walking consumer and
  must be materialized as a MapT/VectorT/etc.
- `union-type` / `intersection-type` (L118-137) — take a prov and a seq
  of members. The **caller's prov** is stamped on the result `UnionT` /
  `IntersectionT`. Members retain their own provs.
- Convenience arity-1 variants (`normalize`, `union`, `intersection`,
  `de-maybe`, `unknown?`, `dyn`, `bottom`, `numeric-dyn`) at L167-210
  derive the prov from the inputs via `derive-prov` — i.e. merge over the
  members' provs, picking the higher-ranked.

`derive-prov` (L9-16):

```clojure
(defn derive-prov
  "Merge attached provenance of typed inputs. Requires at least one input
  — throws otherwise."
  [& types]
  (if (seq types)
    (reduce prov/merge-provenances (map prov/of types))
    (throw (IllegalArgumentException. ...))))
```

### 7.4 Localize (Type → Type, prov preserved)

`skeptic/src/skeptic/analysis/bridge/localize.clj:48-119` —
`localize-semantic-type` rebuilds a Type by reading `(prov/of value)` at
entry and recursing into children, preserving each node's prov. Used to
deep-walk and normalize a Type without losing provenance.

### 7.5 Merge across dict sources

`skeptic/src/skeptic/typed_decls.clj:43-56` — `merge-type-dicts` combines
multiple per-source dicts (schema, malli, native) into one:

- Combines all qualified-syms across sources.
- For each sym, intersects the types (`ato/intersection`) and reduces the
  provs via `prov/merge-provenances` — the merged prov reflects the
  highest-priority declaration for that sym.

## 8. Where prov is read

`prov/of` / `prov/source` call sites in `skeptic/src`:

| File                                                   | Line     | Use                                                                     |
|--------------------------------------------------------|----------|-------------------------------------------------------------------------|
| `skeptic/src/skeptic/checking/pipeline.clj`            | 106      | `prov/of output-type` — tagged-output type construction                 |
| `skeptic/src/skeptic/checking/pipeline.clj`            | 351      | `prov/of (:type entry)` — `resolved-defs-provenance` builds finding prov |
| `skeptic/src/skeptic/inconsistence/report.clj`         | 345,370  | `prov/source (prov/of actual-type)` — tag the finding `:source` field   |
| `skeptic/src/skeptic/analysis/bridge/localize.clj`     | 50       | `prov/of value` — preserve prov during localize recursion               |
| `skeptic/src/skeptic/analysis/type_ops.clj`            | 12       | `keep prov/of types` — derive-prov reduction                            |
| `skeptic/src/skeptic/analysis/calls.clj`               | 33,69    | `prov/with-ctx`, `prov/of fun-type`                                     |
| `skeptic/src/skeptic/analysis/annotate/*.clj`          | many     | `prov/with-ctx ctx` — every Type construction reads the ctx prov        |

The finding-level consumer pattern is: pull `actual-type` off the cast
report, call `(prov/source (prov/of actual-type))`, and attach that as
the finding's `:source` field (see report.clj:345, 370). This is how a
finding tells downstream text/porcelain emitters whether it came from a
schema'd, malli'd, native, or inferred declaration.

## 9. Equality ignores prov

`skeptic/src/skeptic/analysis/types.clj:390-657`:

- `type=?` recursively compares semantic fields directly and intentionally
  omits `:prov`, so two structurally-identical Types with different provs are
  equal.
- `dedup-types` buckets by a provenance-insensitive semantic hash and confirms
  equality with `type=?`, preserving original Type values rather than building
  provenance-stripped copies.
- `type-equal?` = `strip-runtime-closures` then `type=?`.

**Implication**: provenance is purely informational. It plays no role in
cast outcomes, subtyping, or structural matching — only in attribution
(who declared this shape?).

## 10. The dict

`pipeline/namespace-dict` (`skeptic/src/skeptic/checking/pipeline.clj:388-394`)
produces a dict by merging three per-source results:

```clojure
(let [schema-result (typed-decls/typed-ns-results opts ns-sym)
      malli-result  (typed-decls.malli/typed-ns-malli-results opts ns-sym)
      merged        (typed-decls/merge-type-dicts
                      [schema-result malli-result (native-result)])]
  merged)
```

Each source result shape (see `typed_decls.clj:15-21`,
`malli.clj`, `native_fns.clj:91-101`):

```clojure
{:dict       {qualified-sym Type}
 :provenance {qualified-sym Provenance}
 :ignore-body #{qualified-sym ...}
 :errors     [...]}
```

There are two places a prov can be read:
1. **Off a Type** via `prov/of` — the prov stamped on that specific Type
   node (which may differ from the enclosing declaration's prov if the
   Type was constructed during annotation or type-ops).
2. **Out of `:provenance`** — the dict-level prov keyed by the top-level
   qualified-sym. This is the declaration's own prov, set by the
   source-specific admit path.

For a Type that was admitted via `typed-ns-results`, its top-level prov
and the `:provenance` entry match by construction.

## 11. How to extend

To add a new provenance source:

1. Add the keyword to `source-rank-map` in `provenance.clj:9-14` with a
   rank placing it relative to existing sources.
2. Add a per-source admit module (parallel to `typed_decls.clj` /
   `typed_decls/malli.clj` / `native_fns.clj`) that:
   - Collects declarations into `{:entries {qualified-sym desc}}`.
   - Builds a `Provenance` via `prov/make-provenance <new-source> ...`.
   - Calls `ab/schema->type prov schema` (or the malli-bridge equivalent)
     to admit the schema.
   - Returns `{:dict :provenance :ignore-body :errors}`.
3. Wire into `pipeline/namespace-dict` via `merge-type-dicts`.
4. Ensure consumers (text, porcelain, finding-level source tagging) handle
   the new keyword where `prov/source` is read (report.clj:345, 370).

## 12. Testing prov behaviour

`skeptic/test/skeptic/provenance_test.clj` — the canonical unit tests
cover: record shape, predicate, merge picks highest-rank, merge order
independence in reduce, `of` reads `:prov` off a GroundT, schema-beats-
inferred in either merge order.

For admission-side tests (where a Type actually gets constructed), prefer
the `check-fn` + `test-dict` pattern used in
`skeptic/test/skeptic/analysis/origin_test.clj` and elsewhere, not
`check-project`.

## 13. File map (quick reference)

Core:
- `skeptic/src/skeptic/provenance.clj` — record, API, rank, merge.
- `skeptic/src/skeptic/analysis/types.clj` — Type records, `type=?`,
  `dedup-types`.

Admission:
- `skeptic/src/skeptic/schema/collect.clj` — scans vars for `:schema` meta.
- `skeptic/src/skeptic/malli_spec/collect.clj` — scans vars for
  `:malli/schema` meta.
- `skeptic/src/skeptic/typed_decls.clj` — builds per-ns `:schema` /
  `:type-override` results; `merge-type-dicts`.
- `skeptic/src/skeptic/typed_decls/malli.clj` — builds per-ns
  Malli results from internal `:malli-spec` descriptors.
- `skeptic/src/skeptic/analysis/native_fns.clj` — `:native` dict +
  provenance map.
- `skeptic/src/skeptic/analysis/bridge.clj` — plumatic schema →
  Skeptic Type, ctx-threaded prov.
- `skeptic/src/skeptic/analysis/bridge/localize.clj` — prov-preserving
  deep-walk.
- `skeptic/src/skeptic/analysis/type_ops.clj` — `derive-prov`,
  normalize/union/intersection with prov.

Annotation (flow analysis):
- `skeptic/src/skeptic/analysis/annotate.clj` — seeds ctx with
  `prov/inferred`.
- `skeptic/src/skeptic/analysis/annotate/api.clj` — ctx-aware Type
  constructors.
- `skeptic/src/skeptic/analysis/annotate/*.clj` — per-node annotation,
  uses `prov/with-ctx`.

Consumers:
- `skeptic/src/skeptic/checking/pipeline.clj` — `namespace-dict`,
  `resolved-defs-provenance`.
- `skeptic/src/skeptic/inconsistence/report.clj` — tags finding
  `:source` via `prov/source (prov/of actual-type)`.

Tests:
- `skeptic/test/skeptic/provenance_test.clj` — API + merge unit tests.
