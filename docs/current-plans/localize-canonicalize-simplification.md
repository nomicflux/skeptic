# Localize/Canonicalize Simplification Plan

## Goal

Reduce the memory and CPU cost currently attributed to
`skeptic.analysis.bridge.localize/localize-value` by subtracting unnecessary
work from the schema intake and canonicalization path.

This is a simplification plan, not a memoization plan and not a new abstraction
plan. The first implementation pass should remove unneeded traversal and
reconstruction before adding caches, new layers, or new public APIs.

## Current Hot Path

Relevant files:

- `skeptic/src/skeptic/analysis/bridge/localize.clj`
- `skeptic/src/skeptic/analysis/bridge/canonicalize.clj`
- `skeptic/src/skeptic/schema/collect.clj`
- `skeptic/src/skeptic/typed_decls.clj`
- `skeptic/src/skeptic/analysis/bridge.clj`

The production callers of `localize-value` are in
`skeptic.analysis.bridge.canonicalize`:

- `raw-schema-domain-value` localizes its input before rejecting semantic type
  values.
- `schema?` localizes its input, then recursively calls `schema?` on children.
- `canonicalize-schema*` localizes its input at the top of every recursive
  call.

Declaration collection then amplifies the cost:

- `skeptic.schema.collect/build-annotated-schema-desc!` calls
  `abc/canonicalize-schema` on the root schema.
- It then builds `:schema`, `:output`, and arglist schema slots from that
  canonicalized root.
- It then calls `abc/canonicalize-entry`, which canonicalizes those slots again.
- It then calls `assert-admitted-schema-slots!`, which runs `ab/admit-schema`
  over the explicit slots.
- Later, `skeptic.typed-decls/desc->type` calls `ab/schema->type`, which calls
  `admit-schema` again before importing the type.

The main performance problem is not one expensive operation. It is repeated
full-tree traversal of the same schema/type structures.

## Logic That Is Actively Harmful

### 1. `localize-value` recursively localizes semantic types

`localize.clj` currently has `localize-semantic-type`, which reconstructs
internal checker type records:

- `DynT`
- `BottomT`
- `GroundT`
- `NumericDynT`
- `RefinementT`
- `AdapterLeafT`
- `OptionalKeyT`
- `FnMethodT`
- `FunT`
- `MaybeT`
- `UnionT`
- `IntersectionT`
- `MapT`
- `VectorT`
- `SetT`
- `SeqT`
- `VarT`
- `PlaceholderT`
- `ValueT`
- `TypeVarT`
- `ForallT`
- `SealedDynT`
- `InfCycleT`

That is not schema intake. Semantic types are already the typechecker internal
representation. Recursing through them during schema localization is harmful
because it:

- treats internal type values as if they were raw schema values;
- rebuilds already-built type graphs;
- destroys structural sharing;
- walks arbitrary payload fields such as `:adapter-data` and `ValueT :value`;
- can realize lazy sequences inside type or diagnostic payloads;
- hides schema/type boundary bugs by normalizing semantic type structures before
  the boundary rejects them.

The schema boundary should either preserve semantic types unchanged when they
are not schema input, or reject them when they appear where a schema-domain
value is required. It should not recursively rebuild them.

### 2. `canonicalize-schema*` localizes on every recursive call

`canonicalize-schema*` currently starts by calling:

```clojure
(let [schema (abl/localize-value schema)]
  ...)
```

Because `canonicalize-schema*` recursively calls itself on children, nested
schema structures are repeatedly localized. A map containing nested maps,
vectors, sets, joins, valued schemas, or variable schemas can be walked once by
the parent call and then walked again by each recursive child call.

This is unnecessary. Localization belongs at the boundary where raw schema
values enter canonicalization. Once a raw collection has been localized, plain
child values extracted from that localized collection should not be localized
again.

### 3. `canonicalize-entry` repeats work in declaration collection

`build-annotated-schema-desc!` canonicalizes the root declaration schema and
then calls `canonicalize-entry` over the desc built from that canonical root.
For normal declarations, this re-canonicalizes schema slots that were just
derived from the canonicalized schema.

This is avoidable duplicate work during namespace declaration collection.

### 4. Display-name construction may force extra schema explanation work

In `build-annotated-schema-desc!`, the fallback `:name` for class/set/vector
schemas currently uses:

```clojure
(some-> schema abc/schema-display-form pr-str)
```

`schema-display-form` calls `schema-explain`, and `schema-explain` can call
`schema?`, which localizes recursively. If this name is only a label, it should
not force another recursive validation/localization path in the hot declaration
collection path.

