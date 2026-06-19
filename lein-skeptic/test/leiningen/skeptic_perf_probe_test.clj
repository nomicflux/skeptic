(ns leiningen.skeptic-perf-probe-test
  "Synthetic perf harness for the hermetic-host launcher.

   Three things the launcher does today that the pre-hermetic launcher
   did not:

     1. Three aether resolutions in series (locate-skeptic-jar,
        resolve-host-jars, resolve-worker-jars) where the old launcher
        ran one (resolve-worker-jars).

     2. A second JVM process — the host JVM — between lein and the
        worker JVM. Cold-start cost has to be added to every run.

     3. Serial spawn: worker JVM is spawned first, port handshake
        awaited, THEN host JVM is spawned. The host could start in
        parallel since it doesn't need the port until check-from-launcher
        reads stdin.

   The harness measures each in isolation against this repo's own
   project map. The aether probes run in-process. The JVM probes shell
   out to `java`; cold-start cost is not iterable in-process so each
   measurement is one fresh process. Each cold-start measurement is
   repeated N times so noise averages.

   Gated by SKEPTIC_PROBE=1 so the default `lein test` stays a
   correctness check."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [leiningen.core.classpath]
            [leiningen.skeptic :as sut]))

(defn- enabled? []
  (= "1" (System/getenv "SKEPTIC_PROBE")))

(defn- ms [start-ns]
  (/ (- (System/nanoTime) start-ns) 1.0e6))

(defn- probe-line [label samples]
  (let [sorted (vec (sort samples))
        n (count sorted)
        med (nth sorted (quot n 2))
        avg (/ (reduce + sorted) (double n))
        mn (first sorted)
        mx (last sorted)]
    (println (format "[PROBE] %-58s n=%d  median=%.0fms  mean=%.0fms  min=%.0fms  max=%.0fms"
                     label n med avg mn mx))))

;; -------- This repo's project map (for aether resolutions) ----------

(defn- this-project-map
  "Build a minimal project map that uses Maven Central as a fallback so
   aether resolution can run from this test. The hermetic-host launcher
   inherits :repositories from the user project; if the user project
   already has Central wired (almost all do), the synthetic project
   uses that. Here we just provide one."
  []
  {:root (System/getProperty "user.dir")
   :repositories [["central"  {:url "https://repo1.maven.org/maven2/"}]
                  ["clojars"  {:url "https://repo.clojars.org/"}]]})

;; -------- (1) Aether resolution probes -------------------------------

(defn- measure-aether [iters label thunk]
  ;; Aether caches at the local Maven layer. First call is cold (network
  ;; or m2 read); subsequent calls reuse the resolved metadata in memory
  ;; and on disk. Report cold + warm separately so the launcher's
  ;; one-shot resolution shows the COLD cost, which is what every fresh
  ;; `lein skeptic` invocation pays.
  (let [cold-start (System/nanoTime)
        _ (thunk)
        cold-ms (ms cold-start)
        warm-samples (vec (repeatedly iters #(let [t (System/nanoTime)
                                                   _ (thunk)]
                                               (ms t))))]
    (println (format "[PROBE] %-58s cold=%.0fms" label cold-ms))
    (probe-line (str label " (warm)") warm-samples)))

