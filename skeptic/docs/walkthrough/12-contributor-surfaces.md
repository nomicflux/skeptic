# Contributor Surfaces and Pitfalls

> *Snapshot of state as of 2026-05-05.*

This is the reference spoke for contributors. It names the entry
points for adding new things — dispatch rules, Type kinds,
admission sources — and it covers five pitfalls keyed to specific
invariants the rest of the walkthrough has established. Each
pitfall says what the invariant is, what bug violating it
produces, and what the fix looks like.

## Prerequisites

Most of the prior spokes — particularly
[03 (Type Domain)](03-type-domain.md),
[04 (Provenance)](04-provenance.md),
[06 (Annotation Pass)](06-annotation-pass.md),
[09 (Cast Dispatch)](09-cast-dispatch.md), and
[10 (BfA + Projection)](10-blame-for-all-and-projection.md). This
is a reference spoke to consult while making changes; it does not
introduce new concepts. A contributor arriving here without the
prerequisites will not be served — every pitfall references an
invariant established in an earlier spoke.

## Where this fits

Twelfth (last) on the Contributor path. After this, the
walkthrough is done. Diagnose-finding readers do not visit this
spoke.

## Adding a new dispatch rule

**This section teaches: the steps for adding a new rule to
`dispatch-cast`, with the priority-slot decision as the
load-bearing choice.**

A new dispatch rule is a small change with one big risk: getting
the priority slot wrong means the rule never fires (or fires when
it shouldn't). The slot decides whether the new rule
short-circuits other rules, falls through to them, or sits
between two specific tiers.

The seven-step procedure:

1. **Identify the rule's *kind*.** Is it vacuous (always passes
   on a specific shape)? Quantified (operates on a binder)?
   Dynamic (a Dyn-shaped catch-all)? Branching (distributes over
   union/intersection/conditional)? Recursive resolution (placeholder/
   inf-cycle)? Maybe-shaped? Wrapper? Same-shape structural?
   Leaf? The kind determines the tier.
2. **Pick a priority slot in `dispatch-cast`.** Vacuous and
   trivial first; quantified above abstract; abstract above
   target-dyn; target-dyn above branching; branching above
   recursive resolution; resolution above maybe; maybe above
   wrappers; wrappers above same-shape structural; structural
   above leaf. The placement determines correctness; getting it
   wrong is the most common bug in cast extension.
3. **Implement the rule** in a sub-namespace under
   `skeptic.analysis.cast.*`. Pick the sub-namespace by what the
   rule does — `cast.branch` for branching kinds, `cast.collection`
   for vector/seq/set/leaf, `cast.function` for function casts,
   `cast.map` for map-specific work, `cast.quantified` for
   ForallT/TypeVarT/SealedDynT.
4. **Use `cast-ok` / `cast-fail` / `aggregate-children`** in
   `cast/support.clj` to construct results. Pass an explicit
   structural rule keyword for the parent and a descriptive
   reason keyword for failures.
5. **Decide whether the new rule is structural.** If the leaf
   walker should descend through it looking for an inner leaf,
   add the rule's keyword to the structural-rules set in
   `cast/result.clj`. If the rule is itself the leaf the user
   should see, leave the set alone.
6. **Update display.** If the new path segment is visible to
   users, add a `render-path-segment` case in
   `inconsistence/path.clj`. Internal-only segments
   (union-branch markers, etc.) don't need a render case; the
   visible-path filter drops them.
7. **Tests.** Add tests against both leaf cases and structural
   composition (the new rule inside a `UnionT`, inside a `MapT`
   value position, etc.). Use `at/type=?` for shape comparisons,
   never `=`.

The order of priority slots is the contract; see Pitfall 2
below. The layered structure of `dispatch-cast` is what lets a
contributor pick a slot by tier rather than by examining each
adjacent clause.

## Adding a new Type kind

**This section teaches: the six places a new Type kind has to
land, ordered by what would break if any one were missed.**

Adding a Type kind is a larger change than a dispatch rule
because the kind affects every layer that touches Types —
admission, annotation, cast, projection, display, equality,
deduplication. Six places:

1. **The record + predicate** in `skeptic/analysis/types.clj`.
   Define the `defrecord` with `:prov` first; extend
   `proto/SemanticType`; add a per-record tag keyword and add
   it to `known-semantic-type-tags`; add the `kind?-type?`
   predicate. The auto-equality and `dedup-types` use
   `semantic-tag` dispatch but custom shape comparisons (e.g.,
   the new kind has unordered members like `UnionT` does)
   require a branch in `semantic-type=?`.