## Implementation Plan

### Phase 1: Make `localize-value` schema-boundary-only

Edit `skeptic/src/skeptic/analysis/bridge/localize.clj`.

Remove the semantic-type reconstruction path from `localize-value*`.

Specifically:

1. Delete `localize-semantic-type` unless tests prove another non-schema caller
   still needs it.
2. Remove this branch from `localize-value*`:

   ```clojure
   (at/semantic-type-value? value)
   (localize-semantic-type value seen-vars)
   ```

3. Replace it with a no-op preservation branch if needed for clarity:

   ```clojure
   (at/semantic-type-value? value)
   value
   ```

4. Keep these behaviors:

   - `nil` returns `nil`.
   - unbound Vars localize to placeholder schema refs through the existing
     unbound-var path.
   - bound Vars localize to their root value with recursion protection.
   - custom schema maps localize their schema/value children.
   - raw vectors, sets, maps, and seqs localize their elements.
   - records are not treated as raw maps.

Expected result:

- Semantic type graphs are no longer copied by localization.
- `adapter-data` and `ValueT :value` are no longer walked by localization.
- Schema/type boundary violations remain visible to the caller instead of being
  normalized through a type traversal.

Tests to add or update:

- In `skeptic/test/skeptic/analysis/bridge_test.clj`, add a regression proving
  `localize-value` preserves semantic type identity or equality without
  rebuilding it.
- Include a semantic type whose payload would be expensive or unsafe to walk,
  such as an `AdapterLeafT` or `ValueT` containing a lazy sequence in
  `:adapter-data` or `:value`.
- The assertion should prove the lazy payload is not realized by
  `abl/localize-value`.

Example test shape:

```clojure
(deftest localize-value-does-not-walk-semantic-type-payloads-test
  (let [realized? (atom false)
        payload (map (fn [x] (reset! realized? true) x) [1])
        t (at/->AdapterLeafT tp :schema 'Display (constantly true) payload)]
    (is (identical? t (abl/localize-value t)))
    (is (false? @realized?))))
```

If `identical?` is too strict for existing expectations, use equality plus the
`realized?` assertion. Prefer `identical?` because the intended simplification
is preservation, not reconstruction.

### Phase 2: Localize once at `canonicalize-schema`

Edit `skeptic/src/skeptic/analysis/bridge/canonicalize.clj`.

The target shape is:

- `canonicalize-schema` owns the initial `raw-schema-domain-value` call.
- `canonicalize-schema*` assumes the value it receives is already localized for
  raw Clojure container children.
- Recursive calls inside `canonicalize-schema*` do not call `localize-value`
  again for values pulled from already-localized raw maps, vectors, sets, joins,
  valued schemas, or variable schemas.

Minimal implementation:

1. Remove the top-level localization from `canonicalize-schema*`.

   Current:

   ```clojure
   (defn canonicalize-schema*
     [schema {:keys [constrained->base?]}]
     (let [schema (abl/localize-value schema)]
       ...))
   ```

   Target:

   ```clojure
   (defn canonicalize-schema*
     [schema {:keys [constrained->base?]}]
     ...)
   ```

2. Keep `canonicalize-schema` as the public boundary:

   ```clojure
   (defn canonicalize-schema
     [schema]
     (let [v (raw-schema-domain-value schema)]
       (canonicalize-schema* (cond-> v (sb/named? v) sb/de-named)
                             {:constrained->base? false})))
   ```

3. Audit recursive branches in `canonicalize-schema*`.

   For children pulled from plain raw collections or custom schema maps, call
   `canonicalize-schema*` directly.

   For children extracted from Plumatic schema records, decide explicitly
   whether they can contain Vars that still need localization. Examples:

   - `Maybe`, `Constrained`, `Either`, `CondPre`, `Both`, `ConditionalSchema`,
     `FnSchema`, and `One` are record/object wrappers that `localize-value`
     does not recursively inspect as raw maps.
   - If a child is extracted from one of these wrappers and may contain a Var,
     localize that child at the point of extraction before recursing.

   Do not solve this by restoring unconditional localization at the top of
   `canonicalize-schema*`.

Tests to add or update:

- Existing recursive schema tests in `bridge_test.clj` must still pass:

  - `raw-schema-var-normalization-test`
  - `recursive-collections-reduce-by-construction-test`

- Add a test where a Plumatic wrapper contains a Var child, for example:

  ```clojure
  (is (= (s/maybe s/Int)
         (abc/canonicalize-schema (s/maybe #'BoundSchemaRef))))
  ```

  Adjust expected shape to match the repo's canonical representation.