(defn- locate-skeptic-jar-thunk [project verbose?]
  (#'sut/locate-skeptic-jar project verbose?))

(defn- resolve-host-jars-thunk [project skeptic-jar verbose?]
  (#'sut/resolve-host-jars project skeptic-jar verbose?))

(defn- resolve-worker-jars-thunk [project skeptic-jar verbose?]
  (#'sut/resolve-worker-jars project skeptic-jar verbose?))

(defn- resolve-plugin-jars-thunk [project verbose?]
  (#'sut/resolve-plugin-jars project verbose?))

;; -------- (2) JVM cold-start probes ----------------------------------

(defn- run-process
  "Run `command`, drain stdout/stderr (so the child does not block on a
   full pipe), waitFor, return [exit-code wall-ms]."
  [^java.util.List command]
  (let [pb (ProcessBuilder. command)
        _ (.redirectErrorStream pb true)
        start-ns (System/nanoTime)
        proc (.start pb)
        ;; Drain output. Discarding is fine — we measure wall time only.
        drain (future
                (with-open [r (io/reader (.getInputStream proc))]
                  (doseq [_ (line-seq r)] nil)))
        exit (.waitFor proc)
        wall-ms (ms start-ns)]
    @drain
    [exit wall-ms]))

(defn- java-binary []
  (let [home (System/getProperty "java.home")]
    (let [exe (io/file home "bin" "java")]
      (.getPath exe))))

(defn- pure-jvm-floor-thunk []
  ;; `java -version` prints to stderr and exits. The cheapest possible
  ;; JVM run. Floor any other JVM cold-start measurement is bounded by.
  (run-process [(java-binary) "-version"]))

(defn- host-jvm-cold-start-thunk
  "Cold-start the host JVM with skeptic on the cp and require
   skeptic.cli.main. This is what the launcher does today minus the
   actual analysis. The probe-side entry form requires the namespace
   then exits 0; it does NOT call check-from-launcher (that would read
   stdin and run a full analysis).

   `host-jars` is the resolved host classpath."
  [host-jars]
  (let [cp (str/join (System/getProperty "path.separator")
                     (map str host-jars))
        ;; A pr-str'd form for clojure.main -e. Requires the namespace
        ;; the launcher's host-entry-form requires, then exits.
        probe-form (pr-str '(do (require 'skeptic.cli.main)
                                (System/exit 0)))]
    (run-process [(java-binary) "-cp" cp "clojure.main" "-e" probe-form])))

(defn- worker-jvm-cold-start-thunk
  "Cold-start a worker-shaped JVM: worker-jars ++ skeptic-self on the cp,
   require skeptic.worker.server, exit 0. The launcher's real worker
   classpath also has the project-cp at the front; for this probe we
   omit the project (no target project) — so the absolute number is a
   floor, comparison vs host JVM is what matters."
  [worker-cp]
  (let [probe-form (pr-str '(do (require 'skeptic.worker.server)
                                (System/exit 0)))]
    (run-process [(java-binary) "-cp" worker-cp "clojure.main" "-e" probe-form])))

(defn- measure-cold-start [iters label thunk]
  (let [samples (vec (repeatedly iters #(let [[exit wall-ms] (thunk)]
                                           (when-not (zero? exit)
                                             (println (format "[PROBE] %s: non-zero exit %d" label exit)))
                                           wall-ms)))]
    (probe-line label samples)))

;; -------- (3) Serial-vs-parallel worker+host comparison --------------

(defn- run-process-async
  "Start `command` and return [process start-ns-atom]. The future returned
   by `(:done-future result)` resolves when the process exits."
  [^java.util.List command]
  (let [pb (ProcessBuilder. command)
        _ (.redirectErrorStream pb true)
        start-ns (System/nanoTime)
        proc (.start pb)
        drain (future
                (with-open [r (io/reader (.getInputStream proc))]
                  (doseq [_ (line-seq r)] nil)))
        done (future
               (let [exit (.waitFor proc)
                     wall-ms (ms start-ns)]
                 @drain
                 [exit wall-ms]))]
    {:proc proc :done done :start-ns start-ns}))

(defn- measure-serial-spawn
  "Run worker-then-host sequentially, returning the wall time from
   starting the worker to the host's exit."
  [worker-cp host-jars]
  (let [start (System/nanoTime)]
    (worker-jvm-cold-start-thunk worker-cp)
    (host-jvm-cold-start-thunk host-jars)
    (ms start)))

(defn- measure-parallel-spawn
  "Start worker and host JVMs concurrently; return the wall time until
   BOTH have exited. The probe entry forms just require their entrypoint
   namespace and exit — measuring the spawn floor, not analysis. The
   parallel shape is what the new launcher could achieve if it kicked
   off both processes concurrently and only joined when both were
   needed."
  [worker-cp host-jars]
  (let [cp-sep (System/getProperty "path.separator")
        worker-form (pr-str '(do (require 'skeptic.worker.server) (System/exit 0)))
        host-form   (pr-str '(do (require 'skeptic.cli.main) (System/exit 0)))
        host-cp (str/join cp-sep (map str host-jars))
        start (System/nanoTime)
        worker (run-process-async [(java-binary) "-cp" worker-cp
                                   "clojure.main" "-e" worker-form])
        host   (run-process-async [(java-binary) "-cp" host-cp
                                   "clojure.main" "-e" host-form])
        _ @(:done worker)
        _ @(:done host)]
    (ms start)))

;; -------- Top-level probe driver -------------------------------------

