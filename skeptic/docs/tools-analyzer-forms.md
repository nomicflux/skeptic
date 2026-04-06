# `tools.analyzer` AST Reference for `skeptic`

This document is the working reference for the `tools.analyzer` rewrite in `skeptic`.

Scope:

- Host: `clojure.tools.analyzer.jvm`
- Focus: the structure of the data returned from analyzing Clojure forms
- Audience: code in `skeptic.analysis` and tests that assert on analyzer ASTs

Official references:

- `clojure.tools.analyzer` API: <https://clojure.github.io/tools.analyzer/>
- `clojure.tools.analyzer` AST quickref: <https://clojure.github.io/tools.analyzer/spec/quickref.html>
- `clojure.tools.analyzer.jvm` AST quickref: <https://clojure.github.io/tools.analyzer.jvm/spec/quickref.html>

## 1. Core AST contract

`clojure.tools.analyzer/analyze` takes a form plus an env map and returns an AST node.

The official API guarantees that every AST node has:

- `:op`
- `:form`
- `:env`

If a node has child nodes, it is also guaranteed to have:

- `:children`

Important consequence:

- Only the root node (`:top-level true`) and nodes reachable through `:children` are guaranteed to have the normal AST node shape.
- If a map looks node-like but is not reachable via `:children`, the docs do not guarantee it is a real AST node.

The analyzer env passed to `analyze` is documented as containing at least:

- `:locals` - map from binding symbol to the AST of the binding value
- `:context` - one of `:ctx/expr`, `:ctx/return`, `:ctx/statement`
- `:ns` - current namespace symbol

Common node fields from the JVM quickref:

- `:op` - node kind
- `:form` - form the node came from
- `:env` - analyzer environment for this node
- `:children` - ordered child keys
- `:raw-forms` - intermediate macroexpansion forms, from original form to current `:form`
- `:top-level` - `true` on the root node
- `:tag` - required tag of the expression
- `:o-tag` - tag inferred from the node's children
- `:ignore-tag` - marks a statement-like node
- `:loops` - possible enclosing loop ids reachable via recur

## 2. AST traversal helpers

From `clojure.tools.analyzer.ast`:

- `children`
  - returns child expressions in evaluation order
  - flattens vector children so the result is only nodes
- `children*`
  - returns `[child-key child-value]` pairs
  - preserves the structure implied by `:children`
  - child values may be a single node or a vector of nodes
- `nodes`
  - returns all nodes in depth-first pre-order

Practical rule for `skeptic`:

- keep the analyzer AST as the canonical representation
- derive test projections from the AST
- do not replace the shared representation with a flattened or index-based queue model

## 3. What we observed locally in `skeptic`

These are not official guarantees. They are local observations from the rewritten tests and direct analyzer inspection.

### Literal collections often collapse to `:const`

Examples:

- `[1 2 :a "hello"]` typically analyzes as `{:op :const, :type :vector, ...}`
- `#{1 2 :a "hello"}` typically analyzes as `{:op :const, :type :set, ...}`
- `{:a 1 :b 2}` typically analyzes as `{:op :const, :type :map, ...}`

### Non-literal collections stay as explicit collection ops

Examples:

- `(let [x 1] [x 2])` produces a `:vector` body node
- `(let [x 1] {:a x})` produces a `:map` body node
- `(let [x 1] #{x 2})` produces a `:set` body node

### `defn` is not a first-class op

`defn` macroexpands. The analyzed root is a `:def`, and the value under `:init` is typically a `:with-meta` wrapping a `:fn`.

### Macro forms usually surface expanded structure in `:form`

Examples from the rewrite:

- `(or y 1)` becomes a `:let` with an inner `:if`
- `(doto ...)` becomes a `:let`
- `(cond-> ...)` becomes a `:let` whose body contains an `:if`

` :raw-forms` is the place to recover the expansion trail when that provenance matters.

### Some syntactic no-ops disappear

Example:

- `(let [] (+ 1 2))` can be trimmed to a direct call shape instead of remaining a `:let`

### Invalid forms fail before a useful AST exists

Example:

- `(1 2 :a "hello")` is rejected by the analyzer because a literal number is not invokable

That means some legacy bespoke-analysis cases should now be asserted as analyzer failures instead of expecting a synthetic node shape.

## 4. Complete `clojure.tools.analyzer.jvm` op catalog

The list below is the complete JVM quickref op set. Each entry includes:

- the node shape
- the ordered child keys
- the fields that matter most to consumers like `skeptic`
- whether we currently see it in this rewrite or include it for completeness

### Binding and local reference ops

#### `:binding`

