# Named-Type Folding in Type-Mismatch Reports

## Context

Skeptic's "output mismatch against the declared return type" block dumps the
fully-expanded structural form of named schemas. For `add-with-cache` in
`/Users/demouser/Code/clj-threals/src/clj_threals/operations.clj:204`, the 2-field
return schema `{:result Threal :cache ThrealCache}` renders as a ~100-line tree
of nested sets/vectors, because `Threal` = `[#{Threal} #{Threal} #{Threal}]`
expands recursively.

The fix: (1) at admission, stamp every Type subtree that came from a
referenced plumatic named schema with that schema's qualified-sym on its prov;
(2) at render, fold any non-root Type whose prov has a foldable source by
emitting its `:qualified-sym`. Add a `--explain-full` CLI flag to restore
today's structural rendering on demand. Porcelain honours the flag.

## Hard invariant

**Every Type has a `:prov`. Every `:prov` has a `:source` and a
`:qualified-sym` naming where the shape was declared.** See
`docs/provenance-reference.md` §5-6.

- Use `prov/of` / `prov/source`. They throw on missing — that is correct.
- Do NOT use `(some-> t :prov)`, `when-let`, `or` fallbacks, or `prov/unknown`.
- Any Type whose prov does not carry the declaring name after admission is a
  bug to SURFACE, not to work around.

## Why admission changes (with blocker resolutions verified in code)

### Current admission flow (plumatic)

1. `schema/collect/ns-schema-results` (`schema_base.clj:` & `collect.clj:223`)
   iterates `ns-interns ns ∪ ns-refers ns`. For each Var it reads
   `(:schema (meta v))` and builds a desc.
2. `build-annotated-schema-desc!` (collect.clj:139) first calls
   `abc/canonicalize-schema schema` on the raw `:schema` meta. Result is
   passed to admission.
3. `canonicalize-schema*` (canonicalize.clj:192-255) **strips `NamedSchema`
   at line 197-198** and rebuilds maps/vectors/sets via `into`/`mapv`
   (lines 244-254) as **fresh objects**.
4. Admission (`ab/schema->type` → `import-schema-type*` at bridge.clj:197)
   recurses over the canonicalized value. Composite constructors in
   `import-schema-type*` stamp `ctx.prov` on every produced Type.

### Why names are lost

When admitting `#'add-with-cache`, `ctx.prov`'s qualified-sym is
`clj-threals.operations/add-with-cache`. The canonicalized schema contains
a value that was once Threal's Named-wrapper, but that wrapper has been
stripped and its body rebuilt as a fresh vector/set tree under
add-with-cache's prov. Every node produced carries add-with-cache's
qualified-sym, not Threal's.

Meanwhile `ns-refers ops` supplies `#'Threal`; its admission (separate
top-level call) produces a VectorT under Threal's prov. Two Types describe
the same schema; provs differ.

### The two sub-problems

A. **Canonicalize destroys object identity between
   `canonicalize(:schema of #'Threal)` and Threal's appearance inside
   `canonicalize(:schema of #'add-with-cache)`.** Because canonicalize
   rebuilds collections, the two calls produce different objects.

B. **Admission doesn't know that a recursed-into subtree "was originally
   Threal's body".** Without some signal, it can't swap ctx.prov.

### Fix (two mechanisms, both necessary)

**Memoize `canonicalize-schema` on input identity** (fixes A): a shared
`IdentityHashMap` cache across all canonicalize calls in a collect pass.
`canonicalize(<Threal's raw :schema>)` returns the same object every time,
including when invoked transitively from `canonicalize(<add-with-cache's
raw :schema>)`. Canonicalize is pure; memoization is safe.

**Named-refs identity map threaded via dynamic binding** (fixes B): build
`{canonicalized-value → prov}` from every Var in `(all-ns)` with `:schema`
meta (project-wide; collision-safe since identity-keyed). In
`import-schema-type*`, on entry, look up `(:schema ctx)` in the map; on
hit, swap `ctx.prov` to the mapped prov.

### Scope of Phase 1

