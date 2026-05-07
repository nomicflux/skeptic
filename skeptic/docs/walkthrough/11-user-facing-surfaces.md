# User-Facing Surfaces

> *Snapshot of state as of 2026-05-06.*

Output is the last step in the run. It receives report data that has already
been built by checking and projection. The printers decide how that report data
is shown; they do not run another analysis pass.

## The Report Arriving From `classify`

The `classify` output failure reaches output with these facts already assembled:

```text
report kind:    output
expression:     the method body
expected Type:  declared Keyword output
actual Type:    annotated body result
diagnostics:    source-union failure evidence
source:         declaration/inference provenance carried through the report
```

A printer can render those fields in different formats, but the meaning stays
the same. Text output is a human view of this report. JSONL output is a tool view
of this report.

## Text Output

The text printer turns the report into ordered fields. Location, expression,
blame information, rule text, expected Type, actual Type, and error messages are
arranged into a terminal-oriented block.

For `classify`, the output text should lead a developer back to the declared
return boundary and the body result that violates it. The important movement is:

```text
output report -> declared Keyword expectation -> body result evidence -> string branch
```

The text printer preserves the facts that make the finding actionable: what
boundary was checked, what expected Type came from that boundary, what actual
Type failed, and which expression is under inspection.

## Error Strings And Summaries

Reports can carry several error strings. For a structural mismatch, each
diagnostic can contribute a detail line. Report rendering can also choose a
headline Type display from the root cast result or from a selected diagnostic.

In the `classify` output case, the declared return Type remains the expected
Type of the user-facing summary. The diagnostic evidence explains why the body
does not satisfy that declared Type.

## JSONL Output

Porcelain output writes newline-delimited JSON. A finding record gives tools a
stable set of fields: namespace, report kind, location, source expression,
blame data, rule, expected Type data, actual Type data, and error strings.

The run also emits summary records. The final run summary gives the total
finding count, exception count, namespace count, and namespace count with
findings. That closing record is what lets a consuming tool know it has seen the
whole run.

## Type Rendering

Both output modes render Types through the bridge renderer. Text output needs a
readable string. JSONL output needs tagged data that another program can inspect.

The renderer can fold known declaration names when that is clearer, or show
structural detail when full explanation is requested. The Type being rendered is
still the semantic Type from earlier phases. Rendering is a boundary from Type
data to display data.

## Suppressing Checks

Skeptic has three user-facing suppression or override mechanisms, each at a
different boundary.

` :skeptic/ignore-body` skips body checks for a declared function. The function
still has a declared Type for callers. This is a statement about checking the
definition body.

`:skeptic/opaque` makes callers see the function through broad dynamic
input/output. This changes what callers can learn from the function boundary.

`^{:skeptic/type T}` changes the Type of one expression. Annotation imports the
metadata Type and uses it at that expression site. This is an expression-level
override, not a namespace dictionary entry.

## Reading Output Backward

A useful way to read a finding is backward:

```text
report kind
  -> expected Type
  -> actual Type
  -> diagnostic evidence
  -> source expression
  -> admitted declaration or annotation path
```

For `classify`, that gives:

```text
output report
  -> declared Keyword output
  -> body result containing string
  -> source-union failure evidence
  -> cond body
  -> s/defn declaration and branch annotation
```

The same route is available in JSONL mode. Instead of following a terminal
block, a tool follows record fields: report kind, expected Type data, actual
Type data, error strings, and source expression data. The shape is different,
but the debugging path is the same.

For an input report, the route starts differently. The report kind points to a
call argument, not to a definition output. The expected Type comes from the
callee, and the actual Type comes from the supplied expression.

That difference changes where a developer looks first. The `classify` output
report sends the search into the function body and its declared return Type. An
input report sends the search to the call site: which expression supplied the
argument, what Type annotation gave to that expression, and which callee
boundary expected something else.

## Source Pointers

- `skeptic/output.clj:printer` - chooses the output printer.
- `skeptic/output/text.clj:report-fields` - builds ordered text fields.
- `skeptic/output/porcelain.clj:printer` - emits porcelain lifecycle records.
- `skeptic/analysis/bridge/render.clj:render-type` - renders Types as text.
- `skeptic/analysis/bridge/render.clj:type->json-data` - renders Types as JSON data.
