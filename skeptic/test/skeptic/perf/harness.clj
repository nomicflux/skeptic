(ns skeptic.perf.harness
  "Helpers for repeatable performance probes.

   Probes run only when SKEPTIC_PROBE is set so the default `lein test`
   stays a correctness check.

   `measure` runs the thunk under a real benchmarking protocol: warm up,
   then take N independent samples, each a fixed-iter timed loop of the
   same work; report a distribution (n, median, mean, std-dev, min, max,
   95% CI half-width) plus per-sample allocation. A single sample is a
   draw from a noisy distribution and is not a finding on its own — only
   the spread tells you whether two configurations differ.

   Configuration via env (defaults are safe for in-process work):
     SKEPTIC_PROBE        — gates probes on (1 = run, anything else = skip).
     SKEPTIC_PROBE_SAMPLES — sample count N. Default 30.
     SKEPTIC_PROBE_ITERS   — iterations per sample. Default chosen so each
                             sample lasts roughly the supplied min-wall-ms.")

(defn enabled? []
  (= "1" (System/getenv "SKEPTIC_PROBE")))

(defn- env-long [k default]
  (let [v (System/getenv k)]
    (if (and v (not= "" v))
      (try (Long/parseLong v) (catch NumberFormatException _ default))
      default)))

(defn- sample-count [] (env-long "SKEPTIC_PROBE_SAMPLES" 30))

(defn- explicit-iters [] (env-long "SKEPTIC_PROBE_ITERS" -1))

(defn- thread-mx []
  (java.lang.management.ManagementFactory/getThreadMXBean))

(defn- alloc-mx []
  (let [b (thread-mx)]
    (when (and (instance? com.sun.management.ThreadMXBean b)
               (.isThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean b))
      b)))

(defn- now-ns [] (System/nanoTime))

(defn- alloc-bytes []
  (when-let [b (alloc-mx)]
    (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean b
                              (.getId (Thread/currentThread)))))

(defn- pick-iters
  "Choose iterations-per-sample so one sample takes roughly
   `target-wall-ms`. Calibrate by running a small loop and scaling.
   SKEPTIC_PROBE_ITERS overrides to a fixed value (use for cross-config
   comparisons where you want each sample to measure exactly the same
   amount of work)."
  [target-wall-ms thunk sink]
  (let [override (explicit-iters)]
    (if (pos? override)
      override
      (let [probe-iters 100
            t0 (now-ns)
            _ (dotimes [_ probe-iters] (reset! sink (thunk)))
            elapsed-ns (max 1 (- (now-ns) t0))
            ns-per-op (/ (double elapsed-ns) probe-iters)
            target-ns (* target-wall-ms 1000000.0)
            n (long (max 100 (Math/ceil (/ target-ns ns-per-op))))]
        (min n 100000000)))))

(defn- one-sample
  "Run `iters` invocations of thunk, return {:ns-per-op N :bytes-per-op N
   :total-ns N}. Allocation is read once at start and end of the loop."
  [iters thunk sink]
  (let [start-alloc (alloc-bytes)
        start-ns (now-ns)]
    (dotimes [_ iters] (reset! sink (thunk)))
    (let [end-ns (now-ns)
          end-alloc (alloc-bytes)
          total-ns (- end-ns start-ns)
          ns-per-op (/ (double total-ns) iters)
          bytes-per-op (when (and start-alloc end-alloc)
                         (/ (double (- end-alloc start-alloc)) iters))]
      {:ns-per-op ns-per-op
       :bytes-per-op bytes-per-op
       :total-ns total-ns})))

(defn- median [sorted]
  (let [n (count sorted)]
    (if (odd? n)
      (nth sorted (quot n 2))
      (/ (+ (nth sorted (dec (quot n 2))) (nth sorted (quot n 2))) 2.0))))

(defn- mean [xs]
  (/ (reduce + 0.0 xs) (double (count xs))))

(defn- stddev [xs xbar]
  (if (< (count xs) 2)
    0.0
    (let [n (count xs)
          sumsq (reduce (fn [s x] (let [d (- x xbar)] (+ s (* d d)))) 0.0 xs)]
      (Math/sqrt (/ sumsq (double (dec n)))))))

(defn- ci95-halfwidth
  "Conservative 95% confidence-interval half-width on the mean: 2 * s /
   sqrt(n). For n>=30 the normal-approx multiplier 1.96 is standard;
   2.0 is the conservative round number that also approximates the
   t-distribution multiplier for smaller n. Not a substitute for
   reporting n explicitly."
  [s n]
  (if (< n 2) 0.0 (/ (* 2.0 s) (Math/sqrt (double n)))))