- **Plumatic only.** Malli's bridge (`analysis/malli_spec/bridge.clj`) does
  NOT recurse into `[:map …]` / `[:vector …]` — `form->type` handles only
  `:=>`, `:maybe`, `:or`, and leaves. So malli-admitted Types don't contain
  nested named-ref references today; there is nothing to stamp. Renderer
  folds malli Types at root via the existing `:malli-spec` source prov on
  the dict entry. Full malli nested-folding is out of scope.
- **Type-overrides not admitted.** `typed_decls.clj:71-77` inserts override
  Types directly into `:dict` without calling `ab/schema->type`. Their
  prov is whatever the override author stamped. Renderer folds at root via
  `:type-override` source. Inner composite folding for overrides depends
  on what the author stamped; not Phase 1's concern.

### Sidebar: `var-import-type` is not relevant

`var-import-type` (bridge.clj:181) fires only on literal
`clojure.lang.Var` instances in the schema (and on `Var$Unbound` via
`one-step-schema-node` at bridge.clj:64-76 extracting field `v`). The
reference to `Threal` inside `{:result Threal ...}` is the Var's
**dereferenced value** by the time the bridge sees it. `var-import-type`
handles the inner `#{Threal}` cycle detection within Threal's own body via
the `Unbound → Var → var-import-type` path. The admission hook this plan
adds is at `import-schema-type*` on entry — orthogonal to `var-import-type`.

## Decisions fixed

- **Fold sources (renderer)**: `:schema`, `:malli-spec`, `:type-override`.
- **Fold mechanism (renderer)**: `(let [p (prov/of t)] (when (contains?
  foldable-sources (prov/source p)) (:qualified-sym p)))`. Emit that
  symbol instead of recursing. **No equality check. No fold-set. No
  fold-index. No dict merge. No cross-ns plumbing.**
- **Root exception (renderer)**: outermost Type does not fold to its own
  name. Renderer entry point uses `:root? true` on the first call,
  `:root? false` on every recursion.
- **Admission stamping (plumatic)**: memoized canonicalize + identity map
  `{canonicalized-value → prov}` over `(all-ns)` Vars with `:schema`
  meta, threaded via `*named-refs*` dynamic binding, consulted on entry to
  `import-schema-type*`.
- **Admission stamping (malli, type-override)**: none. Root-level fold only.
- **Flag**: `--explain-full`. Default = fold. `--explain-full` = no fold.
- **Porcelain**: honours `--explain-full`.
- **Path diff**: honour cast-result's existing `:path`. No new walker.
- **Qualification**: emit fully-qualified `qualified-sym`.
- **Test runner**: `lein test`. **Linter**: `clj-kondo`.

## Call chain (load-bearing files)

- **Canonicalize (memoization hook)**:
  `skeptic/src/skeptic/analysis/bridge/canonicalize.clj` — add
  `^:dynamic *canonicalize-cache*`. Wrap public `canonicalize-schema`
  (L257-260) to consult/populate the cache when bound. `canonicalize-schema*`
  internals unchanged.
- **Bridge (named-refs hook)**:
  `skeptic/src/skeptic/analysis/bridge.clj` — add
  `^:dynamic *named-refs*`. Modify `import-schema-type*` (L197) to check
  `(.get *named-refs* (:schema ctx))` on entry; on hit, swap `ctx.prov`.
- **Collect (identity map builder + binding scope)**:
  `skeptic/src/skeptic/schema/collect.clj` — `ns-schema-results` (L223)
  sets `binding [abc/*canonicalize-cache* (IdentityHashMap.)
  ab/*named-refs* (IdentityHashMap.)]` around the existing reduce; pre-pass
  walks `(all-ns)` Vars to populate `*named-refs*` via memoized
  `canonicalize-schema`.
- **Bridge admit-schema entry** (`admit-schema` bridce.clj:459-466,
  `import-schema-type` L475-483, `schema->type` L485-488) — unchanged. The
  dynamic binding propagates through function calls.
- **Typed-decls driver**:
  `skeptic/src/skeptic/typed_decls.clj` — unchanged; dynamic bindings set
  upstream in `ns-schema-results` propagate through `desc->type` /
  `ab/schema->type`.
