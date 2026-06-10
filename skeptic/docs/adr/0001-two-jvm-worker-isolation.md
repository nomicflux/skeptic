# ADR 0001 — Two JVMs: host runs Skeptic, worker runs the analyzers in the project's runtime

- **Status:** Accepted (shipped)
- **Recorded:** 2026-06-09

## Context

Skeptic type-checks a target project from its Plumatic Schema and Malli
annotations. Checking requires analyzing project source with the real
analyzers — `clojure.tools.analyzer.jvm/analyze` and
`cljs.analyzer.api/analyze` — and analysis is not a syntactic act:
macroexpansion invokes the project's macro functions as live code
(`tools.analyzer.jvm` 1.4.0-beta1, `clojure/tools/analyzer/jvm.clj:226`),
reading consults the live data-reader registries, and loading a namespace
executes project code.

Running analysis in Skeptic's own process collided Skeptic's pinned
libraries with the project's. The malli-checks corpus recorded 44
exceptions across 4 of 11 projects from exactly this class of divergence
(`skeptic/.scratch/malli-checks-runs/HANDOFF.md`).

## Decision

Two JVMs with a strict domain boundary:

- **Host JVM** — the process the user invokes (`lein skeptic`,
  `clj -T:skeptic check`). Runs everything Skeptic-proper: discovery,
  admission (`schema->type`, `malli-spec->type`), the checking pipeline,
  the cast engine, blame, output. Skeptic's libraries (Plumatic Schema,
  Malli, etc.) are host-only and run at Skeptic's pinned versions.
- **Worker JVM** — runs in the **project's** runtime. Loads project
  namespaces, reads project source by path, runs the analyzers, projects
  ASTs over the wire. No `skeptic.*` analysis namespace, no schema/malli
  evaluation; Var metadata (including `m/=>` registrations and `mx/defn`
  annotations) crosses the wire as inert data and is admitted on the host.

The worker has zero analysis logic of its own; it **calls** the analyzer
libraries. Skeptic resolves its own pinned analyzer versions only as a
fallback so projects that ship no analyzer still analyze; the project's
classpath supplies the version whenever the project pins one (ADR 0002).

## Consequences

- The project's pinned versions of Clojure, Schema, Malli, tools.analyzer
  drive the analyzer; Skeptic's declared versions no longer collide.
- The symmetric guarantee: Skeptic's checking machinery runs on versions
  the project cannot influence.
- Any schema/malli evaluation on the worker would run Skeptic's semantics
  on project-controlled library versions — the same bug class, on the
  checking side. Hence the rule is absolute: Skeptic, schema, malli, and
  admission never run on the worker.
- The boundary has no static enforcement mechanism; it is maintained by
  the derived worker-deps set (ADR 0002), code discipline (dynamic nrepl,
  `ns-interns`, dependency-light worker source), and review.

## Sources

- `skeptic/AGENTS.md` — "Worker Runtime Isolation".
- `skeptic/.scratch/malli-checks-runs/HANDOFF.md` — cluster catalog, §A.
- `.scratch/worker-runtime-handoff.md` §1–§3.
