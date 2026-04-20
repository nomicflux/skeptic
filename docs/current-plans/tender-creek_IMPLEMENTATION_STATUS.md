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

### Phase 1 complete
- Commit `210c7a4` on `main`.
- `lein install` done for skeptic lib + plugin.
- Five invocation variants exercised (see below); all exit 0, all behavior as specified.

## Phase 2 — Docs (complete)

### Changes
- `README.md`: new `-o/--output OUTPUT_FILE` bullet in the "Running it" Options list; sentence appended in the JSONL "Combining with --profile" subsection explaining that with `-o`, JSONL still routes to the file and profile still routes to stderr.
- `CHANGELOG.md`: entry under `[Unreleased] → Added` referencing GitHub #2.

## End-to-end verification (Phase 1)

| Invocation | File content | stdout | stderr | exit |
|---|---|---|---|---|
| `-o FILE` | "No inconsistencies found" | empty of skeptic output | — | 0 |
| `-p -o FILE` | pure JSONL, final line run-summary | empty | — | 0 |
| `--profile -o FILE` | findings + profile summary | empty | — | 0 |
| `-p --profile -o FILE` | pure JSONL (0 non-json lines) | empty | profile summary | 0 |
| (no `-o`) | — | "No inconsistencies found" | — | 0 |
