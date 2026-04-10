# Annotate Algorithm

This document describes the annotation algorithm as it exists today, reconstructed from the current source and checked against [blame-for-all.md](./blame-for-all.md). It is written as a behavioral specification for a rewrite, not as a map of the current implementation.

## Algorithm

### Purpose

The annotation pass takes an analyzed Clojure form and produces a typed AST.

Its job is to:

- attach a type to every node
- attach callable metadata to calls and callable values
- refine local variable information across control flow
- preserve enough origin information for later blame reporting and flow-sensitive reasoning
- stay analyzable even when the source contains bad calls or imprecise values

This pass does not perform the runtime machinery described in `Blame for All`.

- It does not introduce quantified types.
- It does not introduce abstract type variables.
- It does not introduce sealed dynamic values.
- It does not implement sealing, unsealing, runtime `nu` bindings, or tamper checks.

Its role relative to `Blame for All` is narrower: it prepares a first-order typed AST that later checking stages consume. The rewrite must preserve that separation.

### Inputs

The pass needs four categories of input:

- a declaration dictionary that describes known vars and functions
- a source form to analyze
- an optional lexical environment for incoming locals
- optional context such as namespace, source file, assumptions, and the current named definition

Before annotation starts:

- native function descriptions are added to the declaration dictionary
- the source form is analyzed into a tools.analyzer JVM AST
- incoming local entries are normalized
- assumptions are stored as an ordered sequence
- the recur-target table starts empty

### Core Traversal

Annotation is a recursive tree walk over analyzer nodes.

For every node:

- annotate children first when the form needs child information
- compute the node type
- copy or synthesize callable metadata when the node represents a callable value or call site
- attach origin information when the form participates in flow-sensitive refinement
- strip legacy derived-schema mirror fields from the final annotated result

Unknown or unsupported forms remain traversable. If no specialized rule applies, children are still annotated and the node falls back to dynamic type.

### Constants, Vars, And Locals

Literal constants are typed from their runtime value.

Var-like references are looked up in the declaration dictionary. If lookup fails, they become dynamic.

Local references read the current lexical environment entry and then apply the active assumptions to that entry. If the local is unknown, it becomes dynamic.

Binding nodes annotate their initializer and copy the initializer's type and callable metadata onto the binding node itself.

### Sequential Forms

A multi-expression block is processed left to right.

For each non-final expression:

- annotate the expression
- derive any guard assumption exposed by that expression
- extend the running context with that guard before annotating later expressions

The block result type is the type of the final expression.

### Local Bindings

Local bindings are processed sequentially.

For each binding:

- annotate the initializer in the environment built so far
- derive the base entry from the annotated initializer, defaulting to dynamic when necessary
- preserve aliasing information when the initializer is another rooted local
- store the initializer itself in the environment entry so later phases can recover the original bound expression
- if the initializer is a function literal, remember that function value on the binding entry for later call-site specialization

Ordinary local bindings also preserve a fresh root origin for the bound name when that is safe. In particular, this root is kept when the binding does not conflict with an earlier branch-test symbol, or when the initializer is an `if` whose test is a nil-check on the same binding name. That rule keeps refinement attached to shadowed locals in common nil-check patterns.

The body is then annotated in the extended environment, and the whole binding form takes the body type.

### Conditionals

A conditional first annotates its test expression.

The test is then converted into branch-local assumptions. Those assumptions may come from:

- type predicates
- nil checks
- conjunctions of test facts
- previously preserved origins on locals

The then and else branches are annotated under separate local environments derived from those assumptions.

Normally the result type is the join of both branch types. There is one narrower special case:

- if the test is a literal truthy value and the else branch is the literal `nil`, the result keeps the then-branch type directly

Conditionals also attach branch-origin information so later reporting can explain where the refinement came from.

### Loops And Recur

Loop bindings are annotated much like ordinary local bindings, but with recur tracking.

After all loop bindings are annotated:

- each binding type becomes an initial recur target type
- an exact `nil` binding is widened immediately to `Maybe Dyn` so the loop target can accept later non-nil updates

The loop body is then annotated once under those recur targets.

