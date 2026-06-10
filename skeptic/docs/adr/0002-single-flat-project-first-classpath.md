# ADR 0002 — One classloader, one flat launch classpath, project-first

- **Status:** Accepted (shipped)
- **Recorded:** 2026-06-09

## Context

Three rejected generations preceded this design, each a recorded failure:

1. **Proxy/multi-loader generation (rejected).** Runtime `-cp` plus a
   project-first proxy classloader. Multi-loader Clojure is structurally
   broken for the analyzer payload: with `Compiler/LOADER` bound to a proxy
   loader at first load, `defprotocol` synthesizes its interface under one
   loader and `deftype` compiles under another — same FQN, two `Class`
   objects, dispatch fails ("`JavaReflector implements Reflector interface?
   false`", `PHASE_4_5_HANDOFF.md:18-38`). The affected family —
   `clojure.reflect`, `cljs.analyzer`, `tools.analyzer.jvm.utils` — is the
   analyzer payload itself. The proxy loader had looked sound under probes
   that exercised resource resolution but not protocol dispatch; a mechanism
   is only as validated as the failure mode its probe actually exercises.
2. **Hand-curated dependency lists (rejected).** Completed three plan phases
   and still leaked transitives; "agents were once again just playing
   whack-a-mole with individual dependencies"
   (`PHASE_4_5_HANDOFF.md:110-113`). Lists that must enumerate a dependency
   closure by hand are permanently behind it.
3. **Dependency mediation into the project graph (rejected).** Commit
   `9d7671c8` merged Skeptic's coordinate plus the project's `:plugins`
   into the analyzed project's `:dependencies`. Mediation blended Skeptic's
   graph into the project's: resolved versions became a function of the
   project's tree, exclusions, and the machine's m2 cache. Observed
   failure: code that loaded on one machine produced syntax errors on
   another. The project's dependency resolution *defines* the runtime
   being analyzed; entering it changes the thing being measured.

The four malli-checks exception clusters root-caused to one invariant
(HANDOFF.md §440): **the worker JVM must not intern any namespace under
runtime-cp whose project version the project may pin differently.**
Clusters 2 (reitit: `cljs.analyzer` boot-interned from Skeptic's
clojurescript 1.11.132 vs the project's 1.12.134) and 4 (snoop:
`taoensso.truss.impl` boot-interned from Skeptic's transitive
truss-1.11.0 vs the project's 1.6.0) were boot-interning from Skeptic's
runtime entries.

## Decision

- **One classloader.** Loading and analysis go through the same paths; no
  second loader, no proxy.
- **One flat launch classpath:** `(concat project-cp runtime-entries)`
  built by `skeptic.worker.classpath/worker-classpath-entries`, project
  entries first. First-occurrence wins, so anything the project ships
  interns from the project's jars; Skeptic's entries contribute only what
  the project lacks. A symbol present in both Clojures is a wrong-jar bug
  (classpath ordering failure), never a version mismatch.
- **Skeptic's coordinates never enter the project's `:dependencies`.**
  The worker runtime set is resolved separately by each entrypoint's own
  build system: tools.deps for deps.edn, lein's aether wrapper for Lein
  (against a synthetic project). The Lein path additionally appends the
  project's `:plugins` jars resolved as dependencies, because lein's
  `get-classpath` walks `:dependencies` only and projects declare
  cljs-side libraries under `:plugins` (reitit's `lein-doo` →
  `doo.runner`, cluster 3).
- **The worker runtime set is Skeptic-owned, derived data:** ten root
  coordinates in `skeptic.worker.deps/worker-deps`, the probe-captured
  transitive require closure of `skeptic.worker.*`
  (malli-checks-exceptions-v3.md F1). Transitives resolve fresh per build
  system, so version bumps propagate without hand-listing.

## Consequences

- Analysis fidelity: the project's versions are the interned ones. The
  JVM offers no second namespace registry, so position and the derived
  set are the only lever that exists.
- Skeptic's worker machinery executes on versions it does not control —
  including Clojure itself. The worker source's compatibility floor is
  undefined; an incompatible pin fails deterministically at worker boot
  with captured child output, but it is a runtime discovery.
- Nothing detects a new §440 violation statically; a new worker
  dependency with a project-pinnable Clojure namespace reintroduces the
  cluster-4 failure class. Discovery is a corpus run or a user repo.
- The Lein `-classpath` argument replacement is verified against lein
  2.9.7's `shell-command`; a changed emitted shape degrades to a
  diagnosable startup failure.

## Sources

- `skeptic/.scratch/malli-checks-runs/HANDOFF.md` — §A, §440.
- `skeptic/.scratch/malli-checks-runs/PHASE_4_5_HANDOFF.md` — multi-loader
  falsification; whack-a-mole rejection.
- `skeptic/docs/current-plans/malli-checks-exceptions-v3.md` — F1/F2
  probes, the structural fix.
- `.scratch/worker-runtime-handoff.md` §1–§3, §5.
- `skeptic/src/skeptic/worker/{classpath,deps}.clj`,
  `lein-skeptic/src/leiningen/skeptic.clj`.
