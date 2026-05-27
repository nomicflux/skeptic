# HANDOFF — Two-JVM Worker Boundary for Skeptic (re-baselined)

Status: **R0 and R1 have LANDED.** The GroundT/PredInfo class-name representation change
(HANDOFF originally called this "the true foundation phase", §2 below) is committed as R0
(chain-1), and the `form-refs` Var→qsym refactor (originally §3 item 3) is committed as R1.
What remains is the **R2–R5 unit**: the worker/host process split itself. No R2–R5 plan
exists yet — this doc is the discovery baseline for writing one.

HEAD: `3333aaa` (branch `0.9.0-rc`), six commits past the original handoff base `e7672a6`:
- `618ce2a` / `03bf148` / `8e1c31f` — **R0 (chain-1)**: GroundT/PredInfo `:class` fields now
  hold class-NAME strings; host resolves to a live Class via `at/ground-class`
  (`types.clj:20-27`, `Class/forName`). `integral-class-names` (name strings) live in
  `types.clj:29-31`.
- `c786b90` / `e037b9d` / `3333aaa` — **R1**: the project-wide Plumatic form-refs map is
  re-keyed from `IdentityHashMap{Var→form}` to a plain `{qsym→form}` value; the dead
  `:form-refs` ProjectState field is removed.

The failed parent-first `URLClassLoader` attempt is at git tag `v0.9.0-rc5`. A prior
fabricated plan (`docs/current-plans/two-jvm-worker-boundary.md`) was deleted; do not
reconstruct it. **Line numbers below are from the ORIGINAL handoff base `e7672a6` UNLESS a
note says otherwise — R0/R1 shifted many. Re-confirm every anchor before editing.**

---

## 0. READ THIS FIRST — the failure mode that destroyed THREE attempts

Three agents in a row failed for the **same** thing: **asserting code effects (placements,
key-sets, "consumers read X", line-anchored edits) without having read the code, then
dressing the assertion in the texture of verification** — tables, "Verified Facts" headers,
file:line cites for files never opened.

The trap is specific: you trace *construction sites* and a couple of *comparison lines*, then
write a plan as if you traced the whole call chain. You did not. The **consumers** of a value
— every `(:k x)`, every `isa?`/`.isAssignableFrom`/`(contains? <set> x)`, every place the
value flows downstream — are where the real edits live, and they are exactly what gets
skipped. Session 3 fabricated the plan's central whitelist by *stating the method* ("read
these four files, the whitelist is what consumers read") and then **not running it** —
writing from memory. The plan was deleted for this.

**Operating rules for this work (verbatim from the user — non-negotiable):**
- "THE PLAN NEEDS 100% OF EVERY EFFECT ANALYZED. ANYTHING ELSE IS HOPES AND DREAMS."
- "We are NOT CHANGING code behavior beyond the split."
- "The current behavior is the contract" — you do NOT get to re-derive a key-set; the keys the
  current code keeps, and the results it produces, are the contract.

A claim enters a plan ONLY if you can point at the read that proves it, performed at
plan-write time. If you write "consumers already read this" / "strict subset" / "names
suffice" / "zero consumers" without an open file behind it — stop, you are fabricating.
**When stating where code runs or what a value is, cite the file:line you read.**

---

## 1. The shape of what we are building (user-ratified design only)

Problem: Skeptic and the project it checks each need their own versions of libraries (schema,
project deps). One JVM/classloader cannot hold two versions of one class. The rc5
shared-classpath model and the current `eval-in-project` both fail for this.

The boundary (each point ruled by the user, not inferred):

- **Two processes, no shared classpath.** Worker runs on the *project's* classpath; host runs
  on *Skeptic's*. Neither contaminates the other.
- **Worker = dumb data extractor.** Loads the project, runs its macros, runs the analyzers,
  emits **plain EDN**. Depends on **NO Skeptic analysis library** — allowed exceptions ONLY
  `tools.analyzer.jvm`, the cljs analyzer, and minimal form-projection code, *and only if
  there is nowhere else to put them.*