- Shape: binding symbol
- Children: `[]` or `[:init]`
- Important fields:
  - `:name` - uniquified binding symbol
  - `:local` - one of `:arg`, `:catch`, `:fn`, `:let`, `:letfn`, `:loop`, `:field`, `:this`
  - `:arg-id`
  - `:variadic?`
  - `:init`
  - `:atom`
- `skeptic` status: observed

#### `:local`

- Shape: local symbol reference
- Children: none
- Important fields:
  - `:name` - uniquified symbol
  - `:form` - original local symbol
  - `:local`
  - `:arg-id`
  - `:variadic?`
  - `:assignable?`
  - `:atom`
- `skeptic` status: observed

#### `:var`

- Shape: symbol resolving to a Var
- Children: none
- Important fields:
  - `:var`
  - `:assignable?` for dynamic vars
- `skeptic` status: observed

#### `:the-var`

- Shape: `(var var-name)`
- Children: none
- Important fields:
  - `:var`
- `skeptic` status: completeness only in current tests, but should be handled conservatively

### Definition and function ops

#### `:def`

- Shape: `(def name docstring? init?)`
- Children:
  - `[]`
  - `[:init]`
  - `[:meta]`
  - `[:meta :init]`
- Important fields:
  - `:name`
  - `:var`
  - `:meta`
  - `:init`
  - `:doc`
- `skeptic` status: observed

#### `:fn`

- Shape: `(fn* name? [arg*] body*)` or `(fn* name? method*)`
- Children: `[:methods]` or `[:local :methods]`
- Important fields:
  - `:variadic?`
  - `:max-fixed-arity`
  - `:local`
  - `:methods`
  - `:once`
- `skeptic` status: observed

#### `:fn-method`

- Shape: `([arg*] body*)`
- Children: `[:params :body]`
- Important fields:
  - `:loop-id`
  - `:variadic?`
  - `:params`
  - `:fixed-arity`
  - `:body`
- `skeptic` status: observed

#### `:let`

- Shape: `(let* [binding*] body*)`
- Children: `[:bindings :body]`
- Important fields:
  - `:bindings`
  - `:body`
- `skeptic` status: observed

#### `:letfn`

- Shape: `(letfn* [binding*] body*)`
- Children: `[:bindings :body]`
- Important fields:
  - `:bindings`
  - `:body`
- `skeptic` status: completeness only in current tests

#### `:loop`

- Shape: `(loop* [binding*] body*)`
- Children: `[:bindings :body]`
- Important fields:
  - `:bindings`
  - `:body`
  - `:loop-id`
- `skeptic` status: completeness only in current tests

#### `:recur`

- Shape: `(recur expr*)`
- Children: `[:exprs]`
- Important fields:
  - `:exprs`
  - `:loop-id`
- `skeptic` status: completeness only in current tests

### Control-flow and body ops

#### `:do`

- Shape: `(do statement* ret)`
- Children: `[:statements :ret]`
- Important fields:
  - `:statements`
  - `:ret`
  - `:body?` when synthetic
- `skeptic` status: observed

#### `:if`

- Shape: `(if test then else?)`
- Children: `[:test :then :else]`
- Important fields:
  - `:test`
  - `:then`
  - `:else`
- `skeptic` status: observed

#### `:try`

- Shape: `(try body* catch* finally?)`
- Children:
  - `[:body :catches]`
  - `[:body :catches :finally]`
- Important fields:
  - `:body` - synthetic `:do`
  - `:catches`
  - `:finally` - synthetic `:do`
- `skeptic` status: observed

#### `:catch`

- Shape: `(catch class local body*)`
- Children: `[:class :local :body]`
- Important fields:
  - `:class` - `:const` class node
  - `:local` - caught exception binding
  - `:body` - synthetic `:do`
- `skeptic` status: observed

#### `:throw`

- Shape: `(throw exception)`
- Children: `[:exception]`
- Important fields:
  - `:exception`
- `skeptic` status: observed

#### `:case`

- Shape: `(case* expr shift mask default case-map switch-type test-type skip-check?)`
- Children: `[:test :tests :thens :default]`
- Important fields:
  - `:test`
  - `:tests`
  - `:thens`
  - `:default`
  - `:shift`
  - `:mask`
  - `:low`
  - `:high`
  - `:switch-type` - `:sparse` or `:compact`
  - `:test-type` - `:int`, `:hash-equiv`, or `:hash-identity`
  - `:skip-check?`
- `skeptic` status: completeness only in current tests

#### `:case-test`

- Shape: case dispatch test value
- Children: `[:test]`
- Important fields:
  - `:test` - `:const`
  - `:hash`