(deftest launcher-perf-probe
  (when (enabled?)
    (let [project (this-project-map)
          verbose? false
          aether-iters 3
          jvm-iters 3]

      (println "[PROBE] === aether resolution ===")
      (println "[PROBE]   cold = this call's wall time on this JVM's first invocation")
      (println "[PROBE]   warm = subsequent calls in the same JVM (m2 + in-memory cache hits)")
      (println "[PROBE]   launcher pays the COLD sum on every `lein skeptic` invocation")
      ;; Run in the launcher's natural order. Each measure-aether's first
      ;; invocation is THIS particular operation's cold cost on its own.
      ;; After all three, also print "cold-sum" — the launcher's per-run
      ;; aether overhead.
      (let [locate-cold-start (System/nanoTime)
            skeptic-jar (locate-skeptic-jar-thunk project verbose?)
            locate-cold-ms (ms locate-cold-start)
            host-cold-start (System/nanoTime)
            _ (resolve-host-jars-thunk project skeptic-jar verbose?)
            host-cold-ms (ms host-cold-start)
            worker-cold-start (System/nanoTime)
            _ (resolve-worker-jars-thunk project skeptic-jar verbose?)
            worker-cold-ms (ms worker-cold-start)]
        (println (format "[PROBE] %-58s cold=%.0fms" "locate-skeptic-jar (cold)" locate-cold-ms))
        (println (format "[PROBE] %-58s cold=%.0fms" "resolve-host-jars (cold)" host-cold-ms))
        (println (format "[PROBE] %-58s cold=%.0fms" "resolve-worker-jars (cold)" worker-cold-ms))
        (println (format "[PROBE] %-58s sum=%.0fms"
                         "ALL THREE serial aether passes (launcher per-run overhead)"
                         (+ (double locate-cold-ms) (double host-cold-ms) (double worker-cold-ms))))
        (probe-line "locate-skeptic-jar (warm, n=3)"
                    (vec (repeatedly aether-iters
                                     #(let [t (System/nanoTime)]
                                        (locate-skeptic-jar-thunk project verbose?)
                                        (ms t)))))
        ;; Re-run warm a few times for noise.
        (probe-line "resolve-host-jars (warm, n=3)"
                    (vec (repeatedly aether-iters
                                     #(let [t (System/nanoTime)]
                                        (resolve-host-jars-thunk project skeptic-jar verbose?)
                                        (ms t)))))
        (probe-line "resolve-worker-jars (warm, n=3)"
                    (vec (repeatedly aether-iters
                                     #(let [t (System/nanoTime)]
                                        (resolve-worker-jars-thunk project skeptic-jar verbose?)
                                        (ms t)))))

        (println "[PROBE] === JVM cold-start (each is a fresh process) ===")
        (measure-cold-start jvm-iters
                            "pure JVM floor (java -version)"
                            pure-jvm-floor-thunk)

        (let [host-jars (resolve-host-jars-thunk project skeptic-jar verbose?)
              worker-jars (resolve-worker-jars-thunk project skeptic-jar verbose?)
              cp-sep (System/getProperty "path.separator")
              ;; The launcher's real worker cp is project-cp ++ worker-jars ++
              ;; [skeptic-jar]. We have no target project here, so the probe cp
              ;; is worker-jars ++ [skeptic-jar] — enough to make
              ;; skeptic.worker.server requirable.
              worker-cp (str/join cp-sep (concat (map str worker-jars) [skeptic-jar]))]
          (println (format "[PROBE] host-jars count=%d   worker-jars count=%d"
                           (count host-jars) (count worker-jars)))
          (measure-cold-start jvm-iters
                              "host JVM cold start (require skeptic.cli.main)"
                              #(host-jvm-cold-start-thunk host-jars))
          (measure-cold-start jvm-iters
                              "worker JVM cold start (require skeptic.worker.server)"
                              #(worker-jvm-cold-start-thunk worker-cp))

          (println "[PROBE] === serial-vs-parallel host+worker spawn ===")
          (let [serial-samples (vec (repeatedly jvm-iters
                                                #(measure-serial-spawn worker-cp host-jars)))
                parallel-samples (vec (repeatedly jvm-iters
                                                  #(measure-parallel-spawn worker-cp host-jars)))]
            (probe-line "serial spawn (worker then host)" serial-samples)
            (probe-line "parallel spawn (both concurrently)" parallel-samples))))))
  (is true))