- **Schema analysis is host-only, on FORMS,** with Skeptic's pinned `schema.core` 1.4.1. The
  project's schema version is irrelevant. (User: "schema data is in Skeptic. Period.") This
  was the rock the *previous* agent (Session 1) broke on — it placed `schema->type` in the
  worker. `schema->type`, `malli-spec->type`, cast, blame, type domain, annotation, output =
  HOST.
- **Class-subtype between two arbitrary project classes → live worker oracle.** Host can't
  resolve project classes; precompute is impossible (operands not co-located at construction);
  ancestor-sets were ruled a "mini-rewrite of Skeptic in the worker" and rejected. Surviving
  model: worker stays **alive during checking** and answers class questions on demand. User
  dislikes it but accepts it as the only workable option.
- **Per-form clj analysis is LAZY** (user: "Let's start with lazy") — worker analyzes each clj
  form on demand during checking.
- **Admission is a BARRIER, not streamed** — the merged dict must be complete across all
  namespaces before any checking starts (a call site in ns A can reference a schema in ns B).
- **`eval-in-project` must be removed.**
- **Transport:** user prefers a real RPC system; nREPL acceptable "if it handles that." nREPL's
  EDN transport + custom ops was probe-verified to fit (§2).

---

## 2. REAL code findings (each traces to a read or captured probe this session)

Line numbers are HEAD `e7672a6`. **Re-confirm them before editing — they drift.**

### Contamination / execution path
- `leiningen/skeptic.clj:30-42` — `eval-in-project` runs `skeptic.core/check-project` **inside
  the project JVM**; the serialized form also does output-redirect, `without-fn-validation`,
  `skeptic.profiling/run`, `System/exit` there. `skeptic-profile` (`:10-13`) injects Skeptic as
  a project dep. Replacing this relocates ALL that orchestration to Skeptic's JVM — not just
  "spawn a worker."
- `cli/main.clj:113-131` — deps `-T` path runs in Skeptic's JVM; docstring says project "not
  added to this JVM's classpath" (`:118`), but `check-project` → `project-state` →
  `preload-namespaces` → `(require ns-sym)` loads project namespaces into **Skeptic's** JVM at
  runtime. Hermetic only about launch classpath; contaminates at runtime, one layer below where
  flags wire in.

### `project-state` (`pipeline.clj:1003-1061` at HEAD `3333aaa`) — project-world contact points (re-read post-R1)
1. `preload-namespaces` (`:1004-1005`) — `(require)` per ns.
2. `project-discovery` (`:1009`).
3. `project-var-provs` → `ns-var-provs` (`:1010`) — `(ns-resolve (the-ns …) sym)` on **live Vars**
   for BOTH schema and malli provs. (Re-confirm the `ns-var-provs` fn line range — it drifted.)
4. **form-refs** (`:1011-1013`) — R1: now `(reduce … (merge (form-refs-for-ns d)) {} project-disc)`,
   producing a `{qsym→form}` value. No more `IdentityHashMap`, no more live-Var keys.
5. `collect-user-fn-summaries` (`:1014`) — works on discovery forms; already worker-friendly.
6. `preload-cljs-state!` (`:1016-1018`).
7. `namespace-dict` (`:1025-1026`) → `clj-namespace-dict` does a **SECOND** `(require ns-sym)`,
   binds `*ns*`, threads `var-provs`+`form-refs` into typed-decls. (Re-confirm `clj-namespace-dict`
   line range — it drifted.)
8. `collect-accessor-summaries-for-ns` (`:1045`) — runs the **analyzer eagerly per ns**,
   pre-barrier, host-side today. NOT the lazy per-form path; the two must coexist.
9. `->ProjectState` (`:1060-1061`) — carries `var-provs` as a field. **R1 removed the `form-refs`
   field**; the local `form-refs` binding is now consumed only by the `namespace-dict` calls, not
   stored on ProjectState.

### `form-refs` threads through the bridge — host-pure as of R1
- HANDOFF originally recorded `lookup-form-ref` doing `(.get form-refs v)` on a **live
  `clojure.lang.Var`** inside the host `schema->type` chain, and called for replacing the
  `Var→form` IdentityHashMap with a qsym→form map. **R1 did exactly that.** At HEAD,
  `lookup-form-ref [ctx qsym]` does `(get form-refs qsym)` (`bridge.clj`, current), the writer
  `form-refs-for-ns` keys by qsym, and `convert-desc` looks up by qsym — no live Var touches the
  map. The map is now a plain `{qsym→form}` value, already wire-safe (qsyms are EDN symbols).
