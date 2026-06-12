# ADR 0004 — Load the project as the project, not as a reconstruction of it

- **Status:** Accepted (shipped)
- **Recorded:** 2026-06-09
- **Supersedes:** the reader-registration fold + dedicated operation
  thread (described in `.scratch/worker-runtime-handoff.md` §2.4, now
  historical)

## Context

Registered data readers are runtime state. Piecemeal reconstruction of
that state kept missing registration modes:

- Data readers registered by project profiles/injections (e.g.
  `(set! clojure.core/*default-data-reader-fn* …)` in `:dev :injections`)
  were not live during analysis.
- The first fix generation — `fold-reader-registrations!` merging the
  launch thread's reader bindings into Var roots, plus a dedicated
  operation thread that conveyed every launch-thread binding *except*
  the two reader Vars — was itself a reconstruction: hand-tuned Var
  bindings modeling what Clojure's loading "should" do. It carried parity
  edges (a top-level `(set! *data-readers* …)` in project source would
  throw on the operation thread even though it works under plain
  `clojure.main`), the same shape of risk one level deeper.

The recurring failure shape, across readers, resources, and dependencies:
a fix that reconstructs part of the project environment fails on the
next project that exercises the part not reconstructed. The governing
rule, verbatim: **"WE LOAD THE PROJECT AS THE PROJECT. NOT AS OUR
RECONSTRUCTION OF THE PROJECT."** It applies to the entire loading
process with no sub-scope. Do not form a model of Clojure's loading
semantics and conform code to it; the project's own `clojure.main`
behavior, observed by running it on fixtures, is the spec
(`.scratch/reader-probes/`).

## Decision

- **Project initialization actually runs.** The Lein entrypoint launches
  from lein's prepared project JVM command: prep (`:prep-tasks`,
  javac/compile), profiles, `:global-vars`, `:injections`, jvm-opts,
  environment — lein's work, not Skeptic's reimplementation of it.
- **Every project operation runs on the `clojure.main` launch thread
  itself.** `run-worker!` installs a queue and runs the project-operation
  loop on the launch thread the project's own boot set up; nREPL handler
  threads enqueue onto it (`server.clj` `run-worker!`,
  `run-project-operation-loop!`). The fold and the dedicated operation
  thread are deleted. Skeptic adds no reader-Var machinery — no fold, no
  merges — so every registration mechanism (`data_readers.clj`, injection
  `set!`s, `alter-var-root`, registrations made mid-`require`) behaves
  exactly as under the project's own `clojure.main` boot.
- **Tests are specified to observed runtime behavior**, captured by
  running real `clojure.main` on fixtures (`.scratch/reader-probes/`),
  never to a model of what loading should do.
- **No Skeptic-added loading behavior.** The project's runtime never
  retries a failed `require`; `require-with-reload-retry` (formerly in
  `skeptic/src/skeptic/worker/analyzer_clj.clj`, retrying with
  `:reload-all`) could make the worker succeed where the project fails
  and was deleted under this rule. The whole worker load path is swept
  with the single test: *does the project's own `clojure.main` do this?*
  Every other candidate passed the sweep: the tools.reader second read
  inherits the live Clojure reader registries (faithful, not added
  behavior); the `s/defn` normalization, analyzer passes-opts, and cljs
  analyzer bindings shape analysis of already-loaded code, not loading;
  the remaining `require`/`requiring-resolve` call sites are plain lazy
  loads governed by the project-first classpath.

## Amendment — 2026-06-12: the loader itself swept under the rule

The capturing loader — two readers walking the file in lockstep with an
eval between reads, under hand-pushed `Compiler.load` thread bindings —
was itself a reconstruction of Clojure's load, and it carried the
reconstruction failure shape: the host selected a namespace's defining
file by directory-walk order (last-wins `ns → file` map), so a namespace
provided by two files (`.clj` alongside `.cljc`) was evaluated from the
file the project never loads, and every consumer compiled against it
(`:read`-phase `var: #'x is not public` on a repo green under its own
tests). Both are deleted.

Loading a checked namespace is now only ever the project's own
`(require ns)`: the classpath decides which file defines the namespace
(AOT class when strictly newer, else `.clj`, else `.cljc`), exactly as
the project's boot does. Capture is reading, never loading: the source
text is re-read without evaluation after the require. A tagged literal
whose tag no live registration resolves at re-read time (a mid-file
`set!` registration is local to the load's own binding frame —
probe4/probe5) reads as its `tagged-literal` placeholder: an unknown
value typed Dyn, never an error on a file the project loads fine. A file
the project's own load rejects fails inside require and surfaces as that
namespace's finding. Host discovery never collapses one namespace to one
file; a file the loaded runtime's var `:file` metadata says is not the
namespace's definition is reported as an `ns-discovery-warning`, not
checked as text the project never loads
(`.scratch/loader-probes/probe1.clj`).

## Consequences

- Reader registration coverage is derived from the runtime, not
  enumerated: there is no per-mechanism code whose omissions become the
  next incident.
- The observed-behavior spec means fixture probes are the verification
  instrument; harness reader tests assert what `clojure.main` actually
  does.
- Anything Skeptic's worker does during loading that plain `clojure.main`
  would not do is a defect by definition, even when it appears to help.

## Sources

- `.scratch/worker-runtime-handoff.md` §1 (incident history), §2.4
  (superseded fold design), §4 (parity-edge risk that motivated
  superseding it).
- `skeptic/src/skeptic/worker/server.clj` — `run-worker!` docstring and
  the launch-thread loop.
- `skeptic/test/skeptic/worker/harness_test.clj` — reader-registration
  regression tests; `.scratch/reader-probes/` — captured `clojure.main`
  behavior.
- `skeptic/AGENTS.md` — "Worker Runtime Isolation", "Project code loads
  as the project."