- `skeptic` status: completeness only in current tests

#### `:case-then`

- Shape: case branch body
- Children: `[:then]`
- Important fields:
  - `:then`
  - `:hash`
- `skeptic` status: completeness only in current tests

### Invocation and callsite ops

#### `:invoke`

- Shape: `(f arg*)`
- Children: `[:fn :args]`
- Important fields:
  - `:fn`
  - `:args`
  - `:meta`
- `skeptic` status: observed

#### `:prim-invoke`

- Shape: primitive-optimizable invoke
- Children: `[:fn :args]`
- Important fields:
  - `:fn`
  - `:args`
  - `:prim-interface`
  - `:meta`
- `skeptic` status: completeness only in current tests

#### `:protocol-invoke`

- Shape: protocol function invocation
- Children: `[:protocol-fn :target :args]`
- Important fields:
  - `:protocol-fn`
  - `:target`
  - `:args`
- `skeptic` status: completeness only in current tests

#### `:keyword-invoke`

- Shape: keyword callsite such as `(:k m)`
- Children: `[:keyword :target]`
- Important fields:
  - `:keyword`
  - `:target`
- `skeptic` status: completeness only in current tests

#### `:static-call`

- Shape: `(Class/method arg*)`
- Children: `[:args]`
- Important fields:
  - `:class`
  - `:method`
  - `:args`
  - `:validated?`
- `skeptic` status: observed

#### `:instance-call`

- Shape: `(.method instance arg*)`
- Children: `[:instance :args]`
- Important fields:
  - `:method`
  - `:instance`
  - `:args`
  - `:validated?`
  - `:class` when validated
- `skeptic` status: completeness only in current tests

#### `:new`

- Shape: `(new Class arg*)`
- Children: `[:class :args]`
- Important fields:
  - `:class` - `:const` class node
  - `:args`
  - `:validated?`
- `skeptic` status: observed

### Host interop and field ops

#### `:host-interop`

- Shape: unresolved no-arg instance call or unresolved instance field access
- Children: `[:target]`
- Important fields:
  - `:target`
  - `:m-or-f`
  - `:assignable?`
- `skeptic` status: completeness only in current tests

#### `:instance-field`

- Shape: `(.-field instance)`
- Children: `[:instance]`
- Important fields:
  - `:field`
  - `:instance`
  - `:assignable?`
  - `:class`
- `skeptic` status: completeness only in current tests

#### `:static-field`

- Shape: `Class/field`
- Children: none
- Important fields:
  - `:class`
  - `:field`
  - `:assignable?`
- `skeptic` status: completeness only in current tests

#### `:instance?`

- Shape: `(clojure.core/instance? Class x)`
- Children: `[:target]`
- Important fields:
  - `:class`
  - `:target`
- `skeptic` status: completeness only in current tests

### Collection, constant, quote, and metadata ops

#### `:const`

- Shape: constant literal or quoted collection literal
- Children: `[]` or `[:meta]`
- Important fields:
  - `:literal?` - always true
  - `:type` - one of `:nil`, `:bool`, `:keyword`, `:symbol`, `:string`, `:number`, `:type`, `:record`, `:map`, `:vector`, `:set`, `:seq`, `:char`, `:regex`, `:class`, `:var`, `:unknown`
  - `:val`
  - `:meta`
- `skeptic` status: observed

#### `:vector`

- Shape: non-constant vector literal or vector literal with metadata
- Children: `[:items]`
- Important fields:
  - `:items`
- `skeptic` status: observed

#### `:map`

- Shape: non-constant map literal or map literal with metadata
- Children: `[:keys :vals]`
- Important fields:
  - `:keys`
  - `:vals`
- `skeptic` status: observed

#### `:set`

- Shape: non-constant set literal or set literal with metadata
- Children: `[:items]`
- Important fields:
  - `:items`
- `skeptic` status: observed

#### `:quote`

- Shape: `(quote expr)`
- Children: `[:expr]`
- Important fields:
  - `:expr` - always a `:const`
  - `:literal?` - true
- `skeptic` status: observed indirectly; explicit `quote` is not currently a major test target

#### `:with-meta`

- Shape: metadata attached to non-quoted collection or fn/reify expression
- Children: `[:meta :expr]`
- Important fields:
  - `:meta`
  - `:expr`
  - `:expr` op is one of `:vector`, `:map`, `:set`, `:fn`, `:reify`
- `skeptic` status: observed

### Type generation and method ops

#### `:deftype`