- **What R2–R5 still owes (§3 #4):** the worker must be the one that EMITS this `{qsym→form}` map
  (from `discovery/discover` source forms) and ships it to the host; today the host builds it
  itself in `project-state`. Re-trace the host consumption end-to-end now that the key is a qsym.

### Plumatic admission consumes a LIVE Schema object (probe-captured)
- `collect.clj:207-219` (`admit-var`) reads `(:schema (meta v))`; `fn-schema-desc` (`:142-148`)
  does `(into {} schema)` + `:input-schemas`/`:output-schema` — works only on a live
  `schema.core/FnSchema`. `ns-schema-results` (`:234-241`) = `(require)` + `discovery/discover`.
- **PROBE-A (captured, exit 0):** for `(s/defn f :- s/Int [x :- s/Int] …)`, `(:schema (meta #'f))`
  → class `schema.core.FnSchema`, value `(=> Int Int)`. The schema is a live object born in the
  worker. The worker must emit **source forms** (via `discovery/discover`); host re-interprets
  via `schema->type`. (This resolves the long-open "what does `(:schema m)` hold" question that
  Session 1 was terminated for never reading: it is a live object, so the boundary primitive is
  the recovered source form, not the object.)

### Malli admission (read)
- `malli_spec/collect.clj`: `registry-entries` (`:26-31`) reads live Malli objects from
  `(m/function-schemas)` + `(ns-resolve …) meta`; `var-meta-entries` (`:33-39`) reads
  `(:malli/schema (meta v))` via `ns-interns`; `malli-admitted-qsyms` (`:66-79`) repeats the
  live-Var work and feeds `ns-var-provs` (`pipeline.clj:850`).
- `malli_spec/bridge.clj:332-337` (`malli-spec->type`) takes a value → `admit-malli-spec`
  (`m/form`-based), accepts an object OR an EDN form. Worker `m/form` → host `malli-spec->type`
  is feasible. Malli forms (`[:=> [:cat :int] :int]`) are already EDN. Interpretation is HOST
  work with Skeptic's malli, same as Plumatic.

### AST projection (read + PROBE-B)
- `prune.clj:21-46` — `prune-fields` is a **BLOCKLIST**: `(dissoc n :env)`, `:fn` name unwrap,
  `:info` reduce-on-`:var`/drop-else; **everything else preserved**; `project-node` recurses
  `(:children n)`. The committed `prune_test.clj` *pins* the blocklist (asserts `:val 42`,
  `:type`, `:origin`… survive). A whitelist rewrite is a behavior change and was forbidden.
- **PROBE-B (captured):** real JVM nodes carry non-EDN leaves the blocklist keeps: `:o-tag`
  (Class) on every node, `:atom` (mutable `clojure.lang.Atom`) on bindings/locals,
  `:tag`/`:return-tag` (Class).
- A `/tmp` grep found **zero src readers** of the analyzer's `:o-tag` and `:atom` fields (the
  `:atom` hits in `sum_types.clj`/`origin.clj` are an unrelated data model). So "preserve
  current behavior" = preserve the keys **consumers read** (which excludes those two), NOT the
  raw key set the analyzer emits. This distinction is the user's "current behavior is the
  contract" ruling.

### The class crux — R0 made `:class` a NAME string; the worker-oracle substitution is STILL OPEN
HANDOFF originally recorded that GroundT/PredInfo held **live `java.lang.Class` objects** and
that the foundation phase was to make `:class` a NAME. **R0 (chain-1) did exactly that and is
committed.** At current HEAD (`3333aaa`), `:class` is a class-name string, and consumers
resolve it to a live Class **at the use site** via `at/ground-class` (`types.clj:20-27`):

