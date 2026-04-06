# skeptic-profiling — Implementation Plan

**Status:** Under Review
**Date:** 2026-04-03
**Previous stages:** [intention.md](intention.md) | [creation.md](creation.md) | [formation.md](formation.md)

---

## Context

The lein-skeptic plugin runs static type checking for projects that use Plumatic Schema on Clojure projects. Certain sections of the analysis pipeline run slower than others, and there is no built-in way to determine which functions are responsible. External profiling tools require a separate workflow.

This project adds a `-p` / `--profile` flag to lein-skeptic that enables JVM-level sampling profiling via Java Flight Recorder (JFR) for the duration of a plugin run. It captures wall-clock time, memory allocation, and CPU consumption at function granularity, prints an actionable summary to stdout, and writes a raw `.jfr` file to disk. When `-p` is absent, the feature has zero overhead.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│ Leiningen JVM                                                │
│                                                              │
│  leiningen.skeptic                                           │
│    - parses CLI args (adds -p/--profile)                     │
│    - builds eval-in-project form                             │
│    - passes opts, root, paths into the project JVM           │
└─────────────────────┬────────────────────────────────────────┘
                      │ eval-in-project
                      v
┌──────────────────────────────────────────────────────────────┐
│ Project JVM                                                  │
│                                                              │
│  skeptic.profiling/run                                       │
│    - if :profile: start JFR, run thunk, stop JFR,           │
│      dump .jfr, read events, aggregate, print summary        │
│    - if no :profile: call thunk directly (pass-through)      │
│    - returns exit code                                       │
│                                                              │
│  skeptic.core/check-project                                  │
│    - the analysis work (called as the thunk)                 │
│    - returns 0 (no errors) or 1 (errors found)               │
│                                                              │
│  System/exit called after run returns                         │
└──────────────────────────────────────────────────────────────┘
```

Three components: `leiningen.skeptic` (CLI + orchestration), `skeptic.core` (minimal refactoring), `skeptic.profiling` (new, all JFR + output). `skeptic.profiling` has no dependency on any `skeptic.*` namespace.

## Phased Implementation

### Phase 1: `skeptic.core` Refactoring

**Files to modify:** `skeptic/src/skeptic/core.clj`

**Purpose:** Separate "do the analysis work" from "exit the process" so the profiling wrapper can run between them.

#### Function: `check-project`

**Signature:**

```clojure
(defn check-project
  [{:keys [verbose show-context namespace analyzer] :as opts} root & paths]
  ...)
```

**Parameters:**
- `opts` — map of CLI options (`:verbose`, `:show-context`, `:namespace`, `:analyzer`, `:keep-empty`, `:profile`)
- `root` — string, the project root directory
- `paths` — varargs strings, source and test paths

**Returns:** integer `0` (no errors found) or `1` (errors found)

**Body:** Identical to the current `check-project`, with two changes:
1. Replace `(System/exit 1)` with `1`
2. Replace `(do (println "No inconsistencies found") (System/exit 0))` with `(do (println "No inconsistencies found") 0)`

#### Function: `check-project` (backward-compatible wrapper)

```clojure
(defn check-project
  [opts root & paths]
  (System/exit (apply check-project opts root paths)))
```

**Acceptance criteria:**
- `lein test` passes with no changes to existing tests
- `lein skeptic` (invoked from a target project) produces identical output and exit behavior

---

### Phase 2: `skeptic.profiling` Namespace

**Files to create:**
- `skeptic/src/skeptic/profiling.clj`
- `skeptic/test/skeptic/profiling_test.clj`

#### Namespace Declaration

```clojure
(ns skeptic.profiling
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace])
  (:import [jdk.jfr Configuration Recording]
           [jdk.jfr.consumer RecordingFile RecordedEvent RecordedFrame]))
