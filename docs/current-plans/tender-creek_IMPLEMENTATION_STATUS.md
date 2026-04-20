# tender-creek — `-o/--output` flag (GitHub #2)

## Phase 1 — Implementation (in progress)

### Changes
- `lein-skeptic/src/leiningen/skeptic.clj`:
  - Added `-o/--output OUTPUT_FILE` entry to `cli-options`.
  - Rewrote `eval-in-project` body: when `(:output opts)` is non-nil, a `clojure.java.io/writer` wraps `*out*` around the `skeptic.profiling/run` call, with `try`/`finally` flushing and closing before `System/exit`.
  - Added `(require 'clojure.java.io)` to init forms.
  - `skeptic` defn: 19 lines.

### Verified so far
- `lein test` in `skeptic/`: 305 tests, 1610 assertions, 0 failures / 0 errors.
- `clj-kondo --lint lein-skeptic/src`: 0 errors, 0 warnings.

### Remaining gate steps
- Commit Phase 1.
- `lein install` from `lein-skeptic/`.
- Exercise `-o` manually (5 invocation variants), confirm file contents + stdout cleanliness.
- STOP for approval before Phase 2.