(defn- summarize [samples]
  (let [ns-vals (vec (sort (map :ns-per-op samples)))
        b-vals (->> samples (keep :bytes-per-op) (sort) vec)
        n (count ns-vals)
        ns-mean (mean ns-vals)
        ns-sd (stddev ns-vals ns-mean)
        ns-ci (ci95-halfwidth ns-sd n)
        ns-min (first ns-vals)
        ns-max (last ns-vals)
        ns-med (median ns-vals)
        b-mean (when (seq b-vals) (mean b-vals))
        b-sd (when (seq b-vals) (stddev b-vals b-mean))
        b-med (when (seq b-vals) (median b-vals))]
    {:n n
     :ns-median ns-med :ns-mean ns-mean :ns-stddev ns-sd :ns-ci95 ns-ci
     :ns-min ns-min :ns-max ns-max
     :bytes-median b-med :bytes-mean b-mean :bytes-stddev b-sd}))

(defn measure
  "Run a benchmarking protocol on `thunk` and print a distribution.

     - Warm-up: 1000 invocations (lets JIT compile).
     - Calibration: pick iterations-per-sample so each sample takes
       roughly `target-wall-ms` ms.
     - Repeat: take N samples (SKEPTIC_PROBE_SAMPLES, default 30) of
       that same iteration count. Each sample is one independent
       fixed-iter timed loop, so samples are drawn from the same work.

   Reports n, median, mean, std-dev, 95% CI half-width on the mean,
   and min/max in ns/op; mean and std-dev for bytes/op. A single
   number is not a finding. Two configurations differ only if their
   CIs do not overlap.

   The thunk's return value is consumed via an atom so the JIT cannot
   dead-code-eliminate it. Schema fn-validation is forced off so the
   measurement reflects production (cli/main wraps the real run in
   `s/without-fn-validation`); prior state is restored in `finally`.

   Returns the summary map."
  [label target-wall-ms thunk]
  (let [sink (atom nil)
        validate-fn (resolve 'schema.core/set-fn-validation!)
        was-on? (some-> (resolve 'schema.core/fn-validation?) deref boolean)]
    (when validate-fn (validate-fn false))
    (try
      (dotimes [_ 1000] (reset! sink (thunk)))
      (let [iters (pick-iters target-wall-ms thunk sink)
            n (sample-count)
            samples (vec (repeatedly n #(one-sample iters thunk sink)))
            {:keys [ns-median ns-mean ns-stddev ns-ci95 ns-min ns-max
                    bytes-median bytes-mean bytes-stddev] :as summary}
            (summarize samples)
            rel-sd (if (pos? ns-mean) (* 100.0 (/ ns-stddev ns-mean)) 0.0)]
        (println
         (format
          (str "[PROBE] %-50s n=%d iters/sample=%d  "
               "ns/op median=%.0f mean=%.0f sd=%.0f ci95=±%.0f (%.1f%%) "
               "min=%.0f max=%.0f  "
               "bytes/op median=%s mean=%s sd=%s  sink=%s")
          label n iters
          ns-median ns-mean ns-stddev ns-ci95 rel-sd
          ns-min ns-max
          (if bytes-median (format "%.0f" bytes-median) "n/a")
          (if bytes-mean (format "%.0f" bytes-mean) "n/a")
          (if bytes-stddev (format "%.0f" bytes-stddev) "n/a")
          (if (some? @sink) "non-nil" "nil")))
        (assoc summary :label label :iters-per-sample iters))
      (finally
        (when (and validate-fn was-on?) (validate-fn true))))))

(defn compare-configs
  "Helper for the only kind of comparison the harness supports: do two
   sample distributions have non-overlapping 95% CIs on the mean? Pass
   the maps returned by `measure`. Returns
   {:separated? bool :delta-mean N :a-ci95 N :b-ci95 N}.

   Non-overlapping CIs is a *necessary* condition for a finding, not
   sufficient — two configurations whose CIs barely overlap can still
   differ; the test is conservative on the side of declaring 'no
   difference'."
  [a b]
  (let [da (:ns-mean a)
        db (:ns-mean b)
        ca (:ns-ci95 a)
        cb (:ns-ci95 b)
        lo-a (- da ca) hi-a (+ da ca)
        lo-b (- db cb) hi-b (+ db cb)
        separated? (or (< hi-a lo-b) (< hi-b lo-a))]
    {:separated? separated?
     :delta-mean (- db da)
     :a-mean da :a-ci95 ca
     :b-mean db :b-ci95 cb}))
