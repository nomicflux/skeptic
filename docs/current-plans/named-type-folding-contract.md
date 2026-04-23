# Named-type Folding Contract

## The contract

**The behavioural examples below are the contract.** They come through
on provenance: every Type carries a prov that records where its shape
came from, and the renderer reads provs and prints. 

"Where things came from" is a property the Type carries by construction
on both the admission side (declared schemas) and the analyze side
(inferred values). Both input and output positions are in scope.

Every Type has a fully-formed prov. No exceptions for any reason. A prov contains both where the type comes from
(schema, malli, native, inferred) and what it comes from.

If a behavioural example below does not produce its stated result, the
phase is incomplete regardless of what other tests pass.

## Behavioural examples (the entire contract)

### On-declaration cases (prov stamped on the declared Var's Type)

```
(def Foo (s/named s/Int 'Foo))
(def Bar (s/named s/Int 'Foo))

(s/def x :- Foo)                            ; x carries prov(#'Foo)
(s/def y :- Bar)                            ; y carries prov(#'Bar)
(s/def z :- (s/named s/Int 'Foo))           ; z carries prov of inline
                                             ; Named at z's annotation site
(def MyInt s/Int) (s/def w :- MyInt)        ; w carries prov(#'MyInt)
(s/def q :- s/Int)                          ; q carries prov(s/Int)
```

### Rendered-output cases (renderer prints provs of the value's Type)

Real gate target: `add-with-cache` in
`/Users/demouser/Code/clj-threals/src/clj_threals/operations.clj`.

**Case A — `add-with-cache` analogue:**

```clojure
(s/defschema Threal [#{Threal} #{Threal} #{Threal}])
(s/defschema ThrealCache {Threal Threal})

(s/defn compute-result :- Threal [] [#{} #{} #{}])
(s/defn compute-cache :- ThrealCache [] {})

(s/defn add-with-cache :- {:result Threal :cache ThrealCache}
  []
  {:result (compute-result) :cache (compute-cache)})
```

Required actual-side render:
`{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`.

Function names like `compute-result` MUST NOT appear. Ever.

**Case B — bare call to a fn returning an inline-declared composite:**

```clojure
(s/defschema RecursiveNamed [#{RecursiveNamed}])
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})

(s/defn fn-with-call :- {:result RecursiveNamed}
  []
  {:result (produce-inner-set)})
```

Required actual-side render: `{:result #{RecursiveNamed}}`.

**Case C — call wrapped in a literal vector:**

```clojure
(s/defn fn-with-composed :- {:result RecursiveNamed}
  []
  {:result [(produce-inner-set)]})
```

Required actual-side render: `{:result [#{RecursiveNamed}]}`.

**Case D — pure literal, no calls, no named schemas reachable:**

```clojure
(s/defn fn-with-literal :- {:result RecursiveNamed}
  []
  {:result [#{1 2 3}]})
```

Required actual-side render: `{:result [#{Int}]}`.
