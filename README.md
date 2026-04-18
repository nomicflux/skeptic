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

## When to use it

Skeptic is most useful when you already annotate your code with Plumatic
Schema and want feedback about how inferred types line up with those declared
Plumatic Schema annotations. It complements runtime Plumatic Schema validation;
it does not replace it.

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
