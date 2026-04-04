# Schema Boundary Vision

## Goal

Schema must become a closed, structurally isolated subset of the app.

The system should have a single semantic direction at the boundary:

- raw Schema values enter the system
- schema-only code canonicalizes and validates them only where Schema operations require it
- schema-only code converts them once into native semantic types
- all internal analysis, cast logic, map reasoning, AST annotation, origin tracking, callable selection, and reporting run on semantic types only

Schema is not a peer internal representation. It is an external input language and a privileged subset of the native type world.

## Non-Negotiable Rules

- Use existing Schema values directly.
- Do not add any wrapper layer over Schema.
- Allow only one-way conversion: `schema->type`.
- Remove every `type->schema` path.
- Remove every internal dependency on regenerated Schema values.
- Make schema-only helpers unreachable from non-schema code.
- Make `schema->type` total by construction by ensuring only schema-only code can call it and only with actual Schema values.
- Validate only at points where schema-based operations require valid Schema structure or would otherwise throw.
- Keep internal AST nodes, entries, call metadata, and analysis state type-only.
- Do not reconstruct Schema from native semantic types anywhere in internal analysis code.

## Architectural Shape

The system should be split into two layers.

### Schema-only layer

This layer is allowed to hold raw Schema values and call schema-library partial operations.

Its responsibilities are:

- declaration collection from var metadata
- schema canonicalization and localization
- schema display for schema-facing diagnostics
- placeholder resolution over raw Schema values
- Schema validation exactly where schema-library operations require it
- one-way import from Schema into semantic types
- explicit schema-boundary adapter APIs for callers that truly start from raw Schema

This layer is the only place where operations such as these are allowed:

- `s/check`
- `s/explain`
- `s/find-extra-keys-schema`
- `s/make-fn-schema`
- `s/one`
- `s/maybe`
- destructuring `FnSchema` or `One` values via `into {}`

### Type-only layer

This layer must never depend on regenerated Schema values.

Its responsibilities are:

- semantic type normalization
- union and intersection formation
- type algebra
- cast checking
- map lookup and merge reasoning
- value/type compatibility checks
- declaration lookup
- callable selection
- AST annotation
- flow refinement
- origin tracking
- mismatch reporting
- user-facing type rendering

All of those operations must accept and return semantic types only.

## Boundary Rule

The boundary rule is:

1. receive raw Schema at a schema-facing API
2. canonicalize and validate only where a schema-library partial operation requires it
3. convert once to semantic types
4. continue entirely in the type domain

There is no reverse bridge.

If a later consumer needs display or diagnostics, it renders semantic types directly. It does not rebuild Schema in order to keep going.

## Validation Rule

Validation must exist only at schema operations that require real Schema structure or can throw on non-Schema input.

That includes:

- declaration collection before fn-schema destructuring
- schema import before `FnSchema` or `One` destructuring
- schema display before `s/explain`
- schema matching before `s/check`
- extra-keys inspection before `s/find-extra-keys-schema`
- placeholder resolution before rebuilding raw Schema constructors

Validation must not be scattered through internal type logic.

Once a value has crossed into the type domain, it is already trusted as a semantic type and should be handled as such.

## Internal Data Rule

Internal shared state must remain lossless and semantic.

That means:

- AST nodes carry semantic type information only
- declaration entries carry semantic type information only
- call metadata carries semantic type information only
- origin and refinement state carry semantic type information only

Schema-shaped mirrors such as these must not exist internally:

- `:schema`
- `:output`
- `:fn-schema`
- `:actual-arglist`
- `:expected-arglist`
- `:arg-schema`

If user-facing output needs readable forms, it derives them from semantic types at the display boundary.

## Subsystem Boundaries

### Schema-only subsystem

The schema-only subsystem should contain:

- schema declaration collection
- schema payload definitions
- schema canonicalization
- schema localization
- schema display
- schema placeholder resolution
- schema import into semantic types
- explicit schema-boundary adapter APIs

### Type-only subsystem

The type-only subsystem should contain:

- semantic type constructors and predicates
- type normalization
- type algebra
- cast engine
- map type operations
- value/type compatibility logic
- annotation pipeline
- callable lookup
- origin tracking
- reporting and type display

## Acceptance Conditions

The redesign is complete when all of these are true:

- no additional wrapper abstraction over Schema exists
- no non-schema code can call schema-only helpers
- `schema->type` has no runtime failure mode for non-schema input because non-schema input cannot reach it
- validation exists only at schema-library partial-operation boundaries
- internal analysis code operates on semantic types only
- no internal code path reconstructs Schema from native types