After the first body pass, every recur site targeting that loop is inspected. If a target was inferred as exact integer but a recur operand is a JVM numeric helper result typed only as `Number`, that recur target is widened from integer to `Number`. This widening is deliberately narrow: it only handles integer loop counters flowing through JVM numeric helpers and does not perform arbitrary joins.

If widening happened, the body is annotated a second time under the widened recur targets. The purpose of the second pass is to propagate the widened recur-target types back through the recur sites.

A recur expression:

- annotates all operands
- records actual argument types
- records expected argument types when the recur target arity matches
- has bottom type, because it does not produce a local value after transfer

The loop result type is the final body type.

### Functions

Function literals and function methods are annotated from declared parameter information when available.

Parameter processing works as follows:

- if the current named function has a declaration entry for this arity, use its parameter specs
- otherwise default every parameter to dynamic
- temporary per-parameter type overrides may replace declared parameter types for the current annotation pass

Annotated parameter nodes inherit their declared type and also callable metadata if a parameter itself has function type.

The function body is annotated under an environment extended with those parameter entries. Named function methods clear the surrounding definition name before descending into the body so inner functions do not reuse outer declaration lookup by accident.

Every method records:

- annotated parameters
- the body type as both method type and method output type
- the logical arglist
- parameter specs

The whole function value records:

- all annotated methods
- an arglist table indexed by arity
- an output type equal to the join of method output types
- a callable function type assembled from the method signatures

### Call Annotation

There are three call families:

- ordinary invocation
- JVM instance calls
- JVM static calls

#### Ordinary invocation

The callee is sometimes re-annotated before use.

If the callee is:

- an inline single-method unary function literal, or
- a local whose binding entry points to such a function literal

then the call argument is annotated first, and the callee is re-annotated with that argument type temporarily substituted into the single parameter slot. This is a one-argument specialization used to preserve simple flow through local unary function values.

After callee and arguments are ready:

- actual argument types are taken from the annotated arguments
- expected argument types, callable type, and default output type come from callable metadata on the callee
- the default output type is then refined by special collection and map operations
- numeric narrowing may further replace that output for integral arithmetic

The final node records the annotated callee, annotated arguments, actual argument types, expected argument types, callable type, and result type.

#### JVM instance calls

Instance calls are mostly conservative.

Only indexed element lookup on an instance collection is specialized. When that specialization does not apply, the result is dynamic.

#### JVM static calls

Static calls annotate all arguments first.

Then:

- actual argument types come from the annotated arguments
- expected argument types come from native signatures when available, otherwise every argument defaults to dynamic
- the default output type is the native output type when available, otherwise the first argument type when present, otherwise dynamic

Static-call result selection is ordered:

1. apply shared collection and map specializations
2. otherwise apply native numeric narrowing
3. otherwise fall back to dynamic

### Shared Collection And Map Specialization

Some operations are treated the same whether they appear as ordinary invocations or as JVM static calls.

Those shared rules are:

- map lookup
- map merge
- map association
- map dissociation
- map update
- containment checks
- one-argument sequence conversion

Their behavior is:

- map lookup returns the value type at the queried key, joined with the provided default value when a default is present
- map merge merges the map types of all arguments
- map association and dissociation update map shape through literal-key path algebra
- map update specializes only when the key is a literal keyword; otherwise it preserves the caller-supplied default output type instead of erasing to dynamic
- containment checks always return the boolean ground type
- one-argument sequence conversion preserves an existing sequence type, converts vectors to homogeneous sequences, and otherwise returns dynamic

### Invocation-Only Collection Refinement

Ordinary invocation has extra shape-sensitive refinement for collection functions.

These rules cover:

- first, second, last
- nth
- rest, butlast, drop-last
- take, drop, take-while, drop-while
- concat
- into
- chunk-first

The general pattern is:

- preserve precise slot types for vectors when an index is known
- preserve homogeneous element types for homogeneous sequences
- join element types when a heterogeneous collection must collapse to one element type
- preserve vector shape for vector-preserving prefix and suffix operations when the count is known
- otherwise fall back to the default output type supplied by call metadata

Lazy sequence construction also has a special constructor rule:

- when a lazy sequence is created from a zero-argument thunk, try to infer the element type from the thunk body type
- if that body type is still too unknown, scan the body for `cons`-shaped construction and join the pushed element types
- if neither path yields a result, use dynamic element type

