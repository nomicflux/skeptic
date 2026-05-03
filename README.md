# Skeptic

[![CI](https://github.com/nomicflux/skeptic/actions/workflows/ci.yml/badge.svg)](https://github.com/nomicflux/skeptic/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.nomicflux/lein-skeptic.svg)](https://clojars.org/org.clojars.nomicflux/lein-skeptic)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.nomicflux/skeptic.svg)](https://clojars.org/org.clojars.nomicflux/skeptic)

Skeptic is a Leiningen plugin that type-checks Clojure projects that use
[Plumatic Schema](https://github.com/plumatic/schema). It reads your source,
infers a type for each expression, and reports places where the inferred type
disagrees with the Plumatic Schema annotation you declared on a function's
inputs or output.

Experimental support for [Malli](https://github.com/metosin/malli) is in
development.

**Versioning:** stable releases use git tags `vX.Y.Z` and are described on
[GitHub Releases](https://github.com/nomicflux/skeptic/releases). The Clojars
badges above reflect the latest published versions.

## What Skeptic checks

- Call sites where inferred argument types do not fit the declared input schema.
- Return values where the inferred result does not fit the declared output
  schema.
- Nilability and structural mismatches that flow into declared schemas.

## Installation

Add the plugin to the `:plugins` vector in your `project.clj`:

```clojure
:plugins [[org.clojars.nomicflux/lein-skeptic "0.8.0"]]
```

```clojure
:plugins [[org.clojars.nomicflux/lein-skeptic "0.8.1-SNAPSHOT"]]
```

If you need to override the default dependency profile, define a `:skeptic`
profile in your project and Skeptic will use that instead.

## Running it

From the project you want to check:

```sh
lein skeptic
```

`lein skeptic` exits with status `0` when no inconsistencies are found and
status `1` when it reports inconsistencies.

Options:

- `-n`, `--namespace NAMESPACE`: only check one namespace.
- `-c`, `--show-context`: print local-variable and reference context for each
  result.
- `-v`, `--verbose`: print extra progress and debugging output.
- `-a`, `--analyzer`: print the analyzer forms for the namespace being checked.
- `-k`, `--keep-empty`: include analyzed expressions even when they produced no
  mismatches.
- `--explain-full`: show fully expanded structural forms in type-mismatch
  output instead of compact declared names.
- `-p`, `--porcelain`: emit machine-readable JSONL (one JSON object per line)
  instead of the default human-readable output. See [Output](#output) below.
- `--profile`: profile the run (CPU, memory, wall-clock time). Long-only.
- `-o`, `--output OUTPUT_FILE`: write Skeptic's output to this file instead of
  stdout, so lein/JVM messages stay on stdout. Works with text and `-p` JSONL
  output.

## Output

### Text (default)

The default output is a human-readable, ANSI-coloured report, one block per
inconsistency, grouped by namespace. Findings include the source of the
reported type, such as Schema, Malli, a built-in/native declaration, a type
override, or inference.

By default, declared Schema, Malli, and type-override names may be used to keep
large structural types compact in reports. Use `--explain-full` to print the
expanded structural form instead.

### JSONL (`-p` / `--porcelain`)

`lein skeptic -p` switches stdout to newline-delimited JSON: one
`ns-discovery-warning` record per non-blocking namespace load failure, one
`finding` record per type mismatch, one `exception` record per namespace-local
failure hit during checking, and always a final `run-summary` line — even on
clean runs. Finding and exception records include a nested `location` object;
`location.source` carries the same source attribution as text output. Exit code
matches text mode (`0` clean, `1` otherwise).

```json
{
  "kind": "run-summary",
  "errored": true,
  "finding_count": 7,
  "exception_count": 1,
  "namespace_count": 12,
  "namespaces_with_findings": 3
}
```

When `--profile` is also set, the profile summary is written to **stderr** so
stdout stays pure JSONL. When `-o` is also set, JSONL still goes to the output
file and the profile summary still goes to stderr.

See [`docs/jsonl-output.md`](docs/jsonl-output.md) for the full per-kind field
spec and the structured type-tag reference.

## Configuration

Skeptic reads optional project-level configuration from `.skeptic/config.edn`
at the project root. The file is EDN and every key is optional.

```clojure
{:exclude-files ["src/fixtures/*.clj"
                 "test/**/*_examples.clj"]
 :type-overrides {clojure.tools.logging/infof {:output (s/eq nil)}}}
```

### `:exclude-files`

Vector of glob patterns matched against each file's path relative to the
project root. Matched files are skipped entirely — their namespaces are never
loaded or checked. Patterns use the platform's `java.nio.file.PathMatcher`
glob syntax (`*`, `**`, `?`, character classes). Excludes apply before `-n` /
`--namespace` selection, so if you exclude a namespace's file and also pass
`-n` for that namespace, the run checks nothing.

### `:type-overrides`

Map from fully-qualified symbol to an override map with any of `:schema`,
`:output`, `:arglists`. Values are Plumatic Schema expressions evaluated with
`[schema.core :as s]` in scope, so you can write `(s/eq nil)`, `s/Int`, etc.
Overrides replace whatever Skeptic would otherwise infer or collect for that
symbol at call sites. Typical use: silence noise from variadic logging or
side-effecting functions whose declared schemas are unhelpful.

```clojure
{:type-overrides {clojure.tools.logging/infof {:output (s/eq nil)}}}
```

After this, call sites of `infof` are checked as returning `nil`.

## Experimental Malli support

Skeptic can read simple Malli function declarations from `:malli/schema` var
metadata:

```clojure
(defn takes-int
  {:malli/schema [:=> [:cat :int] :string]}
  [x]
  (str x))
```

Full Malli support is in progress. Current useful forms include
`[:=> [:cat ...] out]`, primitive leaves such as `:int`, `:string`,
`:keyword`, `:boolean`, and `:any`, plus `:maybe`, `:or`, `:enum`, and bare
predicate symbols that Skeptic recognizes.

Broader Malli forms are still experimental. Unsupported forms are admitted when
Malli accepts them; their Skeptic type is currently dynamic.

## Suppressing checks

Skeptic provides three opt-out mechanisms for when its inference is wrong or
too dynamic. Each suppresses checks in a different scope without affecting the
rest of your code.

### Ignoring a function body

Use `:skeptic/ignore-body` in a function's attribute map to skip checks inside
the function body. The declared schema still applies to all callers:

```clojure
(s/defn my-fn :- s/Int
  {:skeptic/ignore-body true}
  [x :- s/Int]
  (int-add nil x))
```

The body's internal mismatch (passing `nil` to `int-add`) is suppressed.
Callers are still checked against the declared `:- s/Int` schema.

### Treating a function as a black box

Use `:skeptic/opaque` in a function's attribute map to exclude both the
function body and its schema from checking. Callers see the function as
accepting and returning `s/Any`:

```clojure
(s/defn my-fn :- s/Int
  {:skeptic/opaque true}
  [x :- s/Int]
  "not-an-int")
```

The body is not checked, and callers can pass any type and expect any type
back.

### Overriding an expression's type

Use `^{:skeptic/type T}` metadata on an expression to pin its inferred type to
schema `T`:

```clojure
(let [y ^{:skeptic/type s/Int} (some-call-that-returns-any)]
  (int-add y 1))
```

The expression's type is treated as `s/Int` for subsequent checks. Clojure
does not allow metadata on bare literal values (numbers, strings, keywords);
wrap them in a form if needed:

```clojure
^{:skeptic/type s/Int} (identity 42)
```

## How it works

Skeptic loads the namespaces in your project, collects the Plumatic Schema
annotations you've written on vars and functions, infers a type for each
expression in your code, and compares the inferred types against the declared
schemas on function inputs and outputs. Each mismatch is reported with a
source location, the inferred type, and the expected type.

During `lein skeptic`, the checker runs with Plumatic Schema function
validation disabled around the analysis pass. Projects that enable runtime
Schema validation still keep that behavior for their own code; Skeptic simply
avoids paying that validation cost while it is inspecting the project.

## Attribution

Skeptic's cast semantics — how directional checks between inferred and
declared types assign responsibility for a mismatch — are informed by the
polymorphic blame calculus and runtime cast algorithm from:

Amal Ahmed, Robert Bruce Findler, Jeremy G. Siek, and Philip Wadler.
**Blame for All.** In *Proceedings of the 38th ACM SIGPLAN-SIGACT Symposium on
Principles of Programming Languages (POPL)*, Austin, TX, USA, January 2011.
ACM. [https://doi.org/10.1145/1926385.1926409](https://doi.org/10.1145/1926385.1926409)

## When to use it

Skeptic is most useful when you already annotate your code with Plumatic
Schema and want feedback about how inferred types line up with those declared
annotations. It complements runtime Plumatic Schema validation; it does not
replace it.

## Building from source

To run an unreleased version of the plugin from a local checkout:

1. In `lein-skeptic/`, run `lein install` to publish the plugin to your local
   Maven repository.
2. If you've also changed the checker library, run `lein install` in
   `skeptic/` first so the plugin resolves your build.
3. Add the coordinates and version from `lein-skeptic/project.clj` to the
   target project's `:plugins` vector.
4. Optionally run `lein test` from `skeptic/` to sanity-check your build.

## License

The MIT License (MIT)
