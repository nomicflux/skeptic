# skeptic-profiling — Formation

**Status:** Approved
**Date:** 2026-04-03
**Previous stage:** [creation.md](creation.md)

---

## Summary

The profiling feature is implemented across three components: a modified `leiningen.skeptic` (CLI flag + eval-in-project form change), a minimally refactored `skeptic.core` (extract exit logic into `check-project`), and a new `skeptic.profiling` namespace (all JFR lifecycle, event reading, aggregation, and summary rendering). The profiling namespace takes a thunk and opts, has no dependency on any other skeptic namespace, and uses only JDK-native JFR APIs (no external dependencies). The `.jfr` raw file is dumped to `target/skeptic-profile.jfr`.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│ Leiningen JVM                                                   │
│                                                                 │
│  leiningen.skeptic                                              │
│    - parses CLI args (adds -p/--profile)                        │
│    - builds eval-in-project form                                │
│    - passes opts, root, paths into the project JVM              │
└──────────────────────────┬──────────────────────────────────────┘
                           │ eval-in-project
                           v
┌─────────────────────────────────────────────────────────────────┐
│ Project JVM                                                     │
│                                                                 │
│  skeptic.profiling/run                                          │
│    - if (:profile opts): start JFR, run thunk, stop JFR,       │
│      dump .jfr, read events, aggregate, print summary           │
│    - if no :profile: call thunk directly (pass-through)         │
│    - returns exit code                                          │
│                                                                 │
│  skeptic.core/check-project                                     │
│    - the analysis work (called as the thunk)                    │
│    - returns 0 (no errors) or 1 (errors found)                  │
│                                                                 │
│  System/exit called after run returns                            │
└─────────────────────────────────────────────────────────────────┘
```

## Tech Stack

| Technology | Role | Rationale |
|-----------|------|-----------|
| JFR (`jdk.jfr.Recording`) | Recording lifecycle | Built into JDK 17+. No external dependency. Portable. |
| JFR (`jdk.jfr.Configuration`) | Recording configuration | Use built-in "profile" preset. Well-tuned defaults. |
| JFR (`jdk.jfr.consumer.RecordingFile`) | Event reading | Read `.jfr` file programmatically for summary generation. |
| `clojure.tools.cli` | CLI parsing | Already a dependency of lein-skeptic. |
| Clojure/Java interop | JFR API access | Standard mechanism. No wrapper library needed. |

No new dependencies are added to either `project.clj`.

## Component Inventory

### 1. `leiningen.skeptic` (modified)

**File:** `lein-skeptic/src/leiningen/skeptic.clj`

**Responsibility:** CLI parsing, eval-in-project orchestration.

**Changes:**
- Add `["-p" "--profile" "Profile the run (CPU, memory, wall-clock time)"]` to `cli-options`
- Change the eval-in-project form from:
  ```clojure
  `(skeptic.core/get-project-schemas ~opts ~(:root project) ~@paths)
  ```
  to:
  ```clojure
  `(let [exit-code# (skeptic.profiling/run ~opts ~(str (:root project) "/target")
                      (fn [] (skeptic.core/check-project ~opts ~(:root project) ~@paths)))]
     (System/exit exit-code#))
  ```
- Change the require form from:
  ```clojure
  '(require 'skeptic.core)
  ```
  to:
  ```clojure
  '(do (require 'skeptic.core) (require 'skeptic.profiling))
  ```

**Boundaries:** Only component that knows about Leiningen. Assembles the quoted form and passes serializable data (opts map, strings) into the project JVM.

### 2. `skeptic.core` (minimal refactoring)

**File:** `skeptic/src/skeptic/core.clj`

**Responsibility:** Project analysis entry point.

**Changes:**
- Extract the body of `get-project-schemas` into a new function `check-project` with the same signature. `check-project` returns `0` (no errors) or `1` (errors found) instead of calling `System/exit`.
- Redefine `get-project-schemas` as:
  ```clojure
  (defn get-project-schemas [opts root & paths]
    (System/exit (apply check-project opts root paths)))
  ```
  This preserves backward compatibility.

**Boundaries:** Owns analysis orchestration. No knowledge of profiling. The only change is separating "do the work" from "exit the process."

### 3. `skeptic.profiling` (new)

**File:** `skeptic/src/skeptic/profiling.clj`

**Responsibility:** All profiling concerns -- JFR recording lifecycle, event reading, Clojure name demangling, per-function aggregation, and summary rendering.

**Public API:** One function:
```clojure
(defn run [opts target-dir work-fn]
  ...)
```
- `opts`: the CLI options map. Checks `(:profile opts)`.
- `target-dir`: directory path string for the `.jfr` output file.
- `work-fn`: zero-arg function (thunk) to execute. Returns an exit code (integer).
- Returns: the exit code from `work-fn`.

**Internal structure (private functions):**
- Recording lifecycle: create, configure, start, stop, dump
- Event reading: iterate `.jfr` file, extract execution samples and allocation samples
- Demangling: transform JVM names to Clojure names (best-effort)
- Aggregation: build per-function data (self/total samples, allocation bytes, percentages)
- Summary rendering: format and print text summary to stdout

**Boundaries:** No dependency on any `skeptic.*` namespace. Uses only `jdk.jfr` classes via Java interop, plus `clojure.java.io` for file paths and `clojure.string` for name manipulation.

## Data Flow

### Without `-p` (zero cost path):

```
CLI parse -> opts (no :profile)
  -> eval-in-project:
       skeptic.profiling/run checks (:profile opts) -> falsy
       -> calls (work-fn) directly
       -> skeptic.core/check-project runs normally
       -> returns exit code (0 or 1)
       -> run returns exit code
  -> (System/exit exit-code)
```

No JFR classes loaded. No recording created. Identical behavior to today except the exit code return refactoring.

### With `-p`:

```
CLI parse -> opts {:profile true, ...}
  -> eval-in-project:
       skeptic.profiling/run checks (:profile opts) -> truthy
       1. Create JFR Recording with "profile" configuration
       2. Enable: jdk.ExecutionSample, jdk.ObjectAllocationSample, jdk.CPULoad
       3. Start Recording
       4. try:
            (work-fn) -> skeptic.core/check-project -> exit-code
          finally:
            5. Stop Recording
            6. Dump to <target-dir>/skeptic-profile.jfr
            7. Read events from .jfr file
            8. Demangle Clojure function names in stack frames
            9. Aggregate into Profile Data (per-function self/total/alloc)
           10. Print Summary Report to stdout
       11. Return exit-code
  -> (System/exit exit-code)
```

## Integration Points

| From | To | Mechanism | Data |
|------|----|-----------|------|
| `leiningen.skeptic` | `skeptic.profiling/run` | eval-in-project quoted form | opts map, target-dir string, work-fn thunk |
| `skeptic.profiling/run` | `skeptic.core/check-project` | thunk invocation | (none -- thunk closes over its args) |
| `skeptic.profiling` | JDK JFR | Java interop | `jdk.jfr.Recording`, `Configuration`, `RecordingFile`, `RecordedEvent` |
| `skeptic.profiling` | filesystem | `Recording.dump()` + `RecordingFile` read | `target/skeptic-profile.jfr` |
| `skeptic.profiling` | stdout | `println` | Summary Report text |

## Error Handling

| Failure | Behavior |
|---------|----------|
| JFR recording fails to start | Print warning, run work-fn unprofiled, return exit code normally |
| Analysis throws exception | Recording stops in `finally`, dumps what was captured, summary may be partial, exception propagates |
| `.jfr` dump fails (e.g., path not writable) | Print warning, skip summary, return exit code normally |
| `.jfr` read or aggregation fails | Print warning, raw `.jfr` may still be on disk for manual inspection, return exit code normally |
| `target/` directory doesn't exist | Create it before dumping (`(.mkdirs ...)`) |

**Principle:** Profiling is diagnostic. It must never cause the analysis to fail. All profiling errors are caught, warned, and bypassed.

## Mapping from Concepts to Components

| Concept (creation.md) | Component | Location |
|----------------------|-----------|----------|
| Flag (`:profile` in opts) | `leiningen.skeptic` | CLI option definition |
| Profiling Session lifecycle | `skeptic.profiling/run` | Top-level orchestration |
| Recording (JFR) | `skeptic.profiling` | Private helpers |
| Profiling Events | `skeptic.profiling` | Event reading from `.jfr` |
| Profile Data (aggregation) | `skeptic.profiling` | Private aggregation logic |
| Summary Report | `skeptic.profiling` | Private rendering logic |
| Raw Output File | `skeptic.profiling` | `Recording.dump()` call |
| Demangling | `skeptic.profiling` | Private name transformation |
| Exit code separation | `skeptic.core/check-project` | Refactored return value |

## Accepted Trade-offs

1. **JFR "profile" preset over custom configuration:** Simpler, well-tuned, avoids maintenance burden.
2. **Dump-then-read over in-memory streaming:** Simpler, guarantees raw file exists, negligible I/O cost.
3. **Minimal `skeptic.core` refactoring over duplication:** Clean 5-line change, preserves backward compatibility.
4. **Single `run` entry point over granular API:** Matches the use case, prevents misuse, can be decomposed internally.

## Open Questions for Implementation

1. Exact JFR event names and their field accessors for execution samples, allocation samples, and CPU load (to be verified against JDK 17/21 API docs during implementation).
2. Precise Clojure name demangling regex patterns for common cases (named functions, namespaced functions, anonymous functions).
3. Summary table formatting -- exact column widths and layout (implementation detail, not architectural).
