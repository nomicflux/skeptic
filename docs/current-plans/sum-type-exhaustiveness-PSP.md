# Sum-Type Exhaustiveness PSP

Implement closed sum-type exhaustiveness in the Type domain so exhaustive branch chains do not join unreachable fallback/default outputs.

## Phases

1. Predicate exhaustion for boolean and union conds.
2. Equality exhaustion for enum conds.
3. Case and condp exhaustive defaults.

## Gates

Each phase requires `lein test`, `clj-kondo --lint .`, a status update, a phase commit, and an end-to-end `lein with-profile +skeptic-plugin skeptic` run from `/Users/demouser/Code/skeptic/skeptic`.
