# skeptic-profiling — Intention

**Status:** Approved
**Date:** 2026-04-03
**Previous stage:** N/A

---

## Summary

Add a `-p` / `--profile` flag to the lein-skeptic plugin that enables JVM-level sampling profiling for the duration of a plugin run. The profiler captures wall-clock time, memory allocation, and CPU consumption at function granularity, prints an actionable summary to stdout, and writes a raw data file to disk. When `-p` is absent, the feature has zero overhead. No changes to the skeptic library's source code are required.

## Problem Statement

Running `lein skeptic` on a project has noticeable performance variation across different sections of the analysis pipeline. There is currently no built-in way to determine which functions are responsible for slowness or excessive memory allocation. External profiling tools exist but require a separate workflow, separate tooling setup, and produce output that is disconnected from the plugin's own execution. The library author needs a single-command, opt-in profiling mode integrated into the plugin itself.

## Goals (ordered by priority)

1. **Wall-clock time visibility:** Identify which functions consume the most elapsed time during a plugin run. This is the primary metric.
2. **Memory allocation visibility:** Identify which functions allocate the most memory, contributing to GC pressure and indirectly to slowness. This is the secondary metric.
3. **CPU consumption visibility:** Identify which functions burn the most CPU. Tertiary metric, captured because it is essentially free alongside the above.
4. **Actionable summary output:** Print a human-readable summary to stdout after the normal plugin output. The summary must contain enough detail to go directly from the output to the code that needs changing — fully-qualified function names, sample counts, percentage of total, and allocation volume — so the reader can immediately judge where to focus and how large the potential improvement is.
5. **Raw data file to disk:** Write the profiler's native output format to `target/skeptic-profile.<ext>` for precise offline analysis with standard external tools.
6. **Zero overhead when off:** When `-p` is absent, the profiler is never started. No wrapping, no dynamic var checks, no runtime cost.
7. **Orthogonal composition:** `-p` composes with all existing flags (`-v`, `-a`, `-k`, `-c`, `-n`). It is purely additive — the plugin runs normally, profiling is just also happening.
8. **Automatic, non-invasive profiling:** JVM-level sampling captures all function calls on the stack without requiring per-function instrumentation in the skeptic library. If a function is called, it appears in the profile.
9. **Standard raw format:** The raw data file uses a standard, widely-supported format that can be converted to other formats or consumed by common profiling tools.

## Non-Goals

- No manual per-function instrumentation in the skeptic library source.
- No built-in comparative run / diff tooling (comparing two profile outputs).
- No built-in flame graph rendering (the raw file can be fed to external tools for that).
- No REPL-facing profiling API exposed from the skeptic library.
- No changes to the skeptic library's analysis behavior, correctness, or output format.
- No custom profiling data format — use the profiler's native/standard format.

## Success Criteria

1. `lein skeptic -p` runs the normal analysis pipeline, then prints a profiling summary and writes a raw data file.
2. `lein skeptic` (without `-p`) has zero measurable overhead from the profiling feature's existence.
3. The summary identifies hot functions by wall-clock time, memory allocation, and CPU, with enough detail (qualified name, counts, percentages) to go straight to the source code.
4. The raw data file is consumable by standard profiling tools for deeper analysis.
5. No source changes are required in the `skeptic/` library — all profiling orchestration lives in `lein-skeptic`.
6. `-p` composes correctly with every other existing flag.

## Scope Boundaries

**In scope:**
- Adding `-p` / `--profile` to lein-skeptic's CLI options
- Starting a JVM-level sampling profiler before analysis and stopping it after
- Generating and printing the summary
- Writing the raw file to `target/skeptic-profile.<ext>`
- Ensuring profiling completes before `System/exit` is called

**Out of scope:**
- Any changes to the skeptic library's source files
- Flame graph generation
- Profile comparison tooling
- REPL-facing APIs
- Custom output formats

## Key Assumptions

- Sampling-based profiling provides sufficient accuracy for identifying performance bottlenecks. Very short, infrequently-called functions may not appear — this is acceptable.
- The profiler runs in the project JVM (created by `eval-in-project`), not in Leiningen's own JVM.
- The raw output file goes to `target/skeptic-profile.<ext>` in the target project's root directory.
- The profiling summary and raw file write must complete before `System/exit` is called by `get-project-schemas`. This is a sequencing constraint on the implementation.

## Accepted Trade-offs

1. **Sampling vs. tracing:** Sampling gives statistical accuracy at low cost. Very short, infrequently-called functions may not appear. Accepted because anything eating meaningful time will be sampled.
2. **Summary detail vs. implementation scope:** The summary needs to be actionable (qualified names, counts, percentages, allocation volume) but does not need to be award-winning. A clear, functional table is sufficient.
3. **Portability over depth:** Prefer a portable profiler approach over one requiring platform-specific native agents. Solving the resource issue matters more than maximum profiling fidelity.
4. **Standard format over custom:** Use the profiler's native output format. If it is standard enough to be translated to other formats, that is sufficient.
5. **Profiling overhead when profiling:** Sampling profilers add ~1-5% overhead to the profiled run. This is inherent, acceptable, and only present when `-p` is used.

## Glossary

- **Sampling profiler:** A profiler that periodically snapshots the JVM's call stack (typically every 1-10ms) rather than instrumenting every function entry/exit. Produces statistical data about where time is spent.
- **Wall-clock time:** Elapsed real time, including time spent waiting (I/O, GC pauses, etc.). Distinct from CPU time.
- **Memory allocation:** The volume of heap memory allocated by a function (not retained — allocated). High allocation rates cause GC pressure.
- **Raw data file:** The profiler's native output file, in a standard format, written to disk for offline analysis with external tools.
- **eval-in-project:** Leiningen's mechanism for running code in the target project's JVM with the project's classpath, as opposed to Leiningen's own plugin JVM.