2. **Constructors and helpers** in
   `skeptic/analysis/type_ops.clj` and possibly
   `skeptic/analysis/type_algebra.clj`. Any non-trivial builder
   (normalization, deduplication, simplification) lives here.
3. **The cast dispatch** in `skeptic/analysis/cast.clj` —
   `dispatch-cast`. Add a clause in the right priority slot.
   See [spoke 09](09-cast-dispatch.md#in-depth-how-to-add-a-new-dispatch-rule)
   and the dispatch-rule section above.
4. **The cast implementation**, possibly a new file under
   `skeptic.analysis.cast.*`. Pick the sub-namespace by what
   the rule does (branching, collection, function, map,
   quantified).
5. **Display** in `skeptic/analysis/bridge/render.clj`. Add a
   `render-type-form*` branch and (for JSONL) a
   `type->json-data*` branch. Otherwise the new kind renders as
   `<unknown>` in the user's findings.
6. **Tests** in `test/skeptic/analysis/`. Test both the leaf
   cases (this kind alone) and the structural composition (this
   kind inside a `UnionT`, inside a `MapT` value position,
   etc.).

The largest gotcha is step 5: a Type kind that the cast engine
handles correctly but the renderer doesn't will produce findings
with garbled actual/expected fields, even though the cast
verdict itself is right.

A second gotcha worth flagging: if the new kind contains *other
Types* as fields (members, inner, branches, methods), the kind
participates in `prov/of`'s recursive walk and in the strip-
derived-types pass. The kind needs to play correctly with both;
the existing constructor pattern (`:prov` first, plain fields
after) is the path of least resistance.

## Adding a new admission source

**This section teaches: the four pieces a new admission source
needs, modeled after the Native source's pattern.**

A new admission source is rare but not impossible — Malli was
added this way alongside Plumatic when Malli support became
experimental. The Native source is the simplest example to
copy; a contributor adding a new source typically follows its
pattern.

Four pieces:

1. **A collector namespace** that walks the input data and
   produces admission entries. The collector reads from
   wherever the source's data lives — var metadata, a registry,
   a config file — and produces a per-namespace result map of
   the standard shape `{:dict … :provenance … :ignore-body … :errors …}`.
2. **A bridge namespace** that converts each admitted shape to
   a Type with the appropriate provenance source. The bridge
   is the boundary; it produces Types with the new
   `:source` value on every `:prov`. The Plumatic bridge is in
   `skeptic.analysis.bridge`; the Malli bridge in
   `skeptic.analysis.malli-spec.bridge`. The new source's
   bridge would live alongside.
3. **A plug-in point** in
   `skeptic.checking.pipeline/namespace-dict` (or in the
   `merge-type-dicts` call inside it) that runs the new
   collector and includes its result in the merge. The
   merging itself uses the existing rank-based machinery.
4. **A rank choice** in
   `skeptic/provenance.clj`'s `source-rank-map`. Where does
   the new source sit between `:type-override` (rank 0) and
   `:inferred` (rank 4)? See
   [spoke 04](04-provenance.md#source-rank-and-merge-provenances)
   for the existing rank table and reasoning.

The merge semantics ([spoke 05](05-admission-paths.md#merging-the-four-sources))
are *intersection of Types and rank-merge of Provenances*. So
the contributor adding a new source is automatically deciding,
via the rank choice, what the source's claims do when they
collide with other sources' claims for the same qualified
symbol. A new source ranked lower than `:schema` would mean
"this source's declarations are stronger than the user's
Plumatic schemas" — a strong claim that requires explicit
justification.

## Pitfall 1 — Forgetting anchor provenance on a combinator

**Invariant**: When a combinator builds a *composite* Type
from constituents, the result's `:prov` is the *anchor
provenance supplied by the caller* — the container's own prov
— not a provenance derived from the items
([spoke 04](04-provenance.md#combinator-anchor-provenance)).

**Bug**:

```clojure
;; ✗ — derives the union's prov from the first member.
(defn join-arms [arm-types]
  (at/->UnionT (prov/of (first arm-types))
               arm-types))
```

The union built this way carries the *first member's*
provenance, not the construction site's. A finding on the union
would attribute itself to wherever the first member came from —
which might be a deep substructure of an unrelated form.
Findings become nondeterministic depending on which member
sorted first.

**Fix**:

```clojure
;; ✓ — caller supplies anchor; member provs stay on members.
(defn join-arms [anchor-prov arm-types]
  (at/->UnionT anchor-prov arm-types))
```

The caller — the `:if` annotator, say — passes the analyzer's
ctx-derived prov as the anchor. The members keep their own
provs on themselves. The finding renderer reads the union's
prov for `:source`, which now points at the construction site,
which is what the user wants to see.

The invariant matters because a contributor reading source
without this rule in mind will *naturally* reach for "use the
first member's prov" — it's the path of least resistance.
The combinator-anchor rule (and the explicit anchor parameters
on `av/join`, `amo/merge-map-types`, `amoa/merge-types`,
`coll/concat-output-type`, `ato/union-type`) is what forces the
right choice.

## Pitfall 2 — Introducing a quantified Type in annotation

**Invariant**: Annotation is *first-order*. The annotation pass
never produces a `ForallT`, `TypeVarT`, or `SealedDynT`
([spoke 06](06-annotation-pass.md#the-first-order-invariant)).
Quantified Types enter Skeptic only at admission or under cast.

**Bug**:

```clojure
;; ✗ — annotator synthesizes a forall on a "looks generic" body.
(defn annotate-fn [ctx node]
  (let [body-type (recurse ctx (:body node))
        free-vars (collect-free-vars body-type)]
    (if (seq free-vars)
      (assoc node :type
             (at/->ForallT (prov/with-ctx ctx)
                           (first free-vars)
                           body-type))
      (assoc node :type body-type))))
```

The annotator looks at the body, sees something that *looks*
polymorphic, and wraps the result in a `ForallT`. Now every
call to this function reaches the cast engine as a quantified
type. The cast engine's quantified rules
([spoke 10](10-blame-for-all-and-projection.md)) fire for
ordinary call sites that don't actually involve polymorphism —
seal/collapse machinery activates for first-order code.
Findings either silently disappear (the seals always succeed)
or produce nonsensical `:nu-tamper` errors.

**Fix**:

```clojure
;; ✓ — annotator produces a first-order Type. Quantified types
;; come from admission only.
(defn annotate-fn [ctx node]
  (let [body-type (recurse ctx (:body node))]
    (assoc node :type body-type)))
```

If the function genuinely *should* be polymorphic, the user
declares it with `^{:skeptic/type (forall [X] (=> X X))}` —
admission produces the `ForallT`, with `:source :type-override`.
The annotator stays first-order. The cast engine's quantified
rules fire only when there's a real `ForallT` in scope, never
on speculation.

The invariant matters because the cast engine's first-order
rules vastly outnumber its quantified rules — eighteen of the
twenty dispatch clauses. Synthesizing a `ForallT` in annotation
forces every cast through the small-and-careful quantified path
unnecessarily, eroding precision and adding latent bugs.

## Pitfall 3 — Reaching past `annotate.api`

**Invariant**: Code outside `annotate.*` accesses annotated
nodes through the API in `skeptic.analysis.annotate.api`
([spoke 06](06-annotation-pass.md#the-annotated-node-api)).
Direct field access from outside the family is forbidden.

**Bug**:

```clojure
;; ✗ — reaches past the API.
(defn external-helper [annotated-node]
  (when (= :invoke (:op annotated-node))
    (let [t (:type annotated-node)
          out (:output-type annotated-node)
          fn-node (:fn annotated-node)]
      ...)))
```

Field access (`:op`, `:type`, `:output-type`, `:fn`) couples the
helper to the *current shape* of the annotated node. A future
change to the node's keys — adding `:expected-argtypes`,
renaming `:output-type` to `:return-type`, splitting `:fn` into
`:callee` and `:fn-meta` — would silently break this helper. No
compile-time signal would tell the contributor to update.

**Fix**:

```clojure
;; ✓ — uses the API.
(defn external-helper [annotated-node]
  (when (= :invoke (aapi/node-op annotated-node))
    (let [t (aapi/node-type annotated-node)
          out (aapi/node-output-type annotated-node)
          fn-node (aapi/call-fn-node annotated-node)]
      ...)))
```

The accessors (`aapi/node-op`, `aapi/node-type`,
`aapi/node-output-type`, `aapi/call-fn-node`) hide the node's
field shape. A future change updates the API's accessors but
preserves their behaviour; external code keeps working without
modification.

The invariant matters because the annotated-node shape is
expected to evolve as new annotation features land. Pinning
external code to the *current* shape would freeze the shape;
the API boundary is the breathing room. The rule extends to
tests: a test that reaches into `(:type node)` is exactly as
fragile as production code doing the same.

## Pitfall 4 — Treating Schema as the analysis language

**Invariant**: Real semantic work — checking, narrowing,
exhaustiveness, cast dispatch, blame — happens in the *Type
domain*. Schema and MalliSpec are admission-time inputs only
([spoke 02](02-three-domains.md#what-each-domain-is-allowed-to-do)).

**Bug**:

```clojure
;; ✗ — analysis touches Plumatic schema forms directly.
(defn refine-by-key [schema key]
  (cond
    (map? schema)
    (let [v (get schema key)
          opt-v (get schema (s/optional-key key))]
      (or v opt-v))

    (and (seq? schema) (= 's/maybe (first schema)))
    (refine-by-key (second schema) key)

    :else nil))
```

The helper walks Plumatic schema forms, distinguishing literal
keys from `s/optional-key` keys, peeling `s/maybe`, and so on.
Every shape it handles is one Skeptic already handled at the
admission boundary — and re-implementing the boundary inside an
analysis function means the helper fights with the existing
machinery.

The bug isn't subtle: any quirk of the Plumatic schema form
not handled by this helper produces a wrong answer. Recursive
schemas reference vars, which this helper doesn't know about.
`(s/cond-pre)` and `(s/either)` fall through. The helper
quietly returns `nil` on shapes it doesn't recognize, and
analysis silently degrades.

**Fix**:

```clojure
;; ✓ — analysis works in the Type domain.
(defn refine-by-key [type key]
  (cond
    (at/map-type? type)
    (let [entries (:entries type)
          target-key (ato/exact-value-type ... key)]
      (or (get entries target-key)
          (some-> (find-domain-entry entries target-key) :value)))

    (at/maybe-type? type)
    (refine-by-key (:inner type) key)

    :else nil))
```

Now the helper operates on Types — `MapT`, `MaybeT`, the
already-admitted shapes the cast engine works with. Recursive
schemas, `cond-pre`, `either`, `One`-prefixed vectors, every
shape Plumatic admits — the boundary already converted them all
to Types. The analysis layer doesn't have to reimplement the
boundary.

The invariant matters because it is *the reason the Type domain
exists*. The whole architectural purpose of admission is "do
this work once, at the boundary, then never again." Reaching
back across the boundary inside analysis erodes that purpose.

## Pitfall 5 — Relying on inferred provenance for declaration-level questions

**Invariant**: The *parallel provenance map* records
declaration-level provenance per qualified symbol. Types' own
`:prov` records construction-level provenance, which can drift
from the declaration's
([spoke 04](04-provenance.md#the-provenance-map-vs-the-dict)).

**Bug**:

```clojure
;; ✗ — reads the Type's :prov to decide if a symbol is user-declared.
(defn user-declared? [dict sym]
  (let [type (get dict sym)
        source (some-> type prov/of prov/source)]
    (contains? #{:schema :malli :type-override} source)))
```

The Type's `:prov` is the *construction-level* provenance. For
a `FunT` admitted directly from a `:schema` declaration, the
prov source is `:schema` — and the helper returns the right
answer. But if a downstream pass *rebuilds* the Type — wrapping
it in a `MaybeT`, joining it with another Type as part of a
`UnionT`, applying a narrowing — the construction-level prov
might be `:inferred` (the rebuild happened at inference time).
The helper now returns `false` for a symbol the user *did*
declare; checks downstream silently misclassify.

**Fix**:

```clojure
;; ✓ — reads the parallel provenance map.
(defn user-declared? [provenance-map sym]
  (let [prov (get provenance-map sym)
        source (some-> prov prov/source)]
    (contains? #{:schema :malli :type-override} source)))
```

The provenance map records the *declaration-level* fact: this
symbol was admitted from a `:schema` source, regardless of how
many times its Type has been rewrapped or recombined since.
The helper now returns the right answer in every case.

The invariant matters because the dict's Type and the
provenance map *answer different questions*: the Type answers
"what shape does this value have, with the most current
information?"; the provenance map answers "where did this
declaration originally come from?". Conflating them produces
silent downstream bugs that show up as wrong `:source` fields
on findings — exactly the kind of bug that's hard to track
because the user-facing symptom is "the source attribution is
wrong" without an obvious cause.

### In-depth: a shape-checklist for the new admission source

***Skip if reading the Gist path.***

A contributor adding a new admission source should produce
something with the same observable shape as the Plumatic and
Malli sources. This in-depth lists the specific checks the new
source needs to pass for the system to integrate cleanly.

**Per-namespace result shape.** The collector returns:

```clojure
{:dict        {qualified-sym → Type}
 :provenance  {qualified-sym → Provenance}
 :ignore-body #{qualified-sym ...}
 :errors      [exception-record ...]}
```

Every key is required. `:ignore-body` may be empty; `:errors`
should contain `declaration-error-result`-shaped records
(`{:report-kind :exception :phase :declaration ...}`) for any
declaration the source admitted unsuccessfully.

**Type provenance.** Every Type in `:dict` carries a `:prov`
whose `:source` is the new source's keyword (matching the new
entry in `source-rank-map`). The Provenance also carries
`:qualified-sym` (the symbol), `:declared-in` (the namespace
the declaration came from), and `:var-meta` (the var's
metadata, for downstream context).

**Idempotency.** Running the collector twice on the same
namespace produces the same dict, the same provenance map, and
the same ignore-body set. Test this — a source that mutates
state during admission, or that depends on global call order,
will produce intermittent test failures.

**Error containment.** A declaration-level exception inside the
collector is captured in `:errors` and admission for *other*
declarations in the same namespace continues. The Plumatic
collector demonstrates the pattern via its `try / catch /
declaration-error-result` shape; the new source should follow
suit.

**Rank choice consistency.** The rank chosen for the new
source should be consistent with the source's *role* in the
responsibility ladder. A source that admits user-supplied
declarations sits above `:native` (rank 3); a library-side
source that admits built-in claims sits at `:native`'s level
or below. A user-override-style source sits above `:malli`
(rank 1) — that ranking is reserved for explicit user intent.

The Plumatic and Malli sources both score 4/4 on this
checklist; the Native source scores 4/4 with a trivial
collector (no per-namespace data; just the static dict). A new
source that fails any item is likely to produce surprising
behavior in some downstream code path.

## Where to look first when something looks wrong

A short decision tree for unexpected behaviour:

- **Finding's `:source` is unexpected.** → Check
  `prov/merge-provenances` rank. The cast root merged a source-
  side and target-side provenance; the lower rank wins. If the
  target was schema-declared, the source is `:schema` even when
  the term-side blame might suggest otherwise.
- **No finding at all when you expected one.** → Check whether
  the structural rule called `aggregate-children` with all
  children present. A missed child means a missed leaf. Also
  check whether the failing leaf's `actual_type` is `Dyn`-shaped —
  if so, upstream inference produced a `Dyn` and the cast
  couldn't tell.
- **Cast result has `:residual-dynamic` rule.** → A placeholder
  didn't resolve. The schema is recursively self-referential
  and the cycle wasn't broken; check
  `bridge.localize/localize-value` and the `seen-vars` set.
- **Type is `Dyn` when you expected something concrete.** →
  Check the annotator for the relevant `:op`. The annotator
  produced `Dyn` because the underlying analyzer node didn't
  carry enough information for inference, or because the
  sub-namespace annotator didn't recognize the form's shape.
- **Self-check fails after a refactor that "just renamed
  things."** → Provenance qualified-syms drifted; the dict's
  `:qualified-sym` no longer matches the renamed symbols. The
  dict itself may be stale; rebuild from a clean state.

## Marquee functions

| Function              | File                              | Role                                         |
|-----------------------|-----------------------------------|----------------------------------------------|
| `dispatch-cast`       | `skeptic/analysis/cast.clj`        | The rule-priority site.                       |
| `annotate-dispatch`   | `skeptic/analysis/annotate.clj`    | The `:op`-priority site.                      |
| `merge-type-dicts`    | `skeptic/typed_decls.clj`           | The admission-rank site.                      |
| `merge-provenances`   | `skeptic/provenance.clj`            | The source-rank site.                         |

## Worked example here

The pitfalls reference `classify` and `double-or-zero` only
where their shapes happen to illustrate a pitfall — for example,
Pitfall 5's "wrong `:source`" framing is the kind of bug that
would surface as `classify`'s finding showing
`[source: inferred]` rather than `[source: schema]` if the
construction-level prov leaked into the user-facing source
attribution. The walk-through example doesn't naturally produce
the bug; it's the kind of bug a contributor introduces and
needs to recognize.

## Glossary terms introduced

(none — this spoke uses terms homed elsewhere)

## Where to next

- **The Walkthrough is complete.** Welcome back.
- **Return:** [Hub](README.md)
