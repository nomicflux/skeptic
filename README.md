# Skeptic

Skeptic is a Leiningen plugin for projects that use
[Plumatic Schema](https://github.com/plumatic/schema). It reads Clojure code,
converts declared Plumatic Schema annotations into semantic types, and reports
places where inferred types disagree with those declared Plumatic Schema
annotations.

## What Skeptic checks

- Calls whose inferred argument types do not fit the declared input Plumatic
  Schema.
- Returns whose inferred result types do not fit the declared output Plumatic
  Schema.
- Nilability and structural mismatches that flow into declared Plumatic Schema
  annotations.

## How it works

1. Loads the namespaces in the project being checked.
2. Collects declared Plumatic Schema annotations from schematized vars and
   functions.
3. Converts those Plumatic Schema annotations into semantic types at the
   boundary.
4. Analyzes each source form with `clojure.tools.analyzer`.
5. Compares inferred semantic types against the declared Plumatic Schema
   annotations.
6. Prints a report for each mismatch and exits with status `1` when
   inconsistencies are found, or `0` when none are found.

## Installation

Add the plugin to the `:plugins` vector in your `project.clj`:

```clojure
:plugins [[lein-skeptic "0.7.0-SNAPSHOT"]]
```

If you need to override the default dependency profile, add a `:skeptic`
profile in your project and Skeptic will use that instead.

## Running it

From the project you want to inspect:

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
- `-p`, `--porcelain`: emit machine-readable JSONL (one JSON object per line)
  instead of the default human-readable output. See
  [JSONL output](#jsonl-output) below.
- `--profile`: profile the run (CPU, memory, wall-clock time). Long-only.

## When to use it

Skeptic is most useful when you already annotate your code with Plumatic
Schema and want feedback about how inferred types line up with those declared
Plumatic Schema annotations. It complements runtime Plumatic Schema validation;
it does not replace it.

## JSONL output

`lein skeptic -p` (or `--porcelain`) switches stdout to newline-delimited JSON.
Every line is one self-contained JSON object. No ANSI codes, no banners, no
separator lines — stdout is pure JSONL.

The stream shape:

- One `{"kind": "ns-discovery-warning", ...}` record per non-blocking
  namespace-discovery failure.
- One `{"kind": "finding", ...}` record per type mismatch.
- One `{"kind": "exception", ...}` record per namespace-local exception hit
  during checking.
- Always a final `{"kind": "run-summary", "errored": true|false, ...}` line,
  even on clean runs.

Exit code: `0` when no findings and no exceptions, `1` otherwise (same as
text mode).

### `kind: "finding"` — type mismatch

```json
{
  "kind": "finding",
  "ns": "foo.bar",
  "report_kind": "input",
  "file": "src/foo/bar.clj",
  "line": 42,
  "column": 3,
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

`report_kind` is `"input"` (argument/call site) or `"output"` (return value).
`actual_type` and `expected_type` are structured, tagged representations of
the Skeptic type domain (see below). `*_str` mirrors the same type as a
human-readable string — redundant with the structured form, included for
grep/LLM use. Any empty/nil field is omitted from the line.

### `kind: "exception"` — Skeptic hit an exception while checking a form

```json
{
  "kind": "exception",
  "ns": "foo.bar",
  "phase": "declaration",
  "file": "src/foo/bar.clj",
  "line": 99,
  "blame": "my-fn",
  "exception_class": "java.lang.RuntimeException",
  "exception_message": "could not resolve schema",
  "messages": ["Skeptic hit an exception while checking declared schema for my-fn ..."]
}
```

### `kind: "ns-discovery-warning"`

Emitted when a file in a source path could not be read or its namespace
resolved, and the run is still able to proceed.

```json
{"kind": "ns-discovery-warning", "path": "src/foo/broken.clj", "message": "..."}
```

### `kind: "run-summary"` — always the last line

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

### Structured type shape

Each tagged object has a `"t"` discriminator naming the Skeptic type
constructor. The set of tags:

| tag             | payload                                                            |
|-----------------|--------------------------------------------------------------------|
| `any`           | _(no payload)_                                                     |
| `bottom`        | _(no payload)_                                                     |
| `ground`        | `name`                                                             |
| `refinement`    | `name`                                                             |
| `adapter`       | `name`                                                             |
| `value`         | `value` (stringified via `pr-str`)                                 |
| `type-var`      | `name`                                                             |
| `forall`        | `binder` (array of names), `body` (type)                           |
| `sealed`        | `ground` (type)                                                    |
| `inf-cycle`     | `ref` (optional)                                                   |
| `maybe`         | `inner` (type)                                                     |
| `union`         | `members` (array of types, sorted for stability)                   |
| `intersection`  | `members` (array of types)                                         |
| `map`           | `entries` (array of `{"key": type, "val": type}`)                  |
| `optional-key`  | `inner` (type)                                                     |
| `vector`        | `items` (array of types)                                           |
| `set`           | `members` (array of types)                                         |
| `seq`           | `items` (array of types)                                           |
| `var`           | `inner` (type)                                                     |
| `placeholder`   | `name`                                                             |
| `fn-method`     | `inputs` (array of types), `output` (type), `variadic` (bool), `min_arity` (int) |
| `fun`           | `methods` (array of `fn-method` objects)                           |

### Combining with `--profile`

When both `--porcelain` and `--profile` are set, the profile summary is
written to **stderr** so stdout stays pure JSONL.

## Suppressing checks

Skeptic provides three opt-out mechanisms when its inference is wrong or too dynamic for its analysis. Each mechanism suppresses checks in a different scope without affecting the rest of your code.

### Ignoring a function body

Use `:skeptic/ignore-body` in a function's attribute map to skip type checking inside the function body. The declared schema still applies to all callers:

```clojure
(s/defn my-fn :- s/Int
  {:skeptic/ignore-body true}
  [x :- s/Int]
  (int-add nil x))
```

The function body's type mismatch (passing `nil` to `int-add`) is suppressed. Callers are still checked against the declared `:- s/Int` schema.

### Treating a function as a black box

Use `:skeptic/opaque` in a function's attribute map to exclude both the function body and its schema from checking. Callers see the function as accepting and returning `s/Any`:

```clojure
(s/defn my-fn :- s/Int
  {:skeptic/opaque true}
  [x :- s/Int]
  "not-an-int")
```

The function body is not checked, and callers can pass any type and expect any type back.

### Overriding an expression's type

Use `^{:skeptic/type T}` metadata on an expression to pin its inferred type to schema `T`:

```clojure
(let [y ^{:skeptic/type s/Int} (some-call-that-returns-any)]
  (int-add y 1))
```

The expression's type is treated as `s/Int` for subsequent checks. Note that Clojure does not allow metadata on bare literal values (numbers, strings, keywords); wrap them in a form if needed:

```clojure
^{:skeptic/type s/Int} (identity 42)
```

## License

The MIT License (MIT)