### Phase 3: Remove the second declaration canonicalization pass

Edit `skeptic/src/skeptic/schema/collect.clj`.

Current `build-annotated-schema-desc!` canonicalizes the root schema, builds a
desc, then pipes the desc through `abc/canonicalize-entry`.

Change the desc builder so it constructs canonical slots directly and does not
run `canonicalize-entry` over the whole desc.

Implementation details:

1. Keep the initial root canonicalization:

   ```clojure
   (let [schema (abc/canonicalize-schema schema)
         desc ...]
     ...)
   ```

2. Remove the trailing `abc/canonicalize-entry` from the `->>` pipeline.

3. Make sure each slot is already canonical by construction:

   - `:schema` should be the canonical root schema.
   - `:output` should be either `output-schema` extracted from the canonical
     fn schema, or the canonical root schema.
   - arglist `:schema` values should be the `input-schemas` extracted from the
     canonical fn schema.
   - vararg normalized input schemas must not be re-canonicalized unless the
     code constructs a fresh non-canonical wrapper.

4. Keep `assert-admitted-schema-slots!` in place during this phase. Removing
   duplicate admission is a separate decision after this simpler change is
   verified.

Tests to add or update:

- Declaration collection tests should still pass.
- Add a focused test proving `collect/collect-schemas` returns the same desc
  for representative declaration shapes:

  - scalar/class schema;
  - vector schema;
  - set schema;
  - `s/=>` function schema with output and input schemas;
  - varargs schema if existing tests cover it.

### Phase 4: Avoid display-form work in declaration collection if possible

Edit `skeptic/src/skeptic/schema/collect.clj`.

Review this branch in `build-annotated-schema-desc!`:

```clojure
(if (or (class? schema) (set? schema) (vector? schema))
  {:name (or (some-> schema abc/schema-display-form pr-str) (str ns "/" name))
   :schema schema
   :output schema
   :arglists {}}
  ...)
```

If the display name is not user-visible in a way that requires schema display
fidelity, simplify it to:

```clojure
:name (str ns "/" name)
```

Do not make this change blindly if tests assert the existing display name.
If tests show this is user-visible, leave it alone for this plan and document
that it remains a smaller hot-path cost.

## What Not To Do In The First Pass

Do not start with:

- an `IdentityHashMap` cache;
- dynamic memoization;
- a new localized/already-localized type hierarchy;
- a new public canonicalization API;
- broad rewrites of `admit-schema` or `schema->type`;
- changes to type equality or type hashing.

Those may become useful later, but the first win should come from deleting
unneeded work. If removing the redundant traversals materially reduces the
profiled cost, added caching may not be necessary.

## Expected Improvement

This plan should reduce both allocation and CPU by removing whole classes of
work:

- Semantic type localization goes from "copy the whole type graph" to
  "return the existing type value" for semantic type inputs.
- Canonicalization of nested raw schemas moves away from repeated subtree
  localization and toward one boundary localization plus canonical recursion.
- Declaration collection removes a second canonicalization pass over every
  generated declaration desc.

Without a fresh profile, do not claim an exact percentage. A reasonable
expectation is a 2x-class improvement in declaration-heavy runs if
`localize-value` is currently the largest CPU and memory source. Allocation
reduction may be larger than CPU reduction when profiles include already-built
semantic types or large repeated nested schemas.

## Verification Gates

Run these from the repo root:

```bash
lein test
```

Run targeted bridge and collection tests while iterating:

```bash
lein test skeptic.analysis.bridge-test
lein test skeptic.schema.collect-test
```

Run lint after implementation:

```bash
clj-kondo --lint .
```

If Leiningen fails with a trampoline temp-file permission error like
`mktemp: mkstemp failed on /tmp/lein-trampoline-...: Operation not permitted`,
rerun the same command with:

```bash
TMPDIR=/Users/demouser/Code/skeptic/skeptic/target/trampolines
```

Do not treat a permission-blocked command as validation.

## Completion Criteria

The implementation is complete when:

- `localize-value` no longer recursively rebuilds semantic types.
- `canonicalize-schema*` no longer calls `localize-value` unconditionally on
  every recursive call.
- declaration collection no longer re-canonicalizes an already-canonical desc
  through `canonicalize-entry`.
- existing recursive Var localization behavior still works.
- semantic type payloads are not walked by `localize-value`.
- targeted tests and full tests pass, or any blocker is reported with the exact
  command and exact error text.