### Data Construction And Exceptional Forms

Definitions annotate metadata and initializer separately. The definition type is a var wrapper around the initializer type, defaulting to dynamic when the initializer is absent.

Vectors collect child item types positionally and remember whether all items are identical.

Sets join all member types into one homogeneous set element type, defaulting to dynamic for the empty set.

Maps annotate keys and values independently. Literal keys keep their exact runtime key type when possible; non-literal keys use their annotated key type.

Object construction annotates the class expression and arguments. Lazy sequence construction gets the special sequence rule described above. Other constructions use the runtime class type when known, otherwise dynamic.

Metadata wrappers annotate the metadata and wrapped expression, then copy the wrapped expression's type and callable metadata upward.

Throw expressions annotate the exception and always have bottom type.

Catch clauses bind the caught local to the caught class type when the class is known, otherwise dynamic. The catch clause type is its body type.

Try expressions join the body type with all catch-body types. A finally block is annotated and preserved structurally, but its type does not affect the try expression result.

Quoted forms are typed from the quoted runtime value.

### Case Analysis And Pattern-Like Narrowing

Case analysis is more than a simple branch join.

First, recover the real discriminant expression. The discriminant may be wrapped by analyzer-introduced locals, `do` blocks, or `let` wrappers, and the narrowing logic must look through those wrappers.

Then derive a branch root for narrowing:

- use the rooted local origin when available
- otherwise synthesize a fresh root for a local discriminant

When the discriminant is a keyword lookup on a local map-like value, case analysis can also narrow conditional map types by matching each branch literal against the conditional predicate branches associated with that keyword.

For each explicit case arm:

- collect the arm's literal test values
- derive a positive assumption for those literals
- build branch-local environments from that assumption
- annotate the arm body under the narrowed environment

The default arm gets the complement:

- for ordinary literal matching, exclude all explicit literal values
- for conditional map matching, keep only the conditional branches not matched by any explicit arm

The result type is the join of all explicit-arm types and the default-arm type. The result also keeps an opaque origin tagged with that joined type.

### Numeric Narrowing

Integral arithmetic is refined beyond generic native signatures.

The integral predicate recognizes:

- the built-in integer ground
- JVM boxed and big integer classes used as integer arguments
- exact integer literal values
- refinement types whose base is integral
- intersections whose members are all integral

Using that predicate:

- ordinary `inc`, unary `-`, binary `-`, `+`, and `*` narrow to exact integer when their non-constant arguments are integral in the required pattern
- JVM numeric helpers for increment, decrement, add, multiply, and minus apply the same narrowing rules
- containment checks use the dedicated boolean ground type

When numeric narrowing does not apply, the ordinary call-output logic stays in force.

### Output Contract

The annotated tree produced by this pass must satisfy these invariants:

- every reachable node has a `:type`
- callable nodes carry typed call metadata rather than legacy schema mirror fields
- bad or imprecise code remains annotatable by widening to dynamic instead of failing the pass
- the result stays first-order: no quantified types, abstract type variables, or sealed dynamic values appear anywhere in the annotated output

That last invariant is the main rewrite constraint imposed by `Blame for All`: the annotation phase must not partially implement runtime polymorphic blame semantics. It must stop at first-order typing and call metadata.

## External API Boundary

This section records the public annotate-subtree symbols that are actually called outside the annotation subtree today.

The intended rewrite boundary is now:

- `skeptic.analysis.annotate/annotate-form-loop` for running annotation
- `skeptic.analysis.annotate.api` for all production access to annotated results
- `skeptic.analysis.annotate.test-api` for test-only projections, queries, and synthetic fixtures

Raw annotate data may exist outside `skeptic.analysis.annotate*`.

The rule is about access discipline, not data presence:

- outside `skeptic.analysis.annotate*`, callers may hold or return raw annotated maps and vectors
- outside `skeptic.analysis.annotate*`, callers must not read annotate details directly by keywords or bespoke traversal logic
- outside `skeptic.analysis.annotate*`, callers must use annotate-owned helper functions for node access, tree traversal, projections, and annotate-derived summaries

### Production Boundary

