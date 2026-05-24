# Skeptic

[![CI](https://github.com/nomicflux/skeptic/actions/workflows/ci.yml/badge.svg)](https://github.com/nomicflux/skeptic/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.nomicflux/lein-skeptic.svg)](https://clojars.org/org.clojars.nomicflux/lein-skeptic)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.nomicflux/skeptic.svg)](https://clojars.org/org.clojars.nomicflux/skeptic)

Skeptic is a Leiningen plugin that statically type-checks Clojure projects based on
[Plumatic Schema](https://github.com/plumatic/schema) annotations. 

Experimental support for [Malli](https://github.com/metosin/malli) is in
development.

## What Skeptic checks

- Call sites where inferred argument types do not fit the declared input schema:

  ```clojure
  (s/defn inc-int :- s/Int
    [x :- s/Int]
    (+ x 1))

  (inc-int "1")  ; String flows into an s/Int parameter
  ```

- Return values where the inferred result does not fit the declared output schema:

  ```clojure
  (s/defn as-int :- s/Int
    [x :- s/Int]
    (str x))  ; body returns String, not s/Int
  ```

- Nilability and structural mismatches that flow into declared schemas:

  ```clojure
  (s/defn inc-int :- s/Int
    [x :- s/Int]
    (+ x 1))

  (s/defn caller
    [m :- {:n (s/maybe s/Int)}]
    (inc-int (:n m)))  ; (:n m) is (maybe s/Int); nil case flows into s/Int
  ```

## Installation

### Leiningen

Add the plugin to the `:plugins` vector in your `project.clj`:

```clojure
:plugins [[org.clojars.nomicflux/lein-skeptic "0.8.1"]]
```

Or for the snapshot version:

```clojure
:plugins [[org.clojars.nomicflux/lein-skeptic "0.9.0-rc4"]]
```

### deps.edn / Clojure CLI

Add a tool alias to your `deps.edn`:

```clojure
{:aliases
 {:skeptic
  {:deps {org.clojars.nomicflux/skeptic {:mvn/version "0.9.0-rc4"}}
   :ns-default skeptic.tool}}}
```

Then run it from the project you want to check:

```sh
clj -T:skeptic check
```

`clojure -M:skeptic` is not supported for hermetic deps.edn execution. `-M`
starts Skeptic on the client project's classpath, which lets client
dependencies become Skeptic implementation dependencies. The `-T:skeptic`
tool-alias entry runs Skeptic on the alias tool stack and reads the client
project as analysis input.

## Running it

From the project you want to check.

With Leiningen:

```sh
lein skeptic
```

With the Clojure CLI:

```sh
clj -T:skeptic check
```

Both invocations exit with status `0` when no inconsistencies are
found and status `1` when they report inconsistencies.

Options:

- `-n`, `--namespace NAMESPACE`: only check the specified namespace.
  Repeatable, and accepts comma-separated values: `-n a.ns -n b.ns` and
  `-n a.ns,b.ns` are equivalent.
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
- `--plumatic-disable`: skip Plumatic Schema intake entirely. No `s/defn` /
  `s/def` / `s/defschema` declarations are admitted, no `:skeptic/type-overrides`
  are applied, and no finding will report `[source: schema]` /
  `"source": "type-override"`. See [Disabling an intake stream](#disabling-an-intake-stream).
- `--malli-disable`: skip Malli intake entirely. No `m/=>`, `mx/defn`, or
  `:malli/schema` Var-meta declarations are admitted, and no finding will report
  `[source: malli]`. See [Disabling an intake stream](#disabling-an-intake-stream).
- `--profile`: profile the run (CPU, memory, wall-clock time). Long-only.
- `-o`, `--output OUTPUT_FILE`: write Skeptic's output to this file instead of
  stdout, so lein/JVM messages stay on stdout. Works with text and `-p` JSONL
  output.
- `:paths PATHS` (deps.edn tool only): string or vector of source paths to
  check, overriding the paths discovered from `deps.edn`.
- `:alias ALIAS` (deps.edn tool only): keyword, string, or vector of aliases
  to merge when discovering source paths (for example, `:alias :test`).

### Disabling an intake stream

Skeptic admits typed declarations from two independent stream sources —
Plumatic Schema (`s/defn` / `s/def` / `s/defschema`) and Malli (`m/=>`,
`mx/defn`, `:malli/schema` Var-meta) — plus Skeptic's built-in native-fn
registry. `--plumatic-disable` and `--malli-disable` switch off either stream
wholesale.

When a stream is disabled:

- No declarations from that stream contribute to the merged type dict.
- No findings carry that stream's `source:` attribution.
- The other stream is unaffected; native-fn checks still run.
- A Var declared in **both** streams (i.e. `s/defn` + `m/=>` on the same
  symbol) is still admitted via the surviving stream.
- `--plumatic-disable` also disables `:skeptic/type-overrides` from
  `.skeptic/config.edn`, since overrides are a Plumatic-domain construct.

Both flags can be combined; the result is a run that checks only against
Skeptic's built-in native-fn declarations.

## Output

### Text (default)

The default output is a human-readable, ANSI-coloured report, one block per
inconsistency, grouped by namespace. Findings include the source of the
reported type, such as Schema, Malli, a built-in/native declaration, a type
override, or inference.

```
---------
Namespace: 		skeptic.showcase
Location: 		/Users/demouser/Code/skeptic/skeptic/src/skeptic/showcase.clj:10:3 [source: native]
Blame: 			context( value )
---
(str x)

has an output mismatch against the declared return type.

Declared return type expects:

Int

Problem fields:

	- Str but expected Int

---------
Namespace: 		skeptic.showcase
Location: 		/Users/demouser/Code/skeptic/skeptic/src/skeptic/showcase.clj:14:3 [source: schema]
Blame: 			context( value )
---
(:n m)

	in

(inc-int (:n m))

has inferred type incompatible with the expected type:

Problems:

	- a nullable value was provided where the type requires a non-null value

Per-namespace inconsistencies:
  skeptic.showcase: 2
```

When the run finds inconsistencies, the report ends with a per-namespace
summary listing each affected namespace and its error count, sorted with the
worst-offending namespace first.

### JSONL (`-p` / `--porcelain`)

`lein skeptic -p` switches stdout to newline-delimited JSON. One object per
line, in this order:

```json
{"kind": "ns-discovery-warning", "path": "src/foo/broken.clj", "message": "..."}
```

```json
{
  "kind": "finding",
  "ns": "foo.bar",
  "report_kind": "input",
  "location": {
    "file": "src/foo/bar.clj",
    "line": 42,
    "column": 3,
    "source": "schema",
    "lang": "clj"
  },
  "blame": "(+ 1 :x)",
  "blame_side": "term",
  "blame_polarity": "positive",
  "rule": "ground-mismatch",
  "actual_type":   {"t": "ground", "name": "Keyword"},
  "expected_type": {"t": "ground", "name": "Int"},
  "actual_type_str":   "Keyword",
  "expected_type_str": "Int",
  "focuses":             ["x"],
  "enclosing_form":      "(defn f [x] (+ 1 x))",
  "expanded_expression": "(clojure.core/+ 1 x)",
  "messages":            ["Keyword is not compatible with Int at ..."]
}
```

```json
{
  "kind": "exception",
  "ns": "foo.bar",
  "phase": "declaration",
  "location": {
    "file": "src/foo/bar.clj",
    "line": 99,
    "source": "schema",
    "lang": "clj"
  },
  "blame": "my-fn",
  "exception_class": "java.lang.RuntimeException",
  "exception_message": "could not resolve schema",
  "messages": ["Skeptic hit an exception while checking declared schema for my-fn ..."]
}
```

```json
{
  "kind": "namespace-error-summary",
  "counts": {
    "foo.bar": 5,
    "foo.baz": 2
  }
}
```

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

Exit code matches text mode (`0` clean, `1` otherwise).

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
project root. Matched files are skipped entirely. Patterns use the platform's 
`java.nio.file.PathMatcher` glob syntax (`*`, `**`, `?`, character classes). 

### `:type-overrides`

Map from fully-qualified symbol to an override map with any of `:schema`,
`:output`, `:arglists`. Values are Plumatic Schema expressions evaluated with
`[schema.core :as s]` in scope, so you can write `(s/eq nil)`, `s/Int`, etc.
Overrides replace whatever Skeptic would otherwise infer or collect for that
symbol at call sites.

```clojure
{:type-overrides {clojure.tools.logging/infof {:output (s/eq nil)}}}
```

After this, call sites of `infof` are checked as returning `nil`.

## ClojureScript support

Skeptic loads and admits ClojureScript source files alongside Clojure.
It discovers `.cljs` and `.cljc` files via project-layout-aware helpers
for deps.edn, Leiningen, and Shadow-CLJS.

Each cljs source file is parsed with the public
`cljs.analyzer.api/parse-ns` (which loads any `:require-macros`
namespaces on the JVM) and analyzed form-by-form with the 3-arity
`analyze`. No compiler state is constructed or carried across calls —
every form analysis rebuilds a fresh ephemeral environment from the
source file's ns AST.

Both intake streams support cljs:

- **Plumatic Schema:** `s/defn`, `s/def`, and `s/defschema` declarations
  on cljs vars are admitted. Post-macroexpansion schema bodies are
  evaluated in a sci-sandboxed Clojure interpreter configured with
  `schema.core` pre-loaded, so `s/Int`, `s/maybe`, `s/eq`, and the rest
  resolve to the same Plumatic Schema record values they would on the
  JVM without using `clojure.lang.Compiler/eval`.
- **Malli:** `:malli/schema` var-meta on cljs vars is read directly off
  the cljs analyzer AST and passes through the same
  `malli-spec->type` bridge used for JVM admissions.

`.cljc` files are admitted twice — once with `:clj` reader-conditional
features active and once with `:cljs` — and identical findings from both
passes are deduped at the JSONL layer with `lang` set to
`["clj","cljs"]`. Findings unique to one pass keep `lang` as a single
keyword string (`"clj"` or `"cljs"`).

Pass `--cljs-disable` to skip cljs admission entirely. With the flag
set, `.cljs` files are dropped and `.cljc` files are admitted as
`:clj`-only — the `:cljs` reader-conditional branch is discarded.

Known limitation: a pure-cljs schema that references another pure-cljs
namespace requires the dependency to be analyzable in dependency order.

## Experimental Malli support

Skeptic reads Malli function declarations from `:malli/schema` Var metadata
and from the compile-time `(malli.core/function-schemas)` registry (which
captures `m/=>` and `malli.experimental/defn` declarations):

```clojure
(defn takes-int
  {:malli/schema [:=> [:cat :int] :string]}
  [x]
  (str x))
```

Currently parsed forms include single-arity `[:=> [:cat ...] out]` and
multi-arity `[:function [:=> ...] [:=> ...]]`; primitive leaves such as
`:int`, `:string`, `:keyword`, `:symbol`, `:boolean`, `:double`, `:float`,
`:nil`, `:qualified-keyword`, `:qualified-symbol`, and `:any`; plus
`:maybe`, `:or`, `:and`, `:tuple`, `:vector`, `:set`, `:sequential` (with
optional `:min`/`:max` properties parsed and dropped — Skeptic does not
constrain container length), `:map` (with required keys,
`{:optional true}` keys, and nested values; `:closed true` is honored,
and the default is open — extra keyword keys are admitted with `Any`
values, mirroring Malli's open-by-default semantics), `:multi` with
`{:dispatch :kw}` tagged-dispatch (later branches are correctly narrowed
by negation of earlier tags; fn-dispatch is admitted but each arm stands
alone), `:=`, `:enum`, `:schema` (with optional `{:registry {...}}`
properties carrying a local registry), `:ref` (resolved through the
active registry, with cycle detection: a recursive position emits an
`InfCycleT` rather than diverging), and bare predicate symbols that
Skeptic recognizes.

Broader Malli forms are still experimental. Sequence/regex combinators
outside the `:=>` head (e.g. `:cat` outside the function head, `:alt`,
`:*`, `:+`, `:?`, `:repeat`, `:re`, `:fn`) are admitted when Malli
accepts them; their Skeptic type is currently dynamic.

## Suppressing checks

Skeptic provides three opt-out mechanisms for when its inference is wrong or
too dynamic. 

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

Skeptic discovers `.clj`, `.cljs`, and `.cljc` source files under your
project's source paths, loads each namespace (via JVM `require` for
Clojure and via the public `cljs.analyzer.api` for ClojureScript),
collects the Plumatic Schema and Malli annotations you've written on
vars and functions, infers a type for each expression in your code, and
compares the inferred types against the declared schemas on function
inputs and outputs. Each mismatch is reported with a source location,
the inferred type, and the expected type. `.cljc` files are admitted
twice — once with the `:clj` reader-conditional feature active and once
with `:cljs` — and identical findings from both passes are deduped with
their `lang` set to `["clj","cljs"]`.

If a discovered file cannot be loaded — for example, a `.clj` file
whose body depends on a runtime not available to the lein/JVM process,
or a `.cljs` file whose require graph cannot be parsed — Skeptic skips
it cleanly with a discovery warning naming the file and the underlying
load error. The rest of the project continues to be checked. Discovery
warnings do not flip the run's exit code on their own.

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

The key takeaway is that `Any` is not treated as some sort of `Top` or `Object` type. `Any` (and internally, `Dyn`) is
treated as a Dynamic type that should be treated as typechecking anything, until a more precise form can be inferred.
This is what lets Skeptic work with dynamic code without requiring users to add in a library of type hints: proveable
inconsistencies are flagged without required proof of consistency.

## Building from source

To run an unreleased version of the plugin from a local checkout:

1. Running `script/install-local.sh` we delete old versions in your `.m2` cache, install the Skeptic library, then
   install the lein-skeptic plugin. This will ensure a clean run, but careful if you are running multiple versions.
2. Otherwise, run `lein install` in the `skeptic/` directory first to install the core library, then again in the
   `lein-skeptic/` directory to install the plugin.

## License

The MIT License (MIT)
