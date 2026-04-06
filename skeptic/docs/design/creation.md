# skeptic-profiling — Creation

**Status:** Approved
**Date:** 2026-04-03
**Previous stage:** [intention.md](intention.md)

---

## Summary

The profiling feature is built from a small set of concepts: a JFR-backed recording that captures execution samples, allocation samples, and CPU load during the analysis run; an aggregation step that reads events and builds per-function profile data (self time, total time, allocation volume); a summary report printed to stdout; and a raw `.jfr` file written to disk. The `-p` flag flows through the existing opts map, and a new profiling utility namespace in the skeptic library orchestrates the recording lifecycle. No existing analysis code is modified.

## Deviation from Intention

`intention.md` stated "No source changes are required in the skeptic library." This has been refined: a **new** profiling utility namespace will be added to the skeptic library. The constraint is that **no existing analysis, checking, or reporting code is modified**. The profiling namespace is a new, isolated addition.

## Conceptual Model

### Entities

**Profiling Session**
The top-level concept representing one profiled run. Has a lifecycle: idle -> recording -> stopped -> reported. Created only when `:profile` is present in the opts map. When absent, nothing profiling-related is created or executed (zero cost).

**Recording**
A JFR (Java Flight Recorder) recording object. Configured to capture three event types simultaneously in a single recording. Lifecycle: created -> started -> stopped -> dumped to disk -> closed. Owned by the Profiling Session.

**Profiling Events** (three kinds, captured by the Recording)
- *Execution Samples* (`jdk.ExecutionSample`): periodic snapshots of the call stack, captured at ~10ms intervals. Each sample contains a full stack trace. Source for wall-clock time and CPU attribution.
- *Allocation Samples* (`jdk.ObjectAllocationSample`): captured when allocation crosses a TLAB threshold (~256KB). Each sample contains a stack trace and allocation size. Source for memory attribution. Statistical, not exact.
- *CPU Load* (`jdk.CPULoad`): periodic system-level CPU utilization measurements. Provides overall context (e.g., "CPU was at 95% during the run") but is not per-function.

**Profile Data**
The aggregated result of processing events after the recording stops. Per-function structure:
- *Function name*: demangled Clojure name (best-effort) or raw JVM name (fallback)
- *Self samples*: number of times this function was at the **top** of the stack (doing its own work, not waiting on callees)
- *Total samples*: number of times this function appeared **anywhere** in the stack (itself + everything it transitively called)
- *Self %*: self samples as a percentage of total samples in the recording
- *Total %*: total samples as a percentage of total samples in the recording
- *Allocation bytes*: total bytes allocated in stack traces where this function appears at the top

Profile Data is sorted into views: by self time, by total time, by allocation volume.

**Summary Report**
Human-readable text printed to stdout after the normal plugin output. Structure:
- Overall statistics: recording duration, total execution samples, total allocation samples, overall CPU utilization
- Top functions by self time: function name, self samples, self %, total samples, total %
- Top functions by allocation: function name, allocation bytes, % of total allocation
- Each function name is fully qualified (namespace/function) so the reader can go directly to the source

**Raw Output File**
The `.jfr` file written by `Recording.dump()` to `target/skeptic-profile.jfr`. Standard JFR format, readable by JDK Mission Control, VisualVM, IntelliJ profiler, or programmatically via `jdk.jfr.consumer.RecordingFile`. This is the profiler's native format, not a custom invention.

### Relationships

```
opts map (:profile flag)
    │
    └──> Profiling Session (created only if :profile is true)
              │
              ├── owns ──> Recording (JFR)
              │                │
              │           captures while running
              │                │
              │                v
              │          Profiling Events
              │          ├── Execution Samples (time + CPU)
              │          ├── Allocation Samples (memory)
              │          └── CPU Load (overall context)
              │
              ├── after stop ──> Raw Output File (.jfr dumped to target/)
              │
              └── after stop ──> Profile Data (aggregated from events)
                                      │
                                      └──> Summary Report (printed to stdout)
```

## Capability Inventory

1. **Parse the flag**: Add `-p` / `--profile` to lein-skeptic's CLI options. Produces `:profile true` in the opts map. Composes orthogonally with all other flags.

2. **Create and configure a JFR recording**: Instantiate a Recording with execution sampling, allocation sampling, and CPU load events enabled. Use JFR's built-in "profile" configuration preset.

3. **Start the recording**: Begin capturing events. Must happen before the analysis work begins.

4. **Run the analysis**: Call `check-project` (or equivalent entry point) normally. The analysis is unaware of the profiler.

5. **Stop the recording**: End event capture. Must happen after analysis completes. Must be in a `finally` block so exceptions don't lose captured data.

6. **Dump the raw file**: Write the Recording to `target/skeptic-profile.jfr`.

7. **Read events from the recording**: Iterate over the dumped `.jfr` file, extracting execution samples and allocation samples with their stack traces.

