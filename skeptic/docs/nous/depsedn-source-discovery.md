# deps.edn source-file discovery

## What this is

On the deps.edn tool path (`clj -T:skeptic check`), source-file
discovery decides **eligibility once per discovered file** before
anything is sent to the worker. A `.clj` / `.cljc` / `.cljs` file is
sent iff every dep symbol in its `(ns ...)` form's `:require` /
`:require-macros` / `:use` / `:use-macros` clauses is either:

1. a namespace defined by another in-project source file, or
2. resolvable as a classpath resource against the basis classpath
   (which `tools.deps/create-basis` populates with both `:paths`
   directories and the resolved dep JARs).

A file with any unresolvable dep is **rejected** — never reaches the
worker — and produces a `ns-discovery-warning` JSONL line. Rejection
is **transitive**: if file G is rejected, every file F whose
project-internal `:require` closure reaches G is also rejected.

## Why

Without eligibility filtering on the deps.edn path, the worker is
asked to `(require)` files whose top-level requires the basis cannot
satisfy. Two failure modes resulted:

- The worker crashed with `FileNotFoundException` for the missing
  namespace (e.g. `clojure/core/async__init.class`).
- An earlier code path silently dropped these files from the input
  set, so the user never learned which files weren't checked.

The fix puts both opt-shapes (`:alias` and `:paths`) through the same
filter against the **same** alias-selected basis, so the same input
produces the same outcome regardless of which option the user typed.

## How it is wired

- `skeptic.cli.paths/project-context` builds the basis (via
  `tools.deps/create-basis`), discovers files under
  `source-paths-from-basis` (or the explicit `:source-paths-override`
  when `:paths` was supplied), and returns `:source-files` (eligible)
  + `:source-discovery-failures` (rejection records).
- `skeptic.cli.main/with-selected-source-scope` installs both into
  opts unconditionally when a deps.edn is present (the pre-fix
  `(not (paths-override? opts))` gate is gone).
- `skeptic.core/check-project` iterates `:source-discovery-failures`
  and calls `discovery-warn` on the active printer; the porcelain
  printer emits one JSONL line per record with `:kind
  "ns-discovery-warning"`, `:path`, `:message`, and (when the
  rejection was eligibility-based) `:unresolvable_deps`.

## Lein path

The Leiningen plugin path uses lein's own source-path resolution and
does not run this filter. Lein already discovers and dedupes files
through `:source-paths` + `:test-paths`; missing-dep behavior on the
lein side surfaces through the worker's existing exception channel.

## Related Nous facets

- `depsedn-ns-dep-extraction` — reading the `(ns ...)` form,
  enumerating dep symbols.
- `depsedn-eligibility-probe` — the URLClassLoader-against-basis
  decision.
- `depsedn-rejection-emission` — the JSONL line shape, transitive
  closure, no-silent-drop guarantee.
