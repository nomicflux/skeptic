# Context: local binding resolution, shadowing, and user-visible explanations

This note records **background only**: what breaks, where the lossy resolution logic lives in checking, and how TDD for the area was framed. It does **not** prescribe fixes or sequencing beyond describing intent that already exists in discussion.

---

## Symptom (user-visible)

When **Context** appears in Skeptic’s output, it shows **where locals are thought to come from** (types and a small “resolution path” of forms). Under **lexical shadowing**—the same simple name bound in an outer scope (parameter, outer `let`) and again in an inner scope—those lines can attribute a use of the name to the **wrong** binding’s initializer or chain. The bug is about **explanation / provenance**, not necessarily about whether the primary cast error is detected.

---

## Checking layer: how `local-vars` and resolution paths are built today

In `src/skeptic/checking/ast.clj`:

- **`binding-index`** walks **all** `:binding` nodes in the analyzed AST (via `sac/ast-nodes`) and folds them into a **single map** keyed by **`(:form node)`**—the local’s **symbol name only**.
- **`local-resolution-path`** takes that map and a `:local` node. It looks up **one** binding with `(get bindings (:form local-node))` and, if present, builds a short path from that binding’s **`:init`** (and optionally the callee ref of that init).
- **`local-vars-context`** collects `:local` nodes under a given subtree and, for each **distinct symbol** (first occurrence wins in its own accumulator), records `{:form … :type … :resolution-path …}` using **`local-resolution-path` and the same flat `bindings` map**.
- **`call-refs`** uses the same `bindings` map when the callee is a `:local`.

The pipeline builds this `bindings` map from **`binding-index` on the analyzed form** and passes it into these helpers when assembling each check result (`src/skeptic/checking/pipeline.clj` uses `ca/binding-index` in `check-resolved-form`).

**Structural fact:** Clojure allows many bindings that share the same symbol in **different** lexical scopes. A flat `symbol → single binding` map cannot represent that hierarchy. Depending on traversal order in `ast-nodes`, **at most one** binding per symbol is retained; any other binding for that name is dropped from the map. Resolution for **every** use of that name then goes through that sole entry—so uses that lexically refer to an **outer** binding can still be explained using the **inner** binding’s init (or vice versa), whichever entry survived in the map.

That mismatch between **lexical scope** and **name-only lookup** is the core of the shadowing / wrong-provenance issue in explanations.

---

## Related tests (engine-level, not CLI text)

`test/skeptic/checking/pipeline_test.clj` includes **`resolution-path-resolutions`**, which fixes expectations for `sample-let-bad-fn` on the **`(int-add x y z)`** call: e.g. `y` and `z` resolution paths include the forms that initialized them, while **`x`** is documented there as having an **empty** `:resolution-path` and type aligned with `s/Any` in that scenario. That test works off **`check-fn`** and **`:keep-empty true`**, and it reads **`:context` and `:errors`** directly. It is **not** the same surface as “only strings from `check-project`,” but it encodes current behavior of `local-vars` / resolution paths for one canonical example.

---

## TDD framing (as discussed, not as instructions)

Work on this area was discussed in terms of **test-first / regression-first** behavior:

1. **Regression signal on the user-facing surface** — behavior-driven tests that are agnostic to Skeptic internals should capture a case that reveals a failure due to shadowing in reporting type-checking errors, that correct variable-binding would correct.

3. **Intended direction of travel (high level, from design discussion)** — the problem was framed as **not recomputing** a lossy binding map for explanation, and instead **carrying** binding / resolution information from **annotation** (where lexical structure is still explicit) into what checking exposes to reporting. Any concrete schema names (`:skeptic/…` keys, threading through `calls`, removing `binding-index`, etc.) belong in a separate implementation plan if they are still accurate; this document only notes that **annotation-time structure** vs **flat name lookup** was the conceptual split.

---

## Scope boundary

- **Type domain vs schema domain** — project rules still apply: internal reasoning prefers the type domain; schemas are boundary input. This shadowing bug is about **which syntactic binding** backs an explanation, not about replacing Plumatic schemas.
- **This file** is **context for humans**; it is not a contract for tools and not a checklist of edits.
