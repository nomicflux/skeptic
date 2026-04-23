# Phase 2b.5 Replacement Plan - Post-Audit, Self-Contained

## Purpose

This file is the handoff plan derived from the completed audit.

There is no audit step in this plan. The audit has already been done. The
purpose of this file is to tell the next agent exactly what to change in the
code so the implementation satisfies the literal contract in
`docs/current-plans/named-type-folding-contract.md`.

This phase does not introduce universal provenance. Universal provenance is
already a required invariant of the system. The work here is to preserve that
invariant while fixing named-type folding on the audited failing paths.

## Contract

**The behavioural examples below are the contract.** They come through on
provenance: every Type carries a prov that records where its shape came from,
and the renderer reads provs and prints.

"Where things came from" is a property the Type carries by construction on
both the admission side (declared schemas) and the analyze side (inferred
values). Both input and output positions are in scope.

Every Type has a fully-formed prov. No exceptions for any reason. A prov
contains both where the type comes from (`schema`, `malli`, `native`,
`inferred`) and what it comes from.

If a behavioural example below does not produce its stated result, the phase
is incomplete regardless of what other tests pass.

### Declaration-side contract

```clojure
(def Foo (s/named s/Int 'Foo))
(def Bar (s/named s/Int 'Foo))

(s/def x :- Foo)                            ; x carries prov(#'Foo)
(s/def y :- Bar)                            ; y carries prov(#'Bar)
(s/def z :- (s/named s/Int 'Foo))           ; z carries prov of inline
                                             ; Named at z's annotation site
(def MyInt s/Int) (s/def w :- MyInt)        ; w carries prov(#'MyInt)
(s/def q :- s/Int)                          ; q carries prov(s/Int)
```

### Rendered-output contract

Real gate target: `add-with-cache` in
`/Users/demouser/Code/clj-threals/src/clj_threals/operations.clj`.

Case A:

```clojure
(s/defschema Threal [#{Threal} #{Threal} #{Threal}])
(s/defschema ThrealCache {Threal Threal})

(s/defn compute-result :- Threal [] [#{} #{} #{}])
(s/defn compute-cache :- ThrealCache [] {})

(s/defn add-with-cache :- {:result Threal :cache ThrealCache}
  []
  {:result (compute-result) :cache (compute-cache)})
```

Required render:

```clojure
{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}
```

Function names such as `compute-result` and `compute-cache` must never appear.

Case B:

```clojure
(s/defschema RecursiveNamed [#{RecursiveNamed}])
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})

(s/defn fn-with-call :- {:result RecursiveNamed}
  []
  {:result (produce-inner-set)})
```

Required render:

```clojure
{:result #{RecursiveNamed}}
```

Case C:

```clojure
(s/defn fn-with-composed :- {:result RecursiveNamed}
  []
  {:result [(produce-inner-set)]})
```

Required render:

```clojure
{:result [#{RecursiveNamed}]}
```

Case D:

```clojure
(s/defn fn-with-literal :- {:result RecursiveNamed}
  []
  {:result [#{1 2 3}]})
```

Required render:

```clojure
{:result [#{Int}]}
```

## Audited Starting State

This section is not an audit step. It is the self-contained statement of what
the completed audit established, so the next agent does not need prior session
context.

### 1. The inline `s/named` declaration case currently anchors to the wrong site

The failing declaration case is:

```clojure
(s/def z :- (s/named s/Int 'Foo))
```

Current code path:

- `skeptic.typed-decls/convert-desc` in
  `skeptic/src/skeptic/typed_decls.clj` creates `base-prov` via
  `desc->provenance`.
- `desc->provenance` uses the declared var symbol as the qualified symbol.
- `effective-prov` only replaces that prov when `*annotation-refs*` points to a
  named annotation var.
- Inline `(s/named ...)` does not have an annotation var, so `effective-prov`
  leaves the declared-var prov in place.