#### `skeptic.analysis.annotate/annotate-form-loop`

Observed arities:

- `[dict form]`
- `[dict form opts]`

Inputs:

- `dict`: declaration dictionary
- `form`: source form to analyze and annotate
- `opts`: optional map. The implementation currently consumes these keys across analysis and annotation:
  - `:locals`
  - `:name`
  - `:ns`
  - `:assumptions`
  - `:source-file`

Output:

- annotated analyzer AST rooted at the analyzed form

Observed external production caller:

- `skeptic.checking.pipeline/analyze-source-exprs`

#### `skeptic.analysis.annotate.api`

This namespace is the production accessor boundary for annotated output. The rewrite must preserve the fact that external production code reaches annotation details only through named helpers here, not through bespoke outside-code interpretation.

Observed externally used helper groups:

- node identity and classification
  - `node-location`
  - `node-info`
  - `node-op`
  - `node-form`
  - `node-type`
  - `node-output-type`
  - `node-fn-type`
  - `node-origin`
  - `node-name`
  - `node-class`
  - `node-method`
  - `node-tag`
  - `node-var`
  - `node-target`
  - `node-keyword`
  - `local-node?`
  - `if-node?`
  - `let-node?`
  - `recur-node?`
  - `call-node?`
  - `invoke-ops`
- tree navigation and node search
  - `annotated-nodes`
  - `find-node`
  - `unwrap-with-meta`
- call and callable metadata
  - `call-fn-node`
  - `call-args`
  - `recur-args`
  - `call-actual-argtypes`
  - `call-expected-argtypes`
  - `typed-call-metadata-only?`
- binding and local-resolution helpers
  - `binding-init`
  - `local-resolution-path`
  - `local-vars-context`
  - `synthetic-binding-node`
- function and definition helpers
  - `node-arglists`
  - `function-methods`
  - `method-body`
  - `def-init-node`
  - `analyzed-def-entry`
  - `method-result-type`
  - `resolved-def-output-type`
- branch and refinement helpers
  - `node-test`
  - `node-body`
  - `node-init`
  - `node-bindings`
  - `then-node`
  - `else-node`
  - `branch-origin-kind`
  - `branch-test-assumption`

Observed external production consumers:

- `skeptic.analysis.calls`
- `skeptic.analysis.origin`
- `skeptic.checking.ast`
- `skeptic.checking.form`
- `skeptic.checking.pipeline`

### Test-Only Boundary

`skeptic.analysis.annotate.test-api` owns the test-facing helpers that were previously implemented in non-annotate test code. Tests outside annotate now rely on this namespace instead of defining their own annotate-shape projections, searches, or synthetic node builders.

Observed externally used test helpers:

- annotation wrapper
  - `annotate-form-loop`
- stable projection and query helpers
  - `stable-keys`
  - `arglist-types`
  - `project-ast`
  - `projected-nodes`
  - `find-projected-node`
  - `child-projection`
  - `ast-by-name`
  - `node-by-form`
- synthetic test fixtures for annotate-shaped nodes
  - `test-local-node`
  - `test-fn-node`
  - `test-typed-node`
  - `test-const-node`
  - `test-invoke-node`
  - `test-invoke-form-node`
  - `test-with-meta-node`
  - `test-static-call-node`

Observed external test consumers:

- `skeptic.analysis-test`
- `skeptic.analysis.calls-test`
- `skeptic.checking.ast-test`

### Internal-Only Annotate Namespaces Still Directly Tested Inside The Subtree

These are still direct test targets, but only from tests inside the annotate subtree itself:

- `skeptic.analysis.annotate.coll`
- `skeptic.analysis.annotate.numeric`

## Behavioral Test Record

This section records the behavioral coverage that currently exists for the annotation subtree, excluding pure unit tests for current implementation helpers.

Excluded from this record:

- `skeptic/test/skeptic/analysis/annotate/coll_test.clj`
- `skeptic/test/skeptic/analysis/annotate/numeric_test.clj`
- unit-only constructor and predicate tests in `skeptic/test/skeptic/analysis/origin_test.clj` and `skeptic/test/skeptic/analysis/calls_test.clj`

### Behavioral Tests Inside `test/skeptic/analysis/annotate`

#### `integration_test.clj`