- **Renderer**: `skeptic/src/skeptic/analysis/bridge/render.clj` — today
  contains the prior agent's `build-fold-index` / `folded-entry` /
  `normalize-fold-key` / `strip-prov-local` / `source-priority` /
  `source-rank` / `better-fold-entry` scaffolding. **All deleted.** Replaced
  by `folded-name` (prov-based) + `:root?` opts key in 2-arity
  `render-type-form*` / `type->json-data*`.
- **Report composition, path text, display wrap**: 2-arity opts-aware
  variants already exist from prior attempt and stay.
  `skeptic/src/skeptic/inconsistence/report.clj`, `path.clj`, `display.clj`.
- **`mismatch.clj` (prior attempt missed)**:
  `skeptic/src/skeptic/inconsistence/mismatch.clj` —
  `describe-display-block`, `user-type-form`,
  `mismatched-output-schema-msg`, `mismatched-ground-type-msg`,
  `mismatched-nullable-msg`, `mismatched-schema-msg` must thread opts.
- **CLI opts**: `skeptic/src/skeptic/core.clj:check-project`. Prior agent's
  per-ns fold-index is deleted; `opts*` carries only `:explain-full`.
- **Text output**: `skeptic/src/skeptic/output/text.clj:print-finding!`
  — already threads opts.
- **Porcelain**: `skeptic/src/skeptic/output/porcelain.clj`.
- **CLI flag**: `lein-skeptic/src/leiningen/skeptic.clj`.
- **`check-namespace`**: `skeptic/src/skeptic/checking/pipeline.clj` —
  prior agent's `:namespace-dict` return key removed; reverts to
  `{:results … :provenance …}`.

## Code style checklist (applies to EVERY phase)

Copy verbatim into every subagent prompt:

- Functions must be <20 lines, <10 if possible.
- Write helper functions instead of nested logic.
- Write for the current specification.
- Do not code defensively. Trust internal types.
- Prefer pure functions.
- Modify existing functions in place; update ALL callers.
- Never create `foo_v2`, `new_foo`, "for compatibility" shims.
- Delete obsolete code made redundant.
- No dead code. No TODOs.
- Every new function gets a test.
- 100% test pass rate.
- `prov/of` / `prov/source` throw on missing prov — do NOT guard.

## Phase overview

| Phase | Topic | Agents |
|-------|-------|--------|
| 1 | Memoized canonicalize + plumatic named-refs identity map + `import-schema-type*` entry hook | 1 |
| 2 | Renderer fold by prov; `--explain-full`; opts threading; `mismatch.clj` fix; delete prior fold-index scaffolding | 5 |
| 3 | Porcelain honours `--explain-full` | 1 |
| 4 | `add-with-cache` regression test | 1 |

Dead-code check: Phase 1 puts the correct qualified-sym on admitted Types;
Phase 2 is the first consumer. Phases 3, 4 extend and regression-test.

---

## Phase 1 — Memoized canonicalize + named-refs admission hook

**Goal**: After Phase 1, admitting a schema that references another
plumatic named schema produces a Type subtree whose composite nodes carry
the referenced schema's `qualified-sym` on their prov. Verifiable by unit
test: admit a test-ns Var whose `:schema` references a second test-ns
Var's `:schema`; walk the admitted Type, assert inner nodes'
`(prov/of)` has the referenced var's qualified-sym.

### Mechanism

**Memoization** in `canonicalize.clj`:

```clojure
(def ^:dynamic *canonicalize-cache* nil)

(defn canonicalize-schema
  [schema]
  (let [c *canonicalize-cache*]
    (if c
      (if-let [hit (.get ^java.util.IdentityHashMap c schema)]
        hit
        (let [out (canonicalize-schema* (raw-schema-domain-value schema)
                                        {:constrained->base? false})]
          (.put ^java.util.IdentityHashMap c schema out)
          out))
      (canonicalize-schema* (raw-schema-domain-value schema)
                            {:constrained->base? false}))))
```

**Named-refs binding** in `bridge.clj`:

```clojure
(def ^:dynamic *named-refs* nil)
```

