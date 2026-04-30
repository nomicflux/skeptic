# Malli Reference for Skeptic

Scope: the external shape of [Malli](https://github.com/metosin/malli) that Skeptic's MalliSpec domain targets, and what is currently stubbed versus deferred.

Sourced from the official Malli README and `docs/function-schemas.md` at `github.com/metosin/malli`, pinned to release **0.20.1**.

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
    - `[:enum & values]` (optional properties map at index 1 is ignored) → `ato/union-type` over per-value `ato/exact-value-type` results (so dedup / singleton-collapse / ordering match the Schema-side enum behavior at `src/skeptic/analysis/bridge.clj:386-387`).
    - Leaves (`:int`, `:string`, `:keyword`, `:boolean`, `:any`) route through a five-entry primitive table.
    - Bare predicate symbols registered in `skeptic.analysis.predicates` (e.g. `string?`, `int?`, `keyword?`, `pos?`, `nil?`) route through that registry's `witness-type`, mirroring the Schema `(s/pred f)` rule. Predicate symbols outside the registry → `Dyn`.
    - Anything else currently converts to `Dyn`.

### Direct dict admission flow

Admission is direct: `MalliSpec → malli-spec->type → dict[qualified-sym] = Type`. There is no intermediate entry-map shape between the bridge and the dict — `skeptic.typed-decls.malli/typed-ns-malli-results` inserts the bare converted `Type` into the dict keyed by qualified symbol, and carries the MalliSpec origin separately as a `Provenance` record (source `:malli`) in the parallel `:provenance` map. Once the entry is in the dict, analysis treats it identically to a Schema-admitted entry.

## Stubbed now vs. later

Stubbed now:

- Non-primitive leaves and compound forms outside `:=>` / `:maybe` / `:or` / `:enum`. `[:map ...]`, refs, `:tuple`, `:vector`, `:sequential`, `:set`, `:fn`, `:and`, and refinement leaves with `:min`/`:max`/`:re` currently convert to `Dyn`.
- Non-`:=>` callable shapes. `:->` and `:function` do not produce `FnMethodT` / `FunT` values; they convert to `Dyn`.
- Multi-arity under `:function`. No per-method shapes yet.
- Repetition operators (`:?`, `:*`, `:+`, `:repeat`) and `:catn` layouts are admitted but not parsed (the flat `:cat` form is parsed only inside `:=>` for input extraction).
- `malli.util` schemas are admitted if `malli.core/schema` accepts them; they convert to `Dyn`.

Deferred:

- Registry-based discovery through `m/=>`, `malli.experimental/defn`, and `malli.core/function-schemas`.
- Conflict handling when the same var has both a Plumatic `:schema` declaration and a MalliSpec declaration. In this pass, merge order is the only resolution.
- `.skeptic/config.edn` `:type-overrides` and `:skeptic/type` metadata parity. Those remain Schema-only; MalliSpec does not participate.
- Any reverse conversion. `Schema → MalliSpec` and `Type → MalliSpec` do not exist and are not planned.