Recorded behavior:

- case narrowing through a destructured keyword local reaches the downstream call argument type expected by a typed callee
- empty and bound local-binding forms produce the expected result type
- function literals inherit declared parameter and output information when available
- definitions and function definitions wrap initializer types correctly and preserve typed function outputs
- multi-expression blocks take the final expression type
- `try`, `catch`, `throw`, and `finally` preserve the intended structural shape and result type
- nested bad calls remain annotatable and visible in the tree
- `fn*` with `:once` metadata preserves the metadata wrapper shape
- a let-bound non-callable local can still be invoked syntactically without breaking annotation of the enclosing form
- macroexpansions such as `doto`, `cond->`, and thread-first `doto` remain annotatable after expansion
- ordinary annotation remains first-order and introduces no quantified, abstract-variable, or sealed-dynamic types
- annotated function nodes expose typed metadata fields and omit the old schema-mirror fields

#### `typed_flow_test.clj`

Recorded behavior:

- type information flows through local bindings
- anonymous, named, binary, and multi-arity functions compute parameter specs, arglists, and output types correctly
- definitions, function definitions, and `do` blocks preserve typed outputs
- `try`, `catch`, `throw`, and `finally` remain analyzable in typed scenarios
- nested bad calls still annotate without collapsing the enclosing tree
- `:once` metadata remains visible on wrapped function literals
- calls through let-bound local values stay conservative
- expanded `doto`, `cond->`, and threaded forms preserve the expected typed result
- `throw` and `try` maintain the expected structural shape in the annotated tree

#### `structural_test.clj`

Recorded behavior:

- structural shapes for `throw`, `try`, `catch`, `finally`, `let`, `if`, `case`, `fn`, `def`, literal collections, `loop`, and `recur`
- invalid literal-as-call forms fail in analysis rather than being mis-annotated
- non-literal vector, map, and set construction remains explicit in the analyzer tree
- loop bodies and recur sites carry the expected result and bottom types
- loop-produced vector and map literals cast to declared schemas
- `for` expands to loop-and-recur structure and produces a homogeneous sequence of body values
- native arithmetic through core forms and lowered JVM static calls gets the expected numeric result type
- native function dictionary entries for `+` expose the expected arity layout
- expected argument types for predicates such as `even?` are attached to call nodes
- constrained numeric locals narrowed through JVM numeric helpers keep refined integer-like results
- `concat` joins element types across all inputs rather than keeping only the first collection's shape
- tuple-style element extraction preserves slot precision strongly enough to reject the wrong target type

#### `analyse_detail_test.clj`

Recorded behavior:

- detailed tree shapes for empty, simple, and nested local bindings
- detailed tree shapes for literal and local-tested conditionals
- detailed function annotation behavior for anonymous, named, binary, multi-expression, and multi-arity functions
- detailed definition and function-definition shapes, including raw source-form preservation
- `do` shape and final-expression placement
- literal vector, set, and map typing, including nested literals
- rejection of invalid literal-as-call forms
- map-literal key typing at the raw-value boundary for literal keys such as UUIDs and regexes
- non-literal map keys use inferred semantic key types instead of exact runtime literal typing

### Additional Non-Unit Tests Outside The Annotate Subtree That Exercise Annotation

#### `origin_test.clj`

Recorded behavior:

- `or` and `if` expansions produce branch-local refinement and joined result types
- nil-bearing joins canonicalize to maybe-typed results in analyzed source definitions
- conjunction-style tests contribute multiple assumptions
- shadowed local bindings preserve root-origin information strongly enough for later unary numeric operations to see refined argument types

#### `calls_test.clj`

Recorded behavior:

- analyzer roots distinguish ordinary invocation from JVM static calls
- call nodes carry typed call metadata fields without legacy schema mirror fields
- known and unknown calls compute actual argument types, expected argument types, callable type, and output type correctly
- typed callable entries supplied through local environments are honored by call annotation
- static map lookup returns precise required, optional, and defaulted field types
- static map merge returns merged map types
- rebuilt maps preserve semantic map typing after field extraction
- a resolved static map lookup can feed its refined result type into a parent call's argument typing
- invocation through a local function value preserves both the local call's result type and its callable metadata