```clojure
;; types.clj:20-27 (current HEAD)
(defn ground-class
  "Resolve a ground/pred-info :class field (a class-name string) to a live java.lang.Class.
   nil -> nil. Host-only resolution via Class/forName (single-JVM: identical Class per probe F1)."
  [v]
  (cond
    (nil? v) nil
    (string? v) (try (Class/forName v) (catch ClassNotFoundException _ nil))
    :else nil))
```

The consumer sites HANDOFF listed are all migrated to name-resolution (verified at HEAD):
- `value_check.clj:132` — `(instance? (at/ground-class (:class ground)) value)` (was `(:class ground)`).
- `value_check.clj:162,165,170` — `(= Number (at/ground-class (:class t)))` / `(= Object …)`.
- `value_check.clj:173-174` — `.isAssignableFrom ^Class (at/ground-class (:class s)) ^Class (at/ground-class (:class t))` both directions.
- `value_check.clj:85-89` — `numeric-ground-class` resolves via `at/ground-class`; the live-Class
  `integral-ground-classes` set is gone — `at/integral-class-names` (name strings, `types.clj:29-31`).
- `narrowing.clj`, `calls.clj`, `origin.clj`, `predicate_descriptor.clj`, `pipeline.clj`,
  `data.clj` — all migrated in R0 (see chain-1 commits `03bf148`/`8e1c31f`).

**What R0 did NOT do — this is the R2–R5 core, still entirely open:** `at/ground-class` resolves
a name to a live Class via **`Class/forName` on the HOST classpath**. That only works because
host and project share one JVM today. In the two-JVM split the host classpath does NOT contain
project classes, so `Class/forName` fails for any project class, and the `.isAssignableFrom` /
`instance?` calls at the sites above cannot run host-side. **The worker oracle is the open
substitution:** for a project-class name the host cannot resolve, the subtype/instance question
must be sent to the live worker (which holds the project classes) and answered on demand. R0
made `:class` a name (the precondition); routing the unresolvable-name decisions to the worker
oracle is unbuilt. INVESTIGATION (§3 #1) must trace, per site, where the host obtains the
name(s) to send and what it does with the returned boolean — for `value_check.clj:132` (instance),
`:173-174` (subtype both directions), `narrowing.clj` `instance-ground-assignable?` /
`numeric-dyn-instance-classification` (`pred-class`), and confirm the host-resolvable cases
(`java.lang.*`, Skeptic-known classes) still resolve locally with byte-identical results.

### Classpath discovery (read)
- deps.edn: `cli/paths.clj:24-26` (`classpath-entries`) = `(keys (:classpath (create-basis root
  aliases)))` — full classpath, available today (`tools.deps` is a dep).
- Lein: `paths.clj` docstring (`:1-5`) — lein gets paths from the project map; **NO existing
  Skeptic fn yields a classpath string for lein.** `leiningen.core.classpath/get-classpath` is
  lein's API for this — **NOT verified; probe before use.**

### Transport — nREPL (PROBE-NREPL captured, exit 0)
- `nrepl 1.3.1` is in local `.m2`, compatible with Clojure 1.12 (`project.clj:16`). (A prior
  plan's `1.1.1` pin was fabricated and isn't even cached.) **nREPL is not yet a declared dep.**
- Captured round-trip: `nrepl.server/start-server :port 0 :transport-fn nrepl.transport/edn
  :handler (server/default-handler #'mw)` → `nrepl.server.Server`, autoselected port. Custom op
  via `nrepl.middleware/set-descriptor!` `{:requires #{} :expects #{} :handles {"<op>" {}}}`,
  reply via `nrepl.transport/send` `{:id … :status #{:done} …}`. Client: `nrepl.core/connect
  :port :transport-fn nrepl.transport/edn` → `nrepl.core/client conn timeout` →
  `nrepl.core/message client {:op …}`; `(first …)` is the reply. **EDN survives the wire** (a
  Boolean returned a Boolean).

---

## 3. What must be INVESTIGATED before any R2–R5 plan (NOT yet verified — do not trust prior assertions)

### CLOSED by R0/R1 (do not re-open)
- ~~**GroundT-class-name representation change**~~ — **DONE in R0** (chain-1). `:class` is a name
  string; consumers resolve via `at/ground-class`. Both `integral-ground-classes` sets collapsed
  to `at/integral-class-names` (name strings). See §2 "class crux" for the landed state.
- ~~**`form-refs` Var→qsym-form refactor**~~ — **DONE in R1.** The map is `{qsym→form}`; the dead
  `:form-refs` ProjectState field is removed.

### STILL OPEN — the R2–R5 unit (re-confirm every line number at HEAD `3333aaa`)
1. **Worker oracle for class-subtype/instance.** R0 made `:class` a name, but `at/ground-class`
   still resolves via host `Class/forName` (works only single-JVM). For project classes the host
   cannot resolve, the decision must go to the live worker. For EACH site read the surrounding fn
   and state the exact edit AND where the host gets the name(s) to send and what it does with the
   returned bool: `value_check.clj:132` (instance), `:162-174` (`= Number`/`= Object`/
   `.isAssignableFrom` both directions), `narrowing.clj` `instance-ground-assignable?` /
   `numeric-dyn-instance-classification` (the `pred-class` path), `calls.clj` node-class equality.
   Host-resolvable cases (`java.lang.*`, Skeptic-known) must keep resolving LOCALLY, byte-identical.
2. **Zero-behavior-change in single-JVM.** The oracle's host-side default (when the worker is the
   same JVM / classes are host-resolvable) must reproduce `.isAssignableFrom` / `instance?`
   byte-identically — *proven* by reading every consumer, not assumed.