- Shape: `(deftype* name class.name [arg*] :implements [interface*] method*)`
- Children: `[:fields :methods]`
- Important fields:
  - `:interfaces`
  - `:name`
  - `:class-name`
  - `:fields`
  - `:methods`
- `skeptic` status: completeness only in current tests

#### `:reify`

- Shape: `(reify* [interface*] method*)`
- Children: `[:methods]`
- Important fields:
  - `:interfaces`
  - `:class-name`
  - `:methods`
- `skeptic` status: completeness only in current tests

#### `:method`

- Shape: `(method [this arg*] body*)`
- Children: `[:this :params :body]`
- Important fields:
  - `:bridges`
  - `:interface`
  - `:this`
  - `:loop-id`
  - `:name`
  - `:params`
  - `:fixed-arity`
  - `:body`
- `skeptic` status: completeness only in current tests

### Mutation, synchronization, and import ops

#### `:set!`

- Shape: `(set! target val)`
- Children: `[:target :val]`
- Important fields:
  - `:target`
  - `:val`
- `skeptic` status: completeness only in current tests

#### `:monitor-enter`

- Shape: `(monitor-enter target)`
- Children: `[:target]`
- Important fields:
  - `:target`
- `skeptic` status: completeness only in current tests

#### `:monitor-exit`

- Shape: `(monitor-exit target)`
- Children: `[:target]`
- Important fields:
  - `:target`
- `skeptic` status: completeness only in current tests

#### `:import`

- Shape: `(clojure.core/import* "qualified.class")`
- Children: none
- Important fields:
  - `:class` - string name of imported class
- `skeptic` status: completeness only in current tests

## 5. Canonical guidance for `skeptic.analysis`

This is the design guidance that fell out of the rewrite.

### Keep the analyzer AST, then annotate it

Use `ana.jvm/analyze` on the original form and add `skeptic` fields onto that AST. Do not replace it with a synthetic queue structure.

Useful added fields for `skeptic` include:

- `:type`
- `:output-type`
- `:arglists`
- `:arglist`
- `:actual-argtypes`
- `:expected-argtypes`

### Trust `:children`, not ad hoc nested maps

When traversing:

- use `:children` order as the semantic child order
- use `children*`-style behavior if you need both child keys and child values
- remember vectors inside child slots are still part of the official tree

### Assert stable projections in tests

Stable:

- `:op`
- `:form`
- `:children`
- `:body?`
- `:class`
- `:method`
- `:literal?`
- `:type`
- selected binding/local metadata
- `:raw-forms` when macro provenance is the point

Volatile or too noisy for most tests:

- `:env`
- `:tag`
- `:o-tag`
- `:atom`
- uniquified binding names unless the uniqueness itself matters

## 6. Shapes that matter most in this rewrite

The current implementation and tests rely most heavily on:

- `:const`
- `:vector`
- `:map`
- `:set`
- `:local`
- `:var`
- `:the-var`
- `:invoke`
- `:static-call`
- `:if`
- `:let`
- `:do`
- `:fn`
- `:fn-method`
- `:def`
- `:with-meta`
- `:new`
- `:throw`
- `:catch`
- `:try`

That is enough for the current `skeptic.analysis` pass, but the full op list above is the correct reference boundary.

## 7. Example patterns from the rewrite

### Literal vector

Input:

```clojure
[1 2]
```

Typical analyzed shape:

- root `:op` is `:const`
- root `:type` is `:vector`

### Non-literal vector

Input:

```clojure
(let [x 1] [x 2])
```

Typical analyzed shape:

- root `:op` is `:let`
- body `:op` is `:vector`

### `defn`

Input:

```clojure
(defn f [x] (+ 1 x))
```

Typical analyzed shape:

- root `:op` is `:def`
- `:init` is a `:with-meta`
- `:expr` under that metadata node is a `:fn`
- `:raw-forms` keeps the original `defn`

### `or`

Input:

```clojure
(or y 1)
```

Typical analyzed shape:

- expands to a `:let`
- inner body is an `:if`
- `:raw-forms` preserves the expansion chain

### `doto`

Input:

```clojure
(doto (make-component {:a 1}) (start {:opt1 true}))
```

Typical analyzed shape:

- expands to a `:let`
- body is a synthetic `:do`
- original macro form is available in `:raw-forms`

## 8. Bottom line

For `skeptic`, the right abstraction boundary is:

1. analyze the original form with `tools.analyzer.jvm`
2. treat the analyzer AST as canonical shared state
3. attach typed annotation data directly to those nodes
4. assert on stable projections of that AST in tests

That removes most of the old bespoke macroexpansion bookkeeping while preserving a richer, more official representation of the program shape.