- `skeptic.analysis.bridge/form-prov` in
  `skeptic/src/skeptic/analysis/bridge.clj` only recognizes symbol source
  forms. It does not recognize inline `(s/named ...)` forms.

Result: the declared Type for `z` is currently anchored to the declared var
`z`, not to the inline `s/named` annotation site.

### 2. Function input declarations are missing their own source forms

Current code path:

- `skeptic.checking.form/extract-defn-annotation-form` in
  `skeptic/src/skeptic/checking/form.clj` returns only the function output
  annotation form.
- `skeptic.schema.collect/extract-form-for` and `build-form-refs!` in
  `skeptic/src/skeptic/schema/collect.clj` store exactly one extracted form per
  declared var in `ab/*form-refs*`.
- `skeptic.typed-decls/convert-desc` reads that single form and passes it to
  `ab/schema->type`.
- `skeptic.analysis.bridge/function-import-type` threads `source-form` to the
  output recursion only. At lines 157-160 it recurses into input schemas
  without any input source-form context.

Result: declared input Types are currently stamped from the enclosing function
annotation provenance instead of the per-input annotation sites.

### 3. Composite admission constructors reuse the parent prov where the contract needs node-specific prov

The audited constructors that currently reuse the incoming `ctx` prov are:

- `map-import-type` for `MapT`
- `collection-import-type` for `VectorT`, `SetT`, and `SeqT`
- `function-import-type` for `FnMethodT` and `FunT`
- `conditional-import-type` for `ConditionalT`
- `branch-import-type` for union and intersection construction
- the unary wrappers for `MaybeT`, `OptionalKeyT`, and `VarT`
- the `ValueT` constructor path

The important failing example is the declared output annotation in Case B:

```clojure
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})
```

Current behavior:

- the child `RecursiveNamed` symbol is recognized as a named schema
- the enclosing `SetT` is still built with the function-level prov
- the return Type for `produce-inner-set` is therefore rooted at the function
  annotation, not at an unnamed set node with a child ref to `RecursiveNamed`

That is why a bare call currently renders from the function name path instead
of rendering `#{RecursiveNamed}`.

### 4. The actual-side failures in Cases B and C are on the declared-call output path, not on pure literal construction

Current code path:

- `skeptic.analysis.calls/fun-type-call-info` selects the declared method
  output type from the called function type.
- `skeptic.analysis.annotate.invoke/annotate-invoke` uses that selected output
  type as the invoke result, then normalizes it.

For the audited contract cases:

- Case B fails because the selected output type for `produce-inner-set` is
  already wrong on the admission side.
- Case C fails for the same reason, and then the renderer's root suppression
  prevents the nested named node from folding cleanly.
- Case D already passes. The pure literal vector/set inference path in
  `skeptic/src/skeptic/analysis/annotate/data.clj` is not the root cause and
  should not be redesigned broadly.

### 5. The renderer still implements the wrong rule

Current code path in `skeptic/src/skeptic/analysis/bridge/render.clj`:

- `foldable-sources`
- `folded-name`
- `:root?` in `default-render-opts`
- root-only suppression in `render-type-form*`
- the same non-root-only folding in `type->json-data*`

Result: named composites only fold when they are not the root of the rendered
value. That contradicts the contract, which requires folding whenever the
current node itself has a named prov.

## Scope

In scope:

- replacing single-form declaration capture with a structure that preserves
  output forms and per-arg input forms
- fixing bridge admission so every contract-path node carries the correct
  provenance by construction
- preserving the correct named nodes through the actual call-output path used
  by Cases A, B, C, and the real `clj-threals` target
- replacing the renderer semantics so named nodes fold at root and non-root
  alike
- rewriting tests so the contract is executable by exact assertion

Out of scope:

- creating a new universal provenance policy
- a repo-wide provenance redesign unrelated to the contract paths above
- any separate audit, trace, or blocker-discovery phase
- broad changes to pure inferred literal constructors that already satisfy
  Case D