**Hook** at top of `import-schema-type*`:

```clojure
(defn- import-schema-type*
  [run {:keys [schema] :as ctx}]
  (let [hit (when *named-refs*
              (.get ^java.util.IdentityHashMap *named-refs* schema))
        ctx (cond-> ctx hit (assoc :prov hit))
        schema (one-step-schema-node schema)
        scalar-schema (sb/canonical-scalar-schema schema)]
    ; ...existing body unchanged, using ctx's (possibly-swapped) prov...
    ))
```

**Pre-pass** in `schema/collect.clj:ns-schema-results`:

```clojure
(defn- build-plumatic-named-refs!
  "Populates the project-wide identity map of canonicalized schema values
   to provenance. Requires *canonicalize-cache* to be bound; populates it
   as a side effect."
  [named-refs]
  (doseq [v (mapcat (comp vals ns-interns) (all-ns))
          :let [m (meta v)
                raw (:schema m)
                qsym (sb/qualified-var-symbol v)]
          :when (and raw qsym (not (:macro m)))]
    (try
      (.put ^java.util.IdentityHashMap named-refs
            (abc/canonicalize-schema raw)
            (prov/make-provenance :schema qsym (some-> v .ns ns-name) m))
      (catch Exception _e
        ;; a var whose :schema meta isn't a valid plumatic schema can
        ;; exist (e.g. unusual test fixtures); skip silently — collect
        ;; pass will surface any real errors when that var itself is
        ;; admitted.
        nil))))

(defn ns-schema-results
  [_opts ns]
  (binding [abc/*canonicalize-cache* (java.util.IdentityHashMap.)
            ab/*named-refs* (java.util.IdentityHashMap.)]
    (build-plumatic-named-refs! ab/*named-refs*)
    (binding [*ns* (the-ns ns)]
      (reduce <existing body unchanged>
              {:entries {} :errors []}
              (concat (vals (ns-interns ns))
                      (vals (ns-refers ns)))))))
```

Note: the inner `(binding [*ns* (the-ns ns)] ...)` preserves the existing
`*ns*` scoping. The outer `binding` covers the entire reduce so admission
sees both dynamics.

**Self-admission no-op**: when ns-schema-results admits `#'Threal`, the
first `import-schema-type*` entry hits on Threal's canonicalized value,
swapping `ctx.prov` from Threal's prov to Threal's prov. No-op. Composite
recursion proceeds as today.

**Cross-ref swap**: when admitting add-with-cache, `ctx.prov` is
add-with-cache's. Recursing into `{:result <canonicalized-Threal-value>}`,
`import-schema-type*` is called with schema = `<canonicalized-Threal-value>`.
Identity lookup hits. Swap to Threal's prov. Composites inside Threal's
body stamp Threal's prov. On exit from that recursion, the caller's ctx
is unchanged (ctx is local to the function), so the MapT under
add-with-cache stays under add-with-cache's prov — only the Threal subtree
swaps.

### Agents: 1

Phase 1 is intricate, cross-file, and needs careful ordering. A single
`modular-builder` handles it alone.

```
Agent 1 (modular-builder): files
    [skeptic/src/skeptic/analysis/bridge/canonicalize.clj,
     skeptic/src/skeptic/analysis/bridge.clj,
     skeptic/src/skeptic/schema/collect.clj,
     skeptic/test/skeptic/analysis/bridge_test.clj]
  — Add ^:dynamic *canonicalize-cache* in canonicalize.clj and rewrap
    canonicalize-schema with memoization as shown.
  — Add ^:dynamic *named-refs* in bridge.clj. Add the entry hook at
    import-schema-type*.
  — Add build-plumatic-named-refs! in collect.clj. Wrap ns-schema-results
    with the two bindings and call build-plumatic-named-refs! at scope
    entry.
  — Unit tests in bridge_test.clj covering:
      • Same-ns cross-reference: Var A with :schema = [#{Int}]. Var B with
        :schema = {:inner A}. Admit B; walk :inner's VectorT; assert
        every composite node's (prov/of) has qualified-sym = A's
        qualified-sym.
      • Cross-ns cross-reference: same pattern with A in test-ns-1 and B
        in test-ns-2; both in (all-ns). Same assertion.
      • Self-reference (recursive): Var R with :schema = [#{R}]. Admit R;
        walk inner nodes; assert prov qualified-sym = R throughout
        (no-op swap on hits).
      • Scalar-only schema: Var S with :schema = s/Int. No fold-relevant
        structure; admission produces GroundT with caller's prov.
        Assert normal behaviour (no regression).
      • Unrelated var without :schema meta: ensure build-plumatic-named-refs!
        skips without error.
  — Existing bridge_test.clj tests that asserted caller-prov on admitted
    subtrees of named-ref-containing schemas will now see the referenced
    Var's prov instead. Update assertions to match the new (correct)
    behaviour. DO NOT suppress or skip tests.
```

