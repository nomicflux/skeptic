# JSONL output reference

`lein skeptic -p` (or `--porcelain`) switches Skeptic's stdout to
newline-delimited JSON. Read this page if you're integrating Skeptic into
another tool — a CI gate, a review bot, an editor overlay — and need to
parse the machine-readable stream.

- Every line is one self-contained JSON object.
- No ANSI codes, no banners, no separator lines — stdout is pure JSONL.
- Exit code: `0` when no findings and no exceptions, `1` otherwise (same as
  the default text mode).
- Any empty or nil field is omitted from the line.

## Stream shape

- Zero or more `{"kind": "ns-discovery-warning", ...}` records, one per
  non-blocking namespace-discovery failure.
- Zero or more `{"kind": "finding", ...}` records, one per type mismatch.
- Zero or more `{"kind": "exception", ...}` records, one per namespace-local
  exception hit during checking.
- Always a final `{"kind": "run-summary", "errored": true|false, ...}` line —
  even on clean runs.

## `kind: "finding"` — type mismatch

```json
{
  "kind": "finding",
  "ns": "foo.bar",
  "report_kind": "input",
  "location": {
    "file": "src/foo/bar.clj",
    "line": 42,
    "column": 3,
    "source": "schema"
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

`report_kind` is `"input"` (argument/call site) or `"output"` (return value).
`location.source` identifies where the reported type came from: `"schema"`,
`"malli"`, `"native"`, `"type-override"`, or `"inferred"`.
`actual_type` and `expected_type` are structured, tagged representations of
Skeptic's type system — see [Structured type tags](#structured-type-tags)
below. The `*_str` fields mirror the same type as a human-readable string;
they're redundant with the structured form, included for simple tooling that
wants a quick display without walking nested JSON.

## `kind: "exception"` — Skeptic hit an exception while checking a form

```json
{
  "kind": "exception",
  "ns": "foo.bar",
  "phase": "declaration",
  "location": {
    "file": "src/foo/bar.clj",
    "line": 99,
    "source": "schema"
  },
  "blame": "my-fn",
  "exception_class": "java.lang.RuntimeException",
  "exception_message": "could not resolve schema",
  "messages": ["Skeptic hit an exception while checking declared schema for my-fn ..."]
}
```

## `kind: "ns-discovery-warning"`

Emitted when a file in a source path could not be read or its namespace
resolved, and the run is still able to proceed.

```json
{"kind": "ns-discovery-warning", "path": "src/foo/broken.clj", "message": "..."}
```

## `kind: "run-summary"` — always the last line

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

## Structured type tags

The `actual_type` and `expected_type` fields on a finding, and any nested
type-shaped payload, are tagged JSON objects. Each has a `"t"` discriminator
naming the type constructor. The full set:

| tag             | payload                                                                          |
|-----------------|----------------------------------------------------------------------------------|
| `any`           | _(no payload)_                                                                   |
| `bottom`        | _(no payload)_                                                                   |
| `ground`        | `name`                                                                           |
| `numeric-dyn`   | `name`                                                                           |
| `named`         | `name`, `source`                                                                 |
| `refinement`    | `name`                                                                           |
| `adapter`       | `name`                                                                           |
| `value`         | `value` (stringified via `pr-str`)                                               |
| `type-var`      | `name`                                                                           |
| `forall`        | `binder` (array of names), `body` (type)                                         |
| `sealed`        | `ground` (type)                                                                  |
| `inf-cycle`     | `ref` (optional)                                                                 |
| `maybe`         | `inner` (type)                                                                   |
| `conditional`   | `branches` (array of types)                                                      |
| `union`         | `members` (array of types, sorted for stability)                                 |
| `intersection`  | `members` (array of types)                                                       |
| `map`           | `entries` (array of `{"key": type, "val": type}`)                                |
| `optional-key`  | `inner` (type)                                                                   |
| `vector`        | `items` (array of types)                                                         |
| `set`           | `members` (array of types)                                                       |
| `seq`           | `items` (array of types)                                                         |
| `var`           | `inner` (type)                                                                   |
| `placeholder`   | `name`                                                                           |
| `fn-method`     | `inputs` (array of types), `output` (type), `variadic` (bool), `min_arity` (int) |
| `fun`           | `methods` (array of `fn-method` objects)                                         |

By default, Schema, Malli, and type-override declarations may serialize as
`{"t": "named", ...}` when a declared name can stand in for a larger structure.
Pass `--explain-full` to emit the expanded structural form instead.

## Combining with `--profile`

When both `--porcelain` and `--profile` are set, the profile summary is
written to **stderr** so stdout stays pure JSONL. When `-o` is also set,
JSONL still goes to the output file and the profile summary still goes to
stderr.