## Implementation Plan

### 1. Replace the one-form-per-var model with a structured declaration-form descriptor

Current problem:

- `build-form-refs!` stores one extracted form per declared var.
- That representation cannot carry both output and input annotation sites for
  function declarations.

Replace the value stored in `ab/*form-refs*` with a descriptor map. Use this
shape:

```clojure
{:kind :def | :defschema | :defn
 :schema-form <form>                ; for def / defschema
 :output-form <form-or-nil>         ; for defn
 :arglists {<dispatch-key> {:input-forms [<form> ...]
                            :count <n-when-varargs>}}}
```

Rules:

- for `s/def` and `s/defschema`, keep `:schema-form` as the exact annotation or
  body form
- for `s/defn`, store `:output-form` plus one `:input-forms` vector per
  arglist
- use the same dispatch keys that the current desc uses in
  `build-annotated-schema-desc!`: fixed arity integers and `:varargs`
- preserve method order and input order exactly so `function-import-type` can
  line up schema inputs with source forms without guesswork

Files to change:

- `skeptic/src/skeptic/checking/form.clj`
- `skeptic/src/skeptic/schema/collect.clj`
- `skeptic/src/skeptic/checking/pipeline.clj`
- `skeptic/src/skeptic/typed_decls.clj`

Concrete changes:

- replace `extract-defn-annotation-form` with a helper that returns a full
  descriptor for output and all input annotation forms
- keep `extract-def-annotation-form` and `extract-defschema-body-form`, but
  wrap them in the same descriptor shape
- make `extract-form-for` return the descriptor instead of a raw form
- keep the `IdentityHashMap` keyed by declared var; only the value shape
  changes
- update `convert-desc` to pass the descriptor through to `desc->type`

Acceptance:

- the declaration for `(s/def z :- (s/named s/Int 'Foo))` still has access to
  the exact inline form
- a multi-arity `s/defn` declaration has access to both output and per-input
  annotation forms when bridge admission runs

### 2. Replace `form-prov` with source-form-aware node provenance helpers in `bridge.clj`

Current problem:

- `form-prov` only handles symbol forms
- composite constructors use the parent prov directly even when the current
  node has its own declaration site

Add two explicit helpers in `skeptic/src/skeptic/analysis/bridge.clj`:

1. a helper that resolves provenance from the current node's source form
2. a helper that builds unnamed composite provenance from the current node's
   source class plus child provs

Required behavior of the source-form helper:

- if the current source form is a symbol naming a declared schema var, keep the
  existing named resolution behavior
- if the current source form is a canonical scalar symbol such as `s/Int`,
  resolve it to the named scalar prov as today
- if the current source form is an inline `(s/named ...)` or
  `(schema.core/named ...)` form, create a `:schema` provenance for that named
  site instead of leaving the enclosing declared-var prov in place
- otherwise return nil so the caller can build an unnamed composite prov

Required behavior of the unnamed composite helper:

- preserve `prov/source`, `:declared-in`, and `:var-meta` from the current
  declaration context
- leave `:qualified-sym` absent
- populate `:refs` from the immediate child provs of the node being built

Do not:

- invent a sentinel such as `:no-name`
- introduce a new provenance source to hide missing identity
- keep reusing the enclosing function or var prov for every composite child

### 3. Fix admission constructors so each node uses the correct local prov

This is the main bridge change. After Step 2 exists, replace direct uses of
the incoming `prov` in the audited constructors with node-local prov
calculation.

#### 3.1 `collection-import-type`

Current behavior:

- **vector** source-forms thread per-child forms via `child-form-fn`; **set**
  and **seq** source-forms do not — `child-form-fn` returned
  `(fn [_i] nil)` for any non-vector source-form, so member recursions for set
  and seq literals never saw the member symbol
- the enclosing `VectorT` or `SetT` is still built with the parent `prov`

Change:

- extend `child-form-fn` to thread per-member forms for set and seq source
  forms as well, using positional indexing after converting the coll to a
  vector; this is required for the member recursion in declared annotations
  like `#{RecursiveNamed}` to receive the symbol `RecursiveNamed` and resolve
  it to `#'RecursiveNamed`
- after `child-results` are built, compute the current node prov from the
  current `source-form` and the child provs
- build both the fixed and homogeneous collection result with that node prov
- when creating the homogeneous union child, use the node prov, not the parent
  prov

This change is what makes the declared return annotation `#{RecursiveNamed}`
become an unnamed set node with a child ref to `RecursiveNamed`, instead of a
set rooted at `produce-inner-set`.

#### 3.2 `map-import-type`

Current behavior:

- keys and values recurse with entry-level forms
- the enclosing `MapT` is still built with the parent `prov`

Change:

- build `MapT` with a node prov derived from the map source form and the key
  and value child provs

This is required for Case A's declared return type
`{:result Threal :cache ThrealCache}` so the map node itself is unnamed and the
two value slots retain the named child nodes.

#### 3.3 `function-import-type`

Current behavior:

- output recursion gets `source-form`
- input recursions get no source form
- `FnMethodT` and `FunT` are both built with the enclosing function prov

Change:

- expect a function declaration descriptor as `source-form`
- pass `:output-form` to the output recursion
- for each arglist, look up the exact `:input-forms` vector from the descriptor
  and pass the matching form into each input recursion
- build each `FnMethodT` with a local unnamed composite prov derived from its
  input and output child provs
- build `FunT` with a local unnamed composite prov derived from its methods
- keep the declared function-level provenance on the declared var entry, not on
  every nested node inside the function schema

This change is required for both input-side contract coverage and for Case B,
because the output annotation `#{RecursiveNamed}` must no longer stamp the
whole output tree with the function name.

#### 3.4 Unary and branch constructors

Change the remaining audited constructors so they stop blindly reusing the
parent prov:

- `MaybeT`
- `OptionalKeyT`
- `VarT`
- `ValueT`
- `ConditionalT`
- union and intersection builders reached through `branch-import-type`

Rule:

- if the node has its own named source form, use that named prov
- otherwise build an unnamed composite prov from the child provs

Keep these unchanged:

- `var-import-type` for real declared schema vars
- placeholder and cycle behavior
- primitive scalar ground-type import

### 4. Leave pure literal inference alone unless tests prove a remaining contract failure

The audited failing cases do not require a broad redesign of inferred literal
construction.

Specifically:

- `skeptic/src/skeptic/analysis/annotate/data.clj`
  `annotate-vector`, `annotate-set`, and `annotate-map` already preserve child
  types well enough for Case D
- `skeptic/src/skeptic/analysis/calls.clj`
  `fun-type-call-info` is only selecting the already-admitted function output
  type
- `skeptic/src/skeptic/analysis/annotate/invoke.clj`
  `annotate-invoke` is only normalizing the selected output type

Therefore:

- do not redesign `annotate.data` as part of this phase
- do not redesign `derive-prov` or `merge-provenances` repo-wide as part of
  this phase
- only touch analyze-side files if exact-string contract tests still fail after
  the bridge and renderer fixes

If one post-bridge failure remains on the actual call-output path, the first
files to inspect and edit are:

- `skeptic/src/skeptic/analysis/calls.clj`
- `skeptic/src/skeptic/analysis/annotate/invoke.clj`
- `skeptic/src/skeptic/analysis/type_ops.clj`

The required behavior on that path is narrow: do not re-anchor a selected
named output node to a caller-level derived prov before rendering.

**Phase 3 execution note:** The post-bridge failure actually observed for
Cases B and C was **not** on the call-output path — it was a missed piece of
§3.1 in this plan. `child-form-fn` only threaded per-child source-forms for
vector literals; set and seq literals lost the member symbol entirely. The
fix lived in `bridge.clj/child-form-fn` (admission side), not in
`calls.clj` / `invoke.clj` / `type_ops.clj`. Those three files remained
unchanged. The "narrow re-anchor" concern for the call-output path never
materialised for this contract.