### Deliverables

- `*canonicalize-cache*` memoization in canonicalize.clj.
- `*named-refs*` binding and `import-schema-type*` entry hook in bridge.clj.
- `build-plumatic-named-refs!` helper + binding scope in
  schema/collect.clj:ns-schema-results.
- Unit tests asserting prov.qualified-sym on admitted subtrees (same-ns,
  cross-ns, self-reference, scalar, no-schema-meta).

### Files

- Updated: `skeptic/src/skeptic/analysis/bridge/canonicalize.clj`
- Updated: `skeptic/src/skeptic/analysis/bridge.clj`
- Updated: `skeptic/src/skeptic/schema/collect.clj`
- Updated: `skeptic/test/skeptic/analysis/bridge_test.clj`
- Created: `docs/current-plans/named-type-folding_IMPLEMENTATION_STATUS.md`

### Completion gate

1. `cd skeptic && lein test` — 100% pass. Pre-existing tests that baked
   in caller-prov on named-ref subtrees WILL break. Update the
   assertions; do not suppress.
2. `cd lein-skeptic && lein test` — 100% pass.
3. `clj-kondo --lint src test` — clean in both subprojects.
4. Update status doc with any surprise test breakages found in Step 1
   (enumerate files/assertions changed).
5. Commit: `Phase 1 (memoized canonicalize + named-refs admission hook) complete`.
6. STOP for user approval before Phase 2.

---

## Phase 2 — Renderer fold by prov; `--explain-full`; opts everywhere

**Goal**: default run of `lein skeptic` on the reproducer shows
`{:result clj-threals.threals/Threal :cache
clj-threals.operations/ThrealCache}` in place of the ~100-line structural
block. `--explain-full` restores today's structural block.

### Mechanism

```clojure
(def foldable-sources #{:schema :malli-spec :type-override})

(defn- folded-name
  [t]
  (let [p (prov/of t)]
    (when (contains? foldable-sources (prov/source p))
      (:qualified-sym p))))

(defn render-type-form*
  [t {:keys [explain-full root?] :or {root? true} :as opts}]
  (let [t (ato/normalize-for-declared-type t)
        child-opts (assoc opts :root? false)
        fold-hit (and (not explain-full)
                      (not root?)
                      (folded-name t))]
    (if fold-hit
      fold-hit
      <existing structural cond, recursing with child-opts>)))
```

`type->json-data*` gets the same pattern, emitting
`{:t "named" :name "<qualified-sym>" :source "<source name>"}` on fold.

### Delete from prior attempt

From `render.clj`: `source-priority`, `source-rank`, `better-fold-entry`,
`normalize-fold-key`, `strip-prov-local`, `build-fold-index`,
`folded-entry`.

From `core.clj:check-project`: per-ns `(abr/build-fold-index …)` call and
`:fold-index` key in `opts*`. `opts*` keeps only `:explain-full`.

From `pipeline.clj:check-namespace`: `:namespace-dict` return key and the
`namespace-dict` builder. Return shape reverts to
`{:results … :provenance …}`.

### Agents: 5

Ordered: 1 → {2,3} parallel → 4 → 5.