```

No dependency on any `skeptic.*` namespace. Uses only JDK-native JFR classes (available in JDK 17+) and standard Clojure libraries.

#### Function: `demangle-class` (private)

**Signature:** `(defn- demangle-class [^String class-name])`

**Parameters:** `class-name` — JVM class name string (e.g., `"skeptic.core$check_project"`)

**Returns:** Clojure-style function name string (e.g., `"skeptic.core/check-project"`), or the raw class name if demangling fails.

**Algorithm:**

```clojure
(defn- demangle-class [^String class-name]
  (if-let [idx (str/index-of class-name "$")]
    (let [ns-part (subs class-name 0 idx)
          fn-part (subs class-name (inc idx))]
      (cond
        (re-matches #"fn__\d+" fn-part)
        (str ns-part "/<fn>")

        (re-matches #"eval\d+.*" fn-part)
        (str ns-part "/<eval>")

        (str/includes? fn-part "$")
        (let [inner-idx (str/index-of fn-part "$")
              outer (subs fn-part 0 inner-idx)]
          (str ns-part "/" (str/replace outer "_" "-") "/<inner>"))

        :else
        (str ns-part "/" (str/replace fn-part "_" "-"))))
    class-name))
```

**Test cases:**
- `"skeptic.core$check_project"` -> `"skeptic.core/check-project"`
- `"skeptic.analysis.cast$cast_type"` -> `"skeptic.analysis.cast/cast-type"`
- `"clojure.core$assoc"` -> `"clojure.core/assoc"`
- `"skeptic.core$fn__12345"` -> `"skeptic.core/<fn>"`
- `"skeptic.core$eval12345"` -> `"skeptic.core/<eval>"`
- `"skeptic.core$check_project$inner__456"` -> `"skeptic.core/check-project/<inner>"`
- `"java.lang.String"` -> `"java.lang.String"` (no `$`, returned as-is)

#### Function: `frame->function-name` (private)

**Signature:** `(defn- frame->function-name [^RecordedFrame frame])`

**Returns:** demangled function name string

**Body:**

```clojure
(defn- frame->function-name [^RecordedFrame frame]
  (-> frame .getMethod .getType .getName demangle-class))
```

#### Function: `format-bytes` (private)

**Signature:** `(defn- format-bytes [n])`

**Returns:** human-readable byte string (e.g., `"1.2 GB"`, `"345.6 MB"`, `"12.3 KB"`)

**Body:**

```clojure
(defn- format-bytes [n]
  (cond
    (>= n 1073741824) (format "%.1f GB" (double (/ n 1073741824)))
    (>= n 1048576)    (format "%.1f MB" (double (/ n 1048576)))
    (>= n 1024)       (format "%.1f KB" (double (/ n 1024)))
    :else              (str n " B")))
```

**Test cases:**
- `0` -> `"0 B"`
- `512` -> `"512 B"`
- `1024` -> `"1.0 KB"`
- `1536` -> `"1.5 KB"`
- `1048576` -> `"1.0 MB"`
- `1073741824` -> `"1.0 GB"`

#### Function: `extract-execution-frames` (private)

**Signature:** `(defn- extract-execution-frames [^RecordedEvent event])`

**Returns:** vector of function name strings (top of stack first), or nil if no stack trace

**Body:**

```clojure
(defn- extract-execution-frames [^RecordedEvent event]
  (when-let [st (.getStackTrace event)]
    (let [frames (.getFrames st)]
      (->> frames
           (filter #(.isJavaFrame %))
           (mapv frame->function-name)))))
```

#### Function: `aggregate-frame-data` (private, pure, testable)

**Signature:** `(defn- aggregate-frame-data [frame-seqs])`

**Parameters:** `frame-seqs` — sequence of vectors, each vector being a stack trace (top-of-stack first) of function name strings

**Returns:**

```clojure
{:total-samples <int>
 :per-fn {"fn-name" {:self <int> :total <int>} ...}}
```

**Algorithm:**

```clojure
(defn- aggregate-frame-data [frame-seqs]
  (let [total (count frame-seqs)]
    {:total-samples total
     :per-fn
     (reduce
       (fn [acc frames]
         (let [self-fn (first frames)
               all-fns (distinct frames)]
           (as-> acc a
             (update-in a [self-fn :self] (fnil inc 0))
             (reduce (fn [a2 fn-name]
                       (update-in a2 [fn-name :total] (fnil inc 0)))
                     a all-fns))))
       {}
       frame-seqs)}))
```

**Test cases:**
- Empty input: `{:total-samples 0 :per-fn {}}`
- Single trace `[["a" "b" "c"]]`: a gets self=1,total=1; b gets total=1; c gets total=1
- Two traces `[["a" "b"] ["b" "c"]]`: a gets self=1,total=1; b gets self=1,total=2; c gets total=1
- Duplicate in trace `[["a" "a" "b"]]`: a gets self=1,total=1 (distinct); b gets total=1

#### Function: `extract-allocation-frames` (private)

**Signature:** `(defn- extract-allocation-frames [^RecordedEvent event])`

**Returns:** `{:top-fn <string> :weight <long>}` or nil

**Body:**

```clojure
(defn- extract-allocation-frames [^RecordedEvent event]
  (when-let [st (.getStackTrace event)]
    (let [frames (.getFrames st)
          java-frames (filter #(.isJavaFrame %) frames)]
      (when (seq java-frames)
        {:top-fn (frame->function-name (first java-frames))
         :weight (.getLong event "weight")}))))
```

#### Function: `aggregate-alloc-data` (private, pure, testable)

**Signature:** `(defn- aggregate-alloc-data [alloc-entries])`

**Parameters:** `alloc-entries` — sequence of `{:top-fn <string> :weight <long>}`

**Returns:**

```clojure
{:total-bytes <long>
 :per-fn {"fn-name" {:alloc-bytes <long>} ...}}
```

**Algorithm:**

```clojure
(defn- aggregate-alloc-data [alloc-entries]
  (let [total (reduce + 0 (map :weight alloc-entries))]
    {:total-bytes total
     :per-fn
     (reduce
       (fn [acc {:keys [top-fn weight]}]
         (update-in acc [top-fn :alloc-bytes] (fnil + 0) weight))
       {}
       alloc-entries)}))
```

**Test cases:**
- Empty input: `{:total-bytes 0 :per-fn {}}`
- Two entries same fn: bytes summed
- Two entries different fns: separate entries

#### Function: `aggregate-cpu-load` (private)

**Signature:** `(defn- aggregate-cpu-load [events])`

**Returns:** `{:jvm-user-avg <double> :jvm-system-avg <double>}` or `nil` if no events

**Body:**

```clojure
(defn- aggregate-cpu-load [events]
  (when (seq events)
    (let [n (count events)]
      {:jvm-user-avg   (/ (reduce + (map #(.getDouble % "jvmUser") events)) n)
       :jvm-system-avg (/ (reduce + (map #(.getDouble % "jvmSystem") events)) n)})))
```

#### Function: `build-profile-data` (private)

**Signature:** `(defn- build-profile-data [events-by-type duration-ms])`

**Parameters:**
- `events-by-type` — map of event type name to seq of RecordedEvents
- `duration-ms` — recording duration in milliseconds

**Returns:**

```clojure
{:execution {:total-samples <int>
             :per-fn {"fn" {:self <int> :total <int> :self-pct <double> :total-pct <double>}}}
 :allocation {:total-bytes <long>
              :per-fn {"fn" {:alloc-bytes <long> :alloc-pct <double>}}}
 :cpu {:jvm-user-avg <double> :jvm-system-avg <double>}  ; or nil
 :duration-ms <long>}
```

**Body:**

```clojure
(defn- build-profile-data [events-by-type duration-ms]
  (let [exec-frames (->> (get events-by-type "jdk.ExecutionSample")
                         (keep extract-execution-frames))
        exec-data (aggregate-frame-data exec-frames)
        total-samples (:total-samples exec-data)

        alloc-entries (->> (get events-by-type "jdk.ObjectAllocationSample")
                          (keep extract-allocation-frames))
        alloc-data (aggregate-alloc-data alloc-entries)
        total-bytes (:total-bytes alloc-data)

        add-exec-pcts (fn [per-fn]
                        (into {}
                          (map (fn [[k v]]
                                 [k (assoc v
                                      :self-pct  (if (pos? total-samples)
                                                   (* 100.0 (/ (:self v 0) total-samples))
                                                   0.0)
                                      :total-pct (if (pos? total-samples)
                                                   (* 100.0 (/ (:total v 0) total-samples))
                                                   0.0))]))
                          per-fn))

        add-alloc-pcts (fn [per-fn]
                         (into {}
                           (map (fn [[k v]]
                                  [k (assoc v
                                       :alloc-pct (if (pos? total-bytes)
                                                    (* 100.0 (/ (:alloc-bytes v 0) total-bytes))
                                                    0.0))]))
                           per-fn))]

    {:execution  {:total-samples total-samples
                  :per-fn (add-exec-pcts (:per-fn exec-data))}
     :allocation {:total-bytes total-bytes
                  :per-fn (add-alloc-pcts (:per-fn alloc-data))}
     :cpu        (aggregate-cpu-load (get events-by-type "jdk.CPULoad"))
     :duration-ms duration-ms}))
```

#### Function: `print-summary` (private)

**Signature:** `(defn- print-summary [profile-data jfr-path])`

**Side effects:** prints to stdout

**Output format:**

```
=== Profiling Summary ===
Duration:            12.3s
Execution samples:   4521
Allocation weight:   1.2 GB
Avg CPU (jvm user):  85.2%
Avg CPU (jvm system): 3.1%

--- Top Functions by Self Time ---
  Self%  Total%   Self#  Total#  Function
  23.4%   45.2%    1058    2043  skeptic.analysis.cast/cast-type
  12.1%   12.1%     547     547  clojure.core/assoc
   8.7%   32.4%     393    1464  skeptic.checking.pipeline/check-form
   ...

--- Top Functions by Allocation ---
  Alloc%  Weight       Function
  34.2%   412.3 MB     skeptic.checking.pipeline/check-form
  18.7%   225.6 MB     clojure.core/concat
   ...

Raw profile data: target/skeptic-profile.jfr
```

**Algorithm:**

```clojure
(defn- print-summary [profile-data jfr-path]
  (let [{:keys [execution allocation cpu duration-ms]} profile-data
        duration-s (/ duration-ms 1000.0)]
    (println "\n=== Profiling Summary ===")
    (println (format "Duration:            %.1fs" duration-s))
    (println (format "Execution samples:   %d" (:total-samples execution)))
    (println (str    "Allocation weight:   " (format-bytes (:total-bytes allocation))))
    (when cpu
      (println (format "Avg CPU (jvm user):  %.1f%%" (* 100.0 (:jvm-user-avg cpu))))
      (println (format "Avg CPU (jvm system): %.1f%%" (* 100.0 (:jvm-system-avg cpu)))))

    (println "\n--- Top Functions by Self Time ---")
    (if (pos? (:total-samples execution))
      (do
        (println "  Self%  Total%   Self#  Total#  Function")
        (doseq [[fn-name data] (->> (:per-fn execution)
                                    (sort-by (comp - :self val))
                                    (take 30))]
          (println (format "  %5.1f%%  %5.1f%%  %6d  %6d  %s"
                           (:self-pct data)
                           (:total-pct data)
                           (:self data 0)
                           (:total data 0)
                           fn-name))))
      (println "  (no execution samples captured)"))

    (println "\n--- Top Functions by Allocation ---")
    (if (pos? (:total-bytes allocation))
      (do
        (println "  Alloc%  Weight       Function")
        (doseq [[fn-name data] (->> (:per-fn allocation)
                                    (sort-by (comp - :alloc-bytes val))
                                    (take 20))]
          (println (format "  %5.1f%%  %-12s %s"
                           (:alloc-pct data)
                           (format-bytes (:alloc-bytes data))
                           fn-name))))
      (println "  (no allocation samples captured)"))

    (println (str "\nRaw profile data: " jfr-path))))
```

#### Function: `run` (public)

**Signature:** `(defn run [opts target-dir work-fn])`

**Parameters:**
- `opts` — map of CLI options. Checks `(:profile opts)`.
- `target-dir` — string, directory path for the `.jfr` output file (e.g., `"/path/to/project/target"`)
- `work-fn` — zero-arg function (thunk). Expected to return an integer exit code.

**Returns:** integer exit code from `work-fn`

**Contract:**
- If `(:profile opts)` is falsy, calls `(work-fn)` directly and returns its result. No JFR classes loaded.
- If `(:profile opts)` is truthy, wraps `(work-fn)` with JFR recording start/stop, dumps `.jfr`, reads events, prints summary.
- If JFR fails to start, prints a warning with full stack trace, falls back to calling `(work-fn)` unprofiled.
- If post-recording processing fails, prints a warning with full stack trace. Exit code is still returned.

**Body:**

```clojure
(defn run [opts target-dir work-fn]
  (if-not (:profile opts)
    (work-fn)
    (let [jfr-file (io/file target-dir "skeptic-profile.jfr")
          jfr-path (.toPath jfr-file)]
      (try
        (.mkdirs (io/file target-dir))
        (let [config    (Configuration/getConfiguration "profile")
              recording (Recording. config)
              start-ts  (System/nanoTime)]
          (try
            (.start recording)
            (let [exit-code (try
                              (work-fn)
                              (finally
                                (.stop recording)
                                (.dump recording jfr-path)
                                (.close recording)))
                  end-ts    (System/nanoTime)
                  duration  (/ (- end-ts start-ts) 1000000)]
              (try
                (let [events    (RecordingFile/readAllEvents jfr-path)
                      by-type   (group-by #(-> % .getEventType .getName) events)
                      prof-data (build-profile-data by-type duration)]
                  (print-summary prof-data (.getPath jfr-file)))
                (catch Exception e
                  (println "WARNING: Failed to generate profiling summary:")
                  (stacktrace/print-stack-trace e)
                  (println (str "Raw profile data may still be available at: " (.getPath jfr-file)))))
              exit-code)
            (catch Exception e
              (.close recording)
              (throw e))))
        (catch Exception e
          (println "WARNING: Profiling failed to start, running without profiling:")
          (stacktrace/print-stack-trace e)
          (work-fn))))))
```

**Acceptance criteria:**
- `lein test` passes (new profiling tests)
- Calling `(run {:profile true} "/tmp/test" #(do (Thread/sleep 100) 0))` from a REPL produces a `.jfr` file and prints a summary

---

### Phase 3: `leiningen.skeptic` Wiring

**Files to modify:** `lein-skeptic/src/leiningen/skeptic.clj`

#### Change 1: Add CLI option

```clojure
(def cli-options
  [["-v" "--verbose" "Turn on verbose logging"]
   ["-a" "--analyzer" "Use clojure.tools.analyzer to analyse code"]
   ["-k" "--keep-empty" "Print out checking results with empty error set"]
   ["-c" "--show-context" "Show context and resolution path on items"]
   ["-n" "--namespace NAMESPACE" "Only check specific namespace"]
   ["-p" "--profile" "Profile the run (CPU, memory, wall-clock time)"]
   ["-h" "--help"]])
```

#### Change 2: Update eval-in-project form and require

```clojure
(defn skeptic
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)
        paths (concat (:source-paths project) (:test-paths project))
        {{:keys [help errors] :as opts} :options summary :summary} (cli/parse-opts args cli-options)]
    (if (or help errors)
      (println summary)
      (leiningen.core.eval/eval-in-project
       (leiningen.core.project/merge-profiles project [profile])
       `(let [exit-code# (skeptic.profiling/run ~opts ~(str (:root project) "/target")
                           (fn [] (skeptic.core/check-project ~opts ~(:root project) ~@paths)))]
          (System/exit exit-code#))
       '(do (require 'skeptic.core) (require 'skeptic.profiling))))))
```

**Acceptance criteria:**
- `lein skeptic` (no `-p`) produces identical output and exit behavior to before
- `lein skeptic -p` produces normal output + profiling summary to stdout + `target/skeptic-profile.jfr` on disk
- `lein skeptic -p -n my.namespace` profiles only the specified namespace
- `lein skeptic -p -v` shows verbose output plus profiling summary
- `lein skeptic -h` shows all flags including `-p`

---

## Testing Strategy

### Unit Tests (`skeptic/test/skeptic/profiling_test.clj`)

Tests cover pure logic only. No JFR involvement.

**`test-demangle-class`:**
- Named function: `"skeptic.core$check_project"` -> `"skeptic.core/check-project"`
- Anonymous function: `"skeptic.core$fn__12345"` -> `"skeptic.core/<fn>"`
- Eval form: `"skeptic.core$eval12345"` -> `"skeptic.core/<eval>"`
- Nested/inner: `"skeptic.core$check_project$inner__456"` -> `"skeptic.core/check-project/<inner>"`
- Java class (no `$`): `"java.lang.String"` -> `"java.lang.String"`
- Underscores to hyphens: `"my.ns$my_long_fn_name"` -> `"my.ns/my-long-fn-name"`

**`test-aggregate-frame-data`:**
- Empty input -> zero samples, empty per-fn
- Single trace `[["a" "b" "c"]]` -> a: self=1, total=1; b: total=1; c: total=1
- Multiple traces with shared functions -> correct self and total counts
- Duplicate function in one trace -> counted once in total (distinct)

**`test-aggregate-alloc-data`:**
- Empty input -> zero bytes, empty per-fn
- Multiple entries, same function -> bytes summed
- Multiple entries, different functions -> separate entries with correct bytes

**`test-format-bytes`:**
- `0` -> `"0 B"`
- `1024` -> `"1.0 KB"`
- `1048576` -> `"1.0 MB"`
- `1073741824` -> `"1.0 GB"`
- `1536` -> `"1.5 KB"`

### Manual / Integration Testing

- Run `lein skeptic -p` against a real project and verify:
  - Normal output appears first
  - Profiling summary appears after
  - `target/skeptic-profile.jfr` is written and non-empty
  - The `.jfr` file opens in JDK Mission Control or VisualVM
  - Function names in the summary are recognizable Clojure names
- Run `lein skeptic` (without `-p`) and verify identical behavior to before the change
- Run `lein skeptic -p -n specific.ns` and verify scoped profiling works
- Run `lein skeptic -h` and verify `-p` appears in the help output

## Glossary

| Term | Definition |
|------|-----------|
| **JFR** | Java Flight Recorder. Profiling framework built into JDK 11+. Captures events via sampling. |
| **Execution sample** | JFR event (`jdk.ExecutionSample`) that snapshots the call stack at ~10ms intervals. |
| **Allocation sample** | JFR event (`jdk.ObjectAllocationSample`) that fires on TLAB boundary crossings. Weight field represents sampled allocation pressure. |
| **Self time** | Sample count where a function was at the top of the stack (doing its own work). |
| **Total time** | Sample count where a function appeared anywhere in the stack (itself + callees). |
| **Demangling** | Transforming JVM class names (`my.ns$my_fn`) to Clojure names (`my.ns/my-fn`). |
| **eval-in-project** | Leiningen mechanism for running code in the target project's JVM. |
| **TLAB** | Thread-Local Allocation Buffer. JFR samples allocations at TLAB boundaries. |
| **Thunk** | A zero-argument function used to defer execution. |

## Open Items

1. **JFR "profile" preset tuning:** The preset's defaults may not be optimal for all workloads. If sampling frequency or allocation threshold needs adjustment, this becomes a follow-up change to the Recording configuration.
2. **Demangling edge cases:** Protocol methods, multimethod dispatch, `reify`, `proxy`, and lazy-seq thunks produce unusual JVM names. The current demangler handles common named-function patterns and falls back to raw names. Demangling can be improved incrementally.
3. **Future: configurable top-N:** The summary shows top 30 by time and top 20 by allocation. These are hard-coded. A future flag (e.g., `--profile-top N`) could make them configurable.
4. **Future: machine-readable output:** An EDN or JSON summary alongside the text summary, for programmatic consumption.
5. **Future: comparative profiling:** Tooling to diff two `.jfr` profiles to measure the impact of a change.
