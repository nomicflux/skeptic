# Malli Reference for Skeptic

Scope: the external shape of [Malli](https://github.com/metosin/malli) that Skeptic's MalliSpec domain targets, and what is currently stubbed versus deferred.

Sourced from the official Malli README and `docs/function-schemas.md` at `github.com/metosin/malli`, pinned to release **0.20.1**.

The entire Malli intake described below — `m/=>`, `mx/defn`, `:malli/schema`
Var-meta, and `malli.core/function-schemas` registry projection — can be
disabled wholesale with the `--malli-disable` CLI flag. When set, no Malli
declaration contributes to the merged dict, no `:malli` provenance is
constructed, and no finding will report `[source: malli]`. See the README's
"Disabling an intake stream" section.

## Schema syntaxes

Malli exposes three parallel surface syntaxes. Skeptic admits values from all of them through `malli.core/schema` (which canonicalizes via `m/form`).

### Vector syntax (default)

Per the README:

```
type
[type & children]
[type properties & children]
```

Examples:

```clojure
;; just a type (String)
:string

;; type with properties
[:string {:min 1, :max 10}]

;; type with properties and children
[:tuple {:title "location"} :double :double]

;; a function schema of :int -> :int
[:=> [:cat :int] :int]
[:-> :int :int]
```

### Map syntax / AST

An alternative map representation, also called Schema AST. Per the README, map syntax is currently considered internal but is produced by `m/ast` and consumed by `m/from-ast`:

```clojure
{:type :string
 :properties {:min 1, :max 10}}

{:type :tuple
 :properties {:title "location"}
 :children [{:type :double} {:type :double}]}

{:type :=>
 :input {:type :cat, :children [{:type :int}]}
 :output :int}
```

### Function schemas: `:=>`, `:->`, `:function`

From `docs/function-schemas.md`:

- `:=>` takes an input sequence schema (`:cat` / `:catn`) and an output schema.

    ```clojure
    [:=> :cat :nil]
    [:=> [:cat :int] :int]
    [:=> [:catn [:x :int] [:xs [:+ :int]]] :int]
    ```

- `:->` is the flat-arrow variant (since 0.16.2). Inputs are listed as a flat sequence, last element is the output. `(m/deref [:-> :int :int])` returns `[:=> [:cat :int] :int]`.

    ```clojure
    [:-> :nil]
    [:-> :int :int]
    [:-> {:guard guard} :int :int]
    ```

- `:function` wraps one or more arrow schemas to describe a multi-arity function:

    ```clojure
    [:function
     [:=> [:cat :int] :int]
     [:=> [:cat :int :int [:* :int]] :int]]
    ```

Sequence schemas used inside arrow inputs include `:cat`, `:catn` (named children), and the repetition operators `:?`, `:*`, `:+`, and `:repeat`. Skeptic admits these but does not currently parse their shape (see "Stubbed now" below).

## Function-schema Var annotations

Per the Defn Schemas section of `docs/function-schemas.md`, Malli itself has
several ways to attach a schema to a function. Skeptic currently discovers only
the `:malli/schema` metadata form below; registry-based `m/=>` and
`malli.experimental/defn` discovery are deferred.

1. `m/=>` — stores the var → schema mapping in the global registry.

    ```clojure
    (defn plus1 [x] (inc x))
    (m/=> plus1 [:=> [:cat :int] small-int])
    ```

2. `:malli/schema` Var metadata — pure data, no runtime dep on malli required at the call site.

    ```clojure
    (defn minus
      {:malli/schema [:=> [:cat :int] small-int]}
      [x]
      (dec x))
    ```

3. `malli.experimental/defn` (aliased `mx/defn`) — Plumatic-style inline type hints; the function is registered automatically.

    ```clojure
    (require '[malli.experimental :as mx])

    (mx/defn times :- :int
      [x :- :int, y :- small-int]
      (* x y))
    ```

Registry-backed function schemas are queryable via `malli.core/function-schemas`
in Malli itself. Skeptic does not currently read that registry.

```clojure
(m/function-schemas)
;=> {user {plus1 {:schema [:=> [:cat :int] [:int {:max 6}]]
;                 :ns user
;                 :name plus1}
;          ...}}
```

Each entry's `:schema` key here is the Malli spec. **In this codebase we never propagate that key unchanged** — `:schema` is reserved for Plumatic Schema. The MalliSpec collector renames it to `:malli-spec` at admission.

## Registry and refs

Malli uses a registry to resolve schema names. The README covers several registry implementations (immutable, var, mutable, dynamic, lazy, composite) under "Schema registry". The function-schema registry above is one consumer of the same machinery. Refs (`:ref`, `:schema`-wrapped references) and the default registry are exercised implicitly whenever Skeptic calls `m/schema` on an admitted form.

## What Skeptic does with MalliSpec

Current boundary (pinned to Malli 0.20.1):

- Discovery (`skeptic.malli-spec.collect`):
  - Reads `:malli/schema` from var metadata.
  - Does not read `m/function-schemas`; `m/=>` and `mx/defn` discovery are deferred.
  - Never reads `:schema` from var metadata — that key belongs to Plumatic Schema.
- Admission (`skeptic.analysis.malli-spec.bridge/admit-malli-spec`):
  - Calls `malli.core/schema` and returns `malli.core/form` on success.
  - Raises `IllegalArgumentException` on failure, carried up as a declaration-phase error.
- Conversion (`skeptic.analysis.malli-spec.bridge/malli-spec->type`):
  - Takes a `Provenance` (source `:malli`) as first argument and threads it through every sub-constructor it produces. `FunT`, `FnMethodT`, `MaybeT`, each `UnionT` member, and every primitive leaf carry that `:malli` prov on their `:prov` field. `prov/of` on any returned sub-Type will report `:malli`.
  - Recursive runner over the admitted form.
    - `[:=> [:cat & inputs] output]` → `FunT` with one `FnMethodT`.
    - `[:maybe X]` → `MaybeT` over the converted inner.
    - `[:or X Y …]` → `ato/union-type` over converted members (so dedup / singleton-collapse / ordering match the Schema-side union behavior).
    - `[:and X Y …]` → `ato/intersection-type` over converted members (so dedup / singleton-collapse / ordering match the Schema-side `sb/both?` behavior at `src/skeptic/analysis/bridge.clj:623-624`).
    - `[:tuple X Y …]` → `at/->VectorT prov [Tx Ty …] nil` directly. The cast engine treats `tail=nil` `VectorT` as closed/exact-arity via `prefix-tail-cast-fails-arity?` at `src/skeptic/analysis/cast/collection.clj:24-26`, and Plumatic vector schemas already import to the same shape via `prefix-tail-import-type` at `src/skeptic/analysis/bridge.clj:644-645`, so a separate tuple type variant is not required. `[:tuple]` (zero-element) collapses in `m/form` to the bare keyword `:tuple` and falls through to `Dyn`.
    - `[:map [:k T] [:k {:optional true} T] …]` → `at/->MapT` directly. Required entries use `ato/exact-value-type prov k` for the key; optional entries wrap that in `at/->OptionalKeyT`. The cast engine consumes both kinds via `amo/map-entry-descriptor` at `src/skeptic/analysis/map_ops.clj:85-110`, and Plumatic plain-map schemas already import to the same shape via `map-import-type` at `src/skeptic/analysis/bridge.clj:393-409`, so no separate map type variant is required. The optional map-level properties index (e.g. `[:map {:closed true} ...]`) is parsed and dropped — Skeptic's `MapT` is closed-by-default (open-ness is encoded by adding a `domain-entry`, e.g. `Keyword → T`), matching Plumatic's representation. Honoring Malli's open-by-default semantics is deferred. `[:map]` (zero-element) collapses in `m/form` to the bare keyword `:map` and falls through to `Dyn`.
    - `[:multi {:dispatch DISP} [tag schema] …]` → `at/->ConditionalT` directly. Each entry produces a branch triple `[tag (form->type schema) descriptor]` with the same shape Plumatic's `s/conditional` imports to via `conditional-import-type` at `src/skeptic/analysis/bridge.clj:464-480`; consumed by `effective-conditional-branches` / `effective-conditional-arms` at `src/skeptic/analysis/conditional_arms.clj:52-76`. The descriptor is `{:path [DISP] :values [tag]}` when `DISP` is a keyword (so later arms are correctly narrowed by negation of earlier tags via `route-conditional-by-values`) and `nil` for fn-dispatch and the `:malli.core/default` sentinel — Malli's `:multi` carries enough structure to emit `:values` descriptors that Plumatic's `s/conditional` cannot, so the bridge uses that structure where available.
    - `[:= X]` → `ato/exact-value-type prov X` directly, mirroring the Plumatic `s/eq` import at `src/skeptic/analysis/bridge.clj:590-591`. Malli rejects `[:= nil]` at admission, so the form is reachable only with non-nil values.
    - `[:enum & values]` (optional properties map at index 1 is ignored) → `ato/union-type` over per-value `ato/exact-value-type` results (so dedup / singleton-collapse / ordering match the Schema-side enum behavior at `src/skeptic/analysis/bridge.clj:386-387`).
    - Leaves resolve through a registry of supported keywords:
      - `:int → Int`, `:string → Str`, `:keyword → Keyword`, `:symbol → Symbol`, `:boolean → Bool`, `:double → Double`, `:float → Float`, `:nil → ValueT(Dyn, nil)`, `:qualified-keyword → Keyword`, `:qualified-symbol → Symbol`, `:any → Dyn`.
      - `:uuid` is admitted by Malli but has no Skeptic ground; it falls through to `Dyn`.
      - `:char`, `:pos-int`, `:neg-int`, and `:nat-int` are not in Malli 0.20.1's default registry and are rejected at admission.
    - Bare predicate symbols registered in `skeptic.analysis.predicates` (e.g. `string?`, `int?`, `keyword?`, `pos?`, `nil?`) route through that registry's `witness-type`, mirroring the Schema `(s/pred f)` rule. Predicate symbols outside the registry → `Dyn`.
    - Anything else currently converts to `Dyn`.

### Direct dict admission flow

Admission is direct: `MalliSpec → malli-spec->type → dict[qualified-sym] = Type`. There is no intermediate entry-map shape between the bridge and the dict — `skeptic.typed-decls.malli/typed-ns-malli-results` inserts the bare converted `Type` into the dict keyed by qualified symbol, and carries the MalliSpec origin separately as a `Provenance` record (source `:malli`) in the parallel `:provenance` map. Once the entry is in the dict, analysis treats it identically to a Schema-admitted entry.

## Stubbed now vs. later

Stubbed now:

- Non-primitive leaves and compound forms outside `:=>` / `:maybe` / `:or` / `:and` / `:tuple` / `:map` / `:multi` / `:=` / `:enum`. Refs, `:vector`, `:sequential`, `:set`, `:fn`, and refinement leaves with `:min`/`:max`/`:re` currently convert to `Dyn`.
- Honoring Malli's open-by-default `:map` semantics. `:closed true` is currently a no-op: every admitted `:map` is closed-by-default, matching Plumatic and `MapT`'s native representation.
- Fn-dispatch in `:multi`. Branches are admitted but their narrowing descriptors are `nil`, so each arm stands alone (no negation refinement from earlier arms). Only keyword-dispatch produces `{:path :values}` descriptors that drive `route-conditional-by-values`.
- Non-`:=>` callable shapes. `:->` and `:function` do not produce `FnMethodT` / `FunT` values; they convert to `Dyn`.
- Multi-arity under `:function`. No per-method shapes yet.
- Repetition operators (`:?`, `:*`, `:+`, `:repeat`) and `:catn` layouts are admitted but not parsed (the flat `:cat` form is parsed only inside `:=>` for input extraction).
- `malli.util` schemas are admitted if `malli.core/schema` accepts them; they convert to `Dyn`.

Deferred:

- Registry-based discovery through `m/=>`, `malli.experimental/defn`, and `malli.core/function-schemas`.
- Conflict handling when the same var has both a Plumatic `:schema` declaration and a MalliSpec declaration. In this pass, merge order is the only resolution.
- `.skeptic/config.edn` `:type-overrides` and `:skeptic/type` metadata parity. Those remain Schema-only; MalliSpec does not participate.
- Any reverse conversion. `Schema → MalliSpec` and `Type → MalliSpec` do not exist and are not planned.