3. **Lein classpath:** probe `leiningen.core.classpath/get-classpath` against a real lein project
   map before depending on it (deps.edn path `cli/paths.clj` `classpath-entries` already works).
4. **Worker emitting Plumatic source forms:** confirm `discovery/discover` yields the `:- T`
   forms the host needs and that `schema->type` consumes them via the qsym-form map (now that R1
   landed the qsym-keyed map, re-trace the host side end-to-end).
5. **`collect-accessor-summaries-for-ns`** (eager analyzer, pre-barrier) — how it runs in the
   worker, what projected ASTs it returns; it coexists with the lazy per-form path.
6. **`.cljc` double-pass** and **worker-thrown project exception → finding EDN**
   (`declaration-error-result` `collect.clj:194-196` carries `:exception-data (ex-data e)` +
   `:rejected-schema`, possibly live objects — sanitize to EDN at the catch).
7. **AST projection / `prune.clj`** — PROBE-B (§2) found the blocklist keeps non-EDN leaves
   (`:o-tag` Class, `:atom` Atom, `:tag` Class). Re-confirm at HEAD which keys consumers actually
   read, so the worker's emitted EDN is the "consumers read" subset, not the raw analyzer keyset.
8. **Process spawn machinery** (ProcessBuilder, reading the worker's port line, lifecycle/teardown)
   — no precedent in the repo; design and verify it.

### Known dead end (do not re-enter)
On-disk schema versions differ in record inventory (1.2.0 has records absent from 1.4.1). The
Session-1 agent spiraled on this as a "version-mismatch threat." **It is moot** once schema
interpretation is host-only on forms with Skeptic's pinned 1.4.1 — the project's schema version
is as irrelevant as its logging library. Do not re-investigate it.

---

## 4. Gates (project `AGENTS.md`) — every phase passes ALL three
1. `lein test` — zero failures.
2. `clj-kondo --lint src test` — zero warnings.
3. Skeptic on self: `../script/install-local.sh` then
   `lein with-profile +skeptic-plugin skeptic -p` — zero findings. Use `-p` (JSONL); never grep
   the ANSI report.

Acceptance projects (run on the USER's machine — the Cheshire load failure at
`cheshire/factory.clj:86:9` that motivates this work reproduces there, not in this env):
```
cd ~/Code/malli-checks/aave   && clj -T:skeptic check    # metosin/malli
cd ~/Code/malli               && clj -T:skeptic check    # prismatic/schema
cd ~/Code/malli-checks/reitit && clj -T:skeptic check    # metosin/malli
```

Probes: NO ad-hoc scripts / REPL one-liners (CLAUDE.md). Probe via a throwaway `deftest` run
under `lein test`, capture output to a file and Read it, then DELETE the scaffolding and confirm
the tree is clean. Reverts go through Edit, never `git checkout`.
