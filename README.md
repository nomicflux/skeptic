# Skeptic

Skeptic is a Leiningen plugin for projects that use
[Plumatic Schema](https://github.com/plumatic/schema). It reads Clojure
code, infers the schemas flowing through function calls, and reports places
where the code and the declared schemas disagree.

The goal is to catch schema mistakes before runtime validation or production
traffic has to find them for you.

## What Skeptic checks

Skeptic scans the namespaces in your project's `:source-paths` and
`:test-paths`, then looks for problems such as:

- Calling a function with a value that does not match the declared input schema.
- Passing a maybe-nil value to a function that requires a non-nil argument.
- Returning a value that does not match a function's declared output schema.
- Schema drift through common operations such as `get` and `merge`.

Recent versions also report the source location, the enclosing form, and the
specific input that caused the mismatch, so the output is tied back to the code
you wrote instead of only to a macroexpanded form.

## How it works

At a high level, Skeptic:

1. Loads the namespaces in the project being checked.
2. Collects Schema annotations from schematized vars and functions.
3. Analyzes each source form with `clojure.tools.analyzer`.
4. Compares inferred argument and return schemas against the declared ones.
5. Prints a report for each mismatch and exits with status `1` when
   inconsistencies are found, or `0` when none are found.

## How Skeptic Typechecks Untyped Code

Skeptic's main job is to recover schema information for untyped code from
context.

It starts with whatever the code itself reveals, keeps that information alive
as it flows through locals, branches, map operations, and helper bodies, and
only falls back to `Any` when it cannot prove anything more specific. The
algorithm below is the path an untyped value follows from first sighting to
final pass or fail.

1. When Skeptic first sees a value, it gives that value the most specific
   schema the form itself proves. Literals become concrete schemas. `nil`
   becomes a maybe-value, not plain `Any`. Vectors, lists, sets, and maps keep
   their container shape and combine the schemas of their contents. A local or
   var with no known schema becomes `Any`. A same-namespace helper whose schema
   is not known yet is kept as a deferred placeholder instead of being
   flattened to `Any` immediately.

2. Skeptic then carries that schema through the surrounding code.
   A binding keeps the schema of its initializer. A sequential form keeps the
   schema of its final expression. A `try` keeps the possibilities from the
   body and from each `catch`. An `if` keeps both branch results instead of
   forcing one answer early.

3. Control flow and common data operations can refine that first schema.
   If a local is used as the condition of an `if`, the true branch drops the
   `nil` possibility for that local. A map lookup keeps the schema of the
   matching key when Skeptic can still understand the map, and a default value
   becomes another possible result. A map merge preserves map shape only while
   the merged inputs are still understandable as maps.

4. When a call is analyzed, Skeptic works out what the callee is known to
   accept. A typed callee contributes its declared input and output schemas. An
   untyped callee can still contribute arity: if Skeptic knows how many
   arguments it takes, the call is matched by arity even though each parameter
   slot is still treated as unknown. If the callee is fully opaque, Skeptic
   still treats it as callable with the observed number of arguments and an
   unknown result. This is how untyped code can still be checked against typed
   code instead of being ignored.

5. Local untyped helpers are handled more precisely than fully opaque code.
   Skeptic first leaves a placeholder for the helper, then analyzes the helper
   body, and then comes back and replaces that placeholder with the helper's
   inferred result. That is a core part of how Skeptic turns untyped code into
   useful schema information instead of leaving every helper call at `Any`.

6. Once an untyped value reaches a typed requirement, Skeptic checks
   compatibility in a fixed order. An expected `Any` accepts immediately. Exact
   matches accept immediately. If the expected side has several allowed
   possibilities, one matching possibility is enough. If the actual side has
   several remaining possibilities, every one of them must fit. If both sides
   may be `nil`, Skeptic compares their non-`nil` parts. If only the expected
   side allows `nil`, it keeps checking against the non-`nil` part. If only the
   actual side allows `nil`, the check fails there.

7. Only after those earlier rules does Skeptic treat the actual value
   permissively as residual unknown. At that stage, a plain `Any`, an
   unresolved placeholder, or a very broad category such as "some number" or
   "some object" means "not enough information to prove this wrong." That rule
   is narrower than it sounds. A maybe-unknown has already been handled by the
   nilability rules, and if one branch of a several-possibilities value is
   definitely wrong, the whole check has already failed even if another branch
   is unknown. If the value is still a real map or collection, Skeptic compares
   its structure next. If no rule accepts it, Skeptic reports an
   incompatibility.

### Examples

1. If `x` has no declared schema and nothing else in the surrounding code
   narrows it, then passing `x` to a function that expects a string usually
   succeeds. Skeptic has no proof that `x` is a string, but it also has no
   proof that `x` is the wrong thing, so the residual-unknown rule applies.

2. If `x` is "maybe something" because it may be missing or `nil`, then
   passing `x` to a function that requires a non-maybe integer fails even if
   the non-`nil` part is still unknown. Skeptic rejects it at the nilability
   step, before it reaches the residual-unknown rule.

3. If a local helper has no declared schema but its body always builds
   something like `{:id 1}`, Skeptic does not leave that helper at `Any`
   forever. It analyzes the helper body, records that inferred map shape, and
   then reuses that inferred result at later call sites in the same namespace.

## Current Polymorphism Boundary

Skeptic can already prove many guaranteed inconsistencies with first-order
inference alone. If analysis shows that a result includes `Int`, that is enough
to reject a downstream use that requires only `String`, even when the full
result type could be broader.

The planned Blame for All polymorphism work is a separate layer. Quantified
types are not synthesized opportunistically during ordinary checking. Instead,
first-order inference stays first-order, and any future `forall` support enters
the cast engine as an explicit semantic type.

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

## Reading the output

Each reported issue can include:

- the namespace being checked
- the file, line, and column
- the cast rule that failed
- the blamed side and polarity for the failed check
- the original source expression
- the specific input that failed
- the enclosing function or form
- the analyzed expression, when that differs from the source
- one or more human-readable mismatch messages

That makes it easier to answer two questions quickly: "what failed?" and "why
does Skeptic think it failed?"

## Checking model

Skeptic's current checker uses a directional cast model inspired by Blame for
All, but applies it as static analysis over inferred and declared schemas
rather than as a runtime gradual-typing machine. In practice, it checks
whether values can flow from the actual side of a use site to the expected
side, keeps nilability and structural cases distinct, and reports which side
of the comparison is blamed and with what polarity when that directional check
fails. The underlying idea is adapted from Amal Ahmed, Robert Bruce Findler,
Jeremy G. Siek, and Philip Wadler, "Blame for All" (POPL 2011).

## When to use it

Skeptic is most useful when you already annotate your code with Schema and want
feedback about how values flow between functions, helpers, and map operations.
It complements runtime schema validation; it does not replace it.

## License

The MIT License (MIT)

Copyright (c) 2022-2023 Michael Anderson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
