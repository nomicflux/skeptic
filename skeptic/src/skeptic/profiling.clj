(ns skeptic.profiling
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace])
  (:import [jdk.jfr Configuration Recording]
           [jdk.jfr.consumer RecordingFile RecordedEvent RecordedFrame]))

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

(defn- frame->function-name [^RecordedFrame frame]
  (-> frame .getMethod .getType .getName demangle-class))

(defn- format-bytes [n]
  (cond
    (>= n 1073741824) (format "%.1f GB" (double (/ n 1073741824)))
    (>= n 1048576)    (format "%.1f MB" (double (/ n 1048576)))
    (>= n 1024)       (format "%.1f KB" (double (/ n 1024)))
    :else              (str n " B")))

(def ^:private skeptic-summary-prefixes
  ["skeptic."
   "leiningen.skeptic"])

(defn- skeptic-function? [fn-name]
  (boolean
   (some #(str/starts-with? fn-name %)
         skeptic-summary-prefixes)))

(defn- skeptic-summary-entries [per-fn]
  (filter (comp skeptic-function? key) per-fn))

(defn- extract-execution-frames [^RecordedEvent event]
  (when-let [st (.getStackTrace event)]
    (let [java-frames (->> (.getFrames st)
                           (filter #(.isJavaFrame %))
                           (mapv frame->function-name))]
      (when (seq java-frames)
        java-frames))))

(defn- aggregate-frame-data [frame-seqs]
  (let [frame-seqs (remove empty? frame-seqs)
        total (count frame-seqs)]
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

(defn- extract-allocation-frames [^RecordedEvent event]
  (when-let [st (.getStackTrace event)]
    (let [frames (.getFrames st)
          java-frames (filter #(.isJavaFrame %) frames)]
      (when (seq java-frames)
        {:frames (mapv frame->function-name java-frames)
         :weight (.getLong event "weight")}))))

(defn- aggregate-alloc-data [alloc-entries]
  (let [total (reduce + 0 (map :weight alloc-entries))]
    {:total-bytes total
     :per-fn
     (reduce
      (fn [acc {:keys [frames weight]}]
        (let [self-fn (first frames)
              all-fns (distinct frames)]
          (as-> acc a
            (update-in a [self-fn :self-alloc-bytes] (fnil + 0) weight)
            (reduce (fn [a2 fn-name]
                      (update-in a2 [fn-name :total-alloc-bytes] (fnil + 0) weight))
                    a all-fns))))
      {}
      alloc-entries)}))

(defn- aggregate-cpu-load [events]
  (when (seq events)
    (let [n (count events)]
      {:jvm-user-avg   (/ (reduce + (map #(.getDouble % "jvmUser") events)) n)
       :jvm-system-avg (/ (reduce + (map #(.getDouble % "jvmSystem") events)) n)})))

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
                                                :self-alloc-pct (if (pos? total-bytes)
                                                                  (* 100.0 (/ (:self-alloc-bytes v 0) total-bytes))
                                                                  0.0)
                                                :total-alloc-pct (if (pos? total-bytes)
                                                                   (* 100.0 (/ (:total-alloc-bytes v 0) total-bytes))
                                                                   0.0))]))
                               per-fn))]

    {:execution  {:total-samples total-samples
                  :per-fn (add-exec-pcts (:per-fn exec-data))}
     :allocation {:total-bytes total-bytes
                 :per-fn (add-alloc-pcts (:per-fn alloc-data))}
     :cpu        (aggregate-cpu-load (get events-by-type "jdk.CPULoad"))
     :duration-ms duration-ms}))

(defn- print-summary [profile-data jfr-path]
  (let [{:keys [execution allocation cpu duration-ms]} profile-data
        duration-s (/ duration-ms 1000.0)
        skeptic-execution (->> (:per-fn execution)
                               skeptic-summary-entries
                               (sort-by (fn [[_ {:keys [self]}]]
                                          (- (or self 0))))
                               (take 30))
        skeptic-allocation (->> (:per-fn allocation)
                                skeptic-summary-entries
                                (sort-by (comp - :total-alloc-bytes val))
                                (take 20))]
    (println "\n=== Profiling Summary ===")
    (println (format "Duration:            %.1fs" duration-s))
    (println (format "Execution samples:   %d" (:total-samples execution)))
    (println (str "Allocation weight:   " (format-bytes (:total-bytes allocation))))
    (when cpu
      (println (format "Avg CPU (jvm user):  %.1f%%" (* 100.0 (:jvm-user-avg cpu))))
      (println (format "Avg CPU (jvm system): %.1f%%" (* 100.0 (:jvm-system-avg cpu)))))

    (println "\n--- Top Skeptic Functions by Self Time ---")
    (if (seq skeptic-execution)
      (do
        (println "  Self%  Total%   Self#  Total#  Function")
        (doseq [[fn-name data] skeptic-execution]
          (println (format "  %5.1f%%  %5.1f%%  %6d  %6d  %s"
                           (:self-pct data)
                           (:total-pct data)
                           (:self data 0)
                           (:total data 0)
                           fn-name))))
      (println "  (no Skeptic execution samples captured)"))

    (println "\n--- Top Skeptic Functions by Allocation ---")
    (if (seq skeptic-allocation)
      (do
        (println "  Self%  Total%   Self Weight   Total Weight  Function")
        (doseq [[fn-name data] skeptic-allocation]
          (println (format "  %5.1f%%  %5.1f%%  %-12s  %-12s  %s"
                           (:self-alloc-pct data)
                           (:total-alloc-pct data)
                           (format-bytes (:self-alloc-bytes data 0))
                           (format-bytes (:total-alloc-bytes data 0))
                           fn-name))))
      (println "  (no Skeptic allocation samples captured)"))

    (println (str "\nRaw profile data: " jfr-path))))

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
                  duration  (/ (- end-ts start-ts) 1000000.0)]
              (try
                (let [events    (RecordingFile/readAllEvents jfr-path)
                      by-type   (group-by #(-> % .getEventType .getName) events)
                      prof-data (build-profile-data by-type duration)]
                  (if (:porcelain opts)
                    (binding [*out* *err*]
                      (print-summary prof-data (.getPath jfr-file)))
                    (print-summary prof-data (.getPath jfr-file))))
                (catch Exception e
                  (println "WARNING: Failed to generate profiling summary:")
                  (stacktrace/print-stack-trace e)
                  (println (str "Raw profile data may still be available at: "
                                (.getPath jfr-file)))))
              exit-code)
            (catch Exception e
              (.close recording)
              (throw e))))
        (catch Exception e
          (println "WARNING: Profiling failed to start, running without profiling:")
          (stacktrace/print-stack-trace e)
          (work-fn))))))