```
Agent 1 (modular-builder): files
    [skeptic/src/skeptic/analysis/bridge/render.clj,
     skeptic/test/skeptic/analysis/bridge/render_test.clj]
  — Delete build-fold-index / folded-entry / normalize-fold-key /
    strip-prov-local / source-priority / source-rank /
    better-fold-entry. Introduce folded-name helper and 2-arity
    render-type-form* / type->json-data* with {:explain-full :root?}
    opts as above. 1-arity delegates with root? true, explain-full false.
  — Tests:
      • t with foldable prov → folded-name returns qualified-sym.
      • t with :native / :inferred prov → folded-name returns nil.
      • root? true → no fold, structural output.
      • root? false → fold.
      • explain-full true → no fold anywhere.
      • type->json-data* emits {:t "named" :name … :source …} on fold.
  — complex, alone.

Agent 2 (kiss-code-generator): files
    [skeptic/src/skeptic/inconsistence/display.clj,
     skeptic/src/skeptic/inconsistence/path.clj,
     skeptic/src/skeptic/inconsistence/mismatch.clj]
  — Retain existing 2-arity opts-aware variants in display.clj and
    path.clj (from prior attempt).
  — FIX mismatch.clj (prior attempt missed this):
      • describe-display-block — add opts param, pass to user-type-form.
      • user-type-form — add opts param, pass to render-type /
        type->json-data*.
      • mismatched-output-schema-msg, mismatched-ground-type-msg,
        mismatched-nullable-msg, mismatched-schema-msg — add opts param.
      • Keep 1-arity delegating to 2-arity with empty opts.
  — Update callers within mismatch.clj to pass opts.

Agent 3 (modular-builder): files
    [skeptic/src/skeptic/inconsistence/report.clj]
  — Retain existing opts-threading from prior attempt; remove any
    :fold-index references. opts now carries only :explain-full.
  — Update output-cast-report to pass opts into
    mm/mismatched-output-schema-msg after Agent 2's update.
  — complex, alone.

Agent 4 (kiss-code-generator): files
    [skeptic/src/skeptic/core.clj,
     skeptic/src/skeptic/checking/pipeline.clj,
     lein-skeptic/src/leiningen/skeptic.clj]
  — core.clj:check-project:
      • Remove per-ns (abr/build-fold-index …) call.
      • Remove :fold-index from opts*. Keep :explain-full.
      • Remove destructuring of :namespace-dict from check-namespace.
  — pipeline.clj:check-namespace:
      • Remove :namespace-dict return key and its builder. Return
        {:results … :provenance …}.
  — lein-skeptic/src/leiningen/skeptic.clj:
      • Confirm --explain-full is in cli-options with :flag metadata and
        docstring. If prior attempt left it, keep; if missing, add.
      • Thread :explain-full into opts passed to check-project.

Agent 5 (kiss-code-generator): files
    [skeptic/test/skeptic/inconsistence/report_test.clj,
     skeptic/test/skeptic/inconsistence/display_test.clj,
     skeptic/test/skeptic/core_test.clj]
  — Update assertions:
      • Remove any :fold-index in opts; assert only :explain-full and
        final rendered text.
      • --explain-full parallel assertions remain intact.
      • Structural expectations that baked in the ~100-line expansion
        must now expect the folded form.
```

### Deliverables

- `folded-name` + 2-arity renderer in `render.clj`.
- All prior fold-index scaffolding deleted.
- `mismatch.clj` opts-threaded.
- `:fold-index` removed from opts.
- `check-namespace` return shape reverted.
- Tests updated.

### Files

- Updated: `skeptic/src/skeptic/analysis/bridge/render.clj`
- Updated: `skeptic/src/skeptic/inconsistence/display.clj`
- Updated: `skeptic/src/skeptic/inconsistence/path.clj`
- Updated: `skeptic/src/skeptic/inconsistence/mismatch.clj`
- Updated: `skeptic/src/skeptic/inconsistence/report.clj`
- Updated: `skeptic/src/skeptic/core.clj`
- Updated: `skeptic/src/skeptic/checking/pipeline.clj`
- Updated: `lein-skeptic/src/leiningen/skeptic.clj`
- Updated: `skeptic/test/skeptic/analysis/bridge/render_test.clj`
- Updated: `skeptic/test/skeptic/inconsistence/report_test.clj`
- Updated: `skeptic/test/skeptic/inconsistence/display_test.clj`
- Updated: `skeptic/test/skeptic/core_test.clj`

