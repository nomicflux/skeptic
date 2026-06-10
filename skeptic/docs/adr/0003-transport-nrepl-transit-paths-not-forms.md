# ADR 0003 — Transport: nREPL + Transit, paths over the wire, metadata as data

- **Status:** Accepted (shipped)
- **Recorded:** 2026-06-09

## Context

- **Nippy (rejected).** The original transport's nippy brought
  `taoensso.truss` as a transitive; `taoensso.truss.impl` interned at
  worker boot from truss-1.11.0 while snoop pinned truss-1.6.0
  (malli-checks cluster 4). Skeptic's transport occupied namespace space
  a project could pin — a §440 violation (ADR 0002).
- **A form round-trip op (removed, must not return).** An op that took a
  form on the host, shipped it to the worker, and shipped back an AST
  reintroduces a reader/runtime divergence: the host would read project
  source under host registries.
- The wire strips form metadata, and blame/source location are read from
  metadata, not AST structure.

## Decision

- **nREPL over Transit+msgpack with an explicit `shutdown` op.**
  transit-clj ships one Clojure namespace whose only Clojure transitive
  is `clojure.string`; its Java dependencies ship zero Clojure namespaces
  (malli-checks-exceptions-v2.md §Change 1) — the transport no longer
  occupies pinnable namespace space.
- **nREPL vars resolve dynamically inside `start!`** so a project pinning
  a different nrepl wins by first-occurrence and the worker serves with
  whichever version interned.
- **The host sends paths, never forms.** The bulk `analyze-namespace` op
  opens the file inside the worker; the worker reads its own source.
- **Form metadata crosses as plain data** and is re-materialized
  host-side: `clojure.walk/postwalk` for clj ASTs, `:children`-only
  descent for cljs ASTs (a full walk of a cljs AST runs away into
  `:env`/`:init` back-refs).
- **The worker resolves vars via `ns-interns`, not `ns-resolve`** —
  `ns-resolve` also resolves classes via `Class/forName`, which hits
  sibling-class collisions on the project classpath.
- **The host owns the worker process:** both child streams drained on
  daemon threads (echoed to host stderr only under `-v`; porcelain stdout
  stays clean); startup waits on the port handshake or process exit with
  no elapsed-time cutoff; teardown sends `shutdown`, waits bounded, then
  terminates the owned process.

## Consequences

- Skeptic's transport cannot collide with project-pinned libraries.
- Blame and location stay correct across the wire because metadata is
  carried explicitly, not assumed to survive serialization.
- Worker chatter can never corrupt machine-readable output.

## Sources

- `skeptic/docs/current-plans/malli-checks-exceptions-v2.md` §Change 1.
- `skeptic/.scratch/malli-checks-runs/HANDOFF.md` — cluster 4.
- `skeptic/AGENTS.md` — "Worker Runtime Isolation".
- `skeptic/src/skeptic/worker/{server,client,transport,wire,process}.clj`.