8. **Demangle Clojure function names**: Transform JVM method names (e.g., `my.ns$my_fn`) to Clojure-style names (e.g., `my.ns/my-fn`). Best-effort: handle common patterns (named functions, namespaced functions). Fall back to raw JVM name for unusual cases (anonymous functions, protocol methods, etc.).

9. **Aggregate into Profile Data**: For each function observed in stack traces, count self samples, total samples, and allocation bytes. Compute percentages relative to totals.

10. **Render the Summary Report**: Format Profile Data into a readable text table. Print to stdout after the normal plugin output.

11. **Sequence before exit**: Ensure capabilities 5-10 all complete before `System/exit` is called. The profiling wrapper must control the exit timing.

## Process Flow

### Normal flow (no `-p`):
```
CLI parse -> opts (no :profile) -> eval-in-project -> check-project -> output -> System/exit
```
Identical to today. Zero profiling code executed.

### Profiled flow (`-p` present):
```
CLI parse -> opts {:profile true} -> eval-in-project ->
  profiling wrapper:
    1. Create & start Recording
    2. try: check-project (normal output)
       finally:
         3. Stop Recording
         4. Dump .jfr to target/skeptic-profile.jfr
         5. Read events from .jfr
         6. Aggregate into Profile Data
         7. Print Summary Report
    8. System/exit (with same exit code as normal)
```

## Domain Glossary

| Term | Definition |
|------|-----------|
| **JFR** | Java Flight Recorder. A profiling and diagnostics framework built into the JDK (11+). Captures events via sampling with low overhead. |
| **Execution sample** | A JFR event (`jdk.ExecutionSample`) that snapshots the call stack at a regular interval. The primary source for time and CPU attribution. |
| **Allocation sample** | A JFR event (`jdk.ObjectAllocationSample`) that fires when a thread's allocation crosses a threshold. Contains stack trace and allocation size. |
| **Self time / self samples** | Time (or sample count) spent in a function's own code, not in functions it calls. Indicates the function itself is doing expensive work. |
| **Total time / total samples** | Time (or sample count) spent in a function and everything it transitively calls. Indicates the function is on a hot path, whether the work is its own or delegated. |
| **Demangling** | Transforming JVM-internal names (e.g., `my.ns$my_fn`) back to Clojure-readable names (e.g., `my.ns/my-fn`). Best-effort with fallback to raw names. |
| **eval-in-project** | Leiningen's mechanism for running code in the target project's JVM (with the project's classpath), as opposed to Leiningen's own plugin JVM. |
| **TLAB** | Thread-Local Allocation Buffer. JVM memory allocation mechanism. JFR allocation sampling triggers at TLAB boundaries, making it statistical rather than exact. |

## Identified Gaps and Unknowns

1. **Clojure demangling edge cases**: Anonymous functions (`fn__12345`), protocol method implementations, multimethod dispatch, `reify`, `proxy`, and lazy-seq thunks all produce different JVM name patterns. The demangler handles common named-function patterns and falls back to raw names otherwise. This is acceptable but means some entries in the summary may not be immediately recognizable as Clojure code.

2. **JFR "profile" preset details**: The exact sampling interval and allocation threshold in JFR's built-in "profile" configuration may vary across JDK versions. We rely on the JDK's defaults being reasonable. If tuning is needed, it becomes future work.

3. **`System/exit` sequencing**: Currently `check-project` calls `System/exit` directly. The profiling wrapper needs to either intercept this or restructure the call so that profiling cleanup happens before exit. The exact mechanism is an architectural decision for Stage 3.

## Dependencies Between Concepts

- Summary Report depends on Profile Data
- Profile Data depends on reading Profiling Events from the Recording
- Raw Output File depends on Recording being stopped and dumped
- Recording lifecycle depends on Profiling Session existing
- Profiling Session depends on `:profile` being in opts
- Demangling is applied during the aggregation step (building Profile Data)
- All profiling output (summary + raw file) depends on completing before `System/exit`

## Open Questions for Next Stage

1. Where should the profiling namespace live? (`skeptic.profiling`? `skeptic.profile`? Other?)
2. How to handle the `System/exit` sequencing — does the profiling wrapper replace the direct call, or does `check-project` gain a return value instead of exiting?
3. Exactly which JFR configuration to use — the "profile" preset or custom event settings?
4. Should the profiling namespace be a dependency of lein-skeptic only, or available as a general utility in skeptic's public API?

## Accepted Trade-offs

1. **Flat aggregation over call-tree model**: Profile Data is per-function, not a call tree. The raw `.jfr` file preserves the full tree for deeper analysis.
2. **Specific over general**: The profiling namespace is shaped for the skeptic plugin's use case, not as a general-purpose profiling library.
3. **Best-effort demangling**: Common Clojure function patterns are demangled; unusual cases fall back to raw JVM names.
4. **Single recording**: All event types captured in one recording, not separate passes.
5. **Bundled summary rendering**: Summary formatting lives alongside event reading and aggregation, not as a separate pluggable concern.