### 5. Replace the renderer semantics in `render.clj`

Current behavior:

- only non-root named composites fold
- root nodes always expand structurally
- the same weaker rule exists in `type->json-data*`

Replace this with one structural-recursive rule:

- if the current node's prov has a qualified symbol, render that symbol and
  stop
- otherwise recurse structurally into the node's children

Concrete code changes in `skeptic/src/skeptic/analysis/bridge/render.clj`:

- delete `foldable-sources`
- delete `folded-name`
- delete `:root?` from the default render options
- remove all root/non-root branching from `render-type-form*`
- make `type->json-data*` use the same named-node rule

Keep `normalize-for-declared-type` only where it is still needed for
ConditionalT rendering semantics. Do not use root suppression as a workaround
for bad provenance.

### 6. Rewrite tests so the file is a real handoff to implementation, not a diagnostic harness

The next agent should add or update tests in these groups:

1. Declaration-side exact provenance identity for:
   - `(s/def x :- Foo)`
   - `(s/def y :- Bar)`
   - `(s/def z :- (s/named s/Int 'Foo))`
   - `(s/def w :- MyInt)`
   - `(s/def q :- s/Int)`
2. Input-side exact provenance identity for declared function inputs, using a
   fixture that includes both fixed-arity and varargs annotation forms
3. Exact-string actual-side assertions for Cases A, B, C, and D
4. Real smoke validation for `clj-threals.operations/add-with-cache`

Rules:

- exact string equality only for rendered contract cases
- no substring matching
- no surrogate fixture replacing the contract examples
- no retained diagnostic-only audit step in the plan

Files in scope:

- `skeptic/test/skeptic/analysis/bridge_test.clj`
- `skeptic/test/skeptic/analysis/bridge/render_test.clj`
- `skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj`
- `skeptic/test/skeptic/checking/pipeline/named_fold_diagnostic_test.clj`
  only if it is converted from a trace harness into contract assertions

## Project Commands

```bash
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src
cd /Users/demouser/Code/clj-threals && LEIN_FAST_TRAMPOLINE=y lein skeptic -n clj-threals.operations
```

## Completion Gate

The phase is complete only when all of the following are true:

1. `lein test` passes in both subprojects.
2. `clj-kondo --lint` is clean in both subprojects.
3. The five declaration cases above prove the exact required provenance
   identities, including the inline `(s/named ...)` case.
4. Declared function input tests prove the exact required input provenance
   identities.
5. Cases A, B, C, and D each render by exact-string equality to the contract.
6. The real smoke command renders this exact fragment:

   ```clojure
   {:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}
   ```

7. No function name appears anywhere in the relevant actual-side output.

## Constraints Carried Into Implementation

- `prov/unknown` is forbidden anywhere in skeptic.
- Composites without a qualified symbol leave that field absent; they do not
  receive a sentinel.
- Inputs are in scope.
- Output-only carve-outs are forbidden.
- The contract examples are the whole acceptance standard.
- If any contract example fails, the phase is incomplete.
- There is no audit step in this plan. Audits are pre-plan work; the plan must carry forward implementation details.

## Deferred (out of Phase 2b.5 scope)

- **Unqualified rendering of locally-declared named types.** The contract doc
  writes `Threal` / `RecursiveNamed` as shorthand for folded names. The
  renderer currently emits fully-qualified symbols
  (`clj-threals.threals/Threal`,
  `skeptic.test-examples.named-fold-contract-probe/RecursiveNamed`). User
  confirmed this qualifier mismatch is acceptable for Phase 2b.5 and deferred
  to a later phase. The contract tests in
  `skeptic/test/skeptic/checking/pipeline/named_fold_contract_test.clj` assert
  against the qualified form as a result.