### Completion gate

1. `cd skeptic && lein test` — 100% pass.
2. `cd lein-skeptic && lein test` — 100% pass.
3. `clj-kondo --lint src test` — clean.
4. Update status doc.
5. Commit: `Phase 2 (renderer fold + --explain-full flag) complete`.
6. Manual smoke:
   - `cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations`
     — short output; `{:result clj-threals.threals/Threal :cache
     clj-threals.operations/ThrealCache}` near the `add-with-cache`
     block.
   - `cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations --explain-full`
     — today's ~100-line structural block.
7. STOP for user approval before Phase 3.

---

## Phase 3 — Porcelain honours `--explain-full`

### Agents: 1

```
Agent 1 (kiss-code-generator): files
    [skeptic/src/skeptic/output/porcelain.clj,
     skeptic/test/skeptic/output/porcelain_test.clj]
  — Ensure porcelain's calls into abr/type->json-data and abr/render-type
    use the 2-arity variants with opts containing :explain-full.
  — Remove any :fold-index references.
  — Tests for both modes:
      • Default → {"t":"named","name":"…","source":"…"}.
      • --explain-full true → structural {"t":"map"|"vector"|…}.
```

### Files

- Updated: `skeptic/src/skeptic/output/porcelain.clj`
- Updated: `skeptic/test/skeptic/output/porcelain_test.clj`

### Completion gate

1. Tests + lint + status doc + commit.
2. Manual smoke:
   - `lein skeptic -n clj-threals.operations --porcelain | jq .` — folded
     subtrees show
     `{"t":"named","name":"clj-threals.threals/Threal","source":"schema"}`.
   - `lein skeptic -n clj-threals.operations --porcelain --explain-full | jq .`
     — structural expansion.
3. STOP for user approval before Phase 4.

---

## Phase 4 — `add-with-cache` regression test

### Agents: 1

```
Agent 1 (kiss-code-generator): files
    [skeptic/test/skeptic/test_examples/catalog.clj,
     skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj]
  — Fixture: recursive schema Inner in one test ns; consumer fn Outer in
    another test ns whose declared return references Inner and whose
    inferred output structurally doesn't match.
  — Assertions:
      • Default report text references Inner by qualified-sym; report
        body under N lines (e.g. 15).
      • --explain-full text contains expanded structural rendering
        (`#{…}` tree).
      • Porcelain default → {:t "named"} for Inner's subtree.
      • Porcelain --explain-full → {:t "map"|"vector"|"set"}.
```

### Files

- Created: `skeptic/test/skeptic/checking/pipeline/named_fold_regression_test.clj`
- Updated: `skeptic/test/skeptic/test_examples/catalog.clj`
- Created (two-ns fixture): as needed under `skeptic/test/skeptic/test_examples/`

### Completion gate

1. Tests + lint + status doc + commit.
2. Final manual verification on the clj-threals reproducer (both text
   modes and both porcelain modes).
3. STOP — effort complete.

---

## Verification (end-to-end)

```
cd /Users/demouser/Code/skeptic/skeptic && lein test
cd /Users/demouser/Code/skeptic/lein-skeptic && lein test
cd /Users/demouser/Code/skeptic/skeptic && clj-kondo --lint src test
cd /Users/demouser/Code/skeptic/lein-skeptic && clj-kondo --lint src

cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations
cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations --explain-full
cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations --porcelain | head
cd /Users/demouser/Code/clj-threals && lein skeptic -n clj-threals.operations --porcelain --explain-full | head
```

Expected: default text run shows
`{:result clj-threals.threals/Threal :cache clj-threals.operations/ThrealCache}`
near the `add-with-cache` blame block. `--explain-full` restores today's
~100-line structural block. Porcelain JSON emits `{"t":"named"}` by
default and `{"t":"map"}` under `--explain-full`.
