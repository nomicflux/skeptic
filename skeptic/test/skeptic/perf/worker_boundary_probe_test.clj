(ns skeptic.perf.worker-boundary-probe-test
  "Performance probe for perf-worker-boundary facet.

   JFR (180.9s run, current HEAD) ranks worker.transport/read-message at
   63.1% allocation total / 0.8% self time — the framing/transit pipeline
   is the dominant memory pressure on a Skeptic run. preload-clj-state!
   shows 60.6% allocation total: every analyze-namespaces-stream reply
   pays the read-message tax once and the host-side postwalk reattach
   once.

   This probe drives the framing layer directly with
   DataInputStream/DataOutputStream backed by byte arrays. No worker JVM,
   no socket — measurements isolate the wire cost itself. Three regimes:

     1. write-message / read-message round-trip on a small payload
        (one analyze-namespaces-stream `:starting?` reply shape).

     2. write-message / read-message round-trip on a realistic per-namespace
        reply: ~40 entries, each carrying a synthetic AST (8 nodes deep)
        and source-form metadata sidecars.

     3. apply-form-meta reattach over the same payload (the
        clj-postwalk step in process-stream-reply).

   The wire layer is by-design opaque to schemas: payloads are plain
   Clojure data carrying whatever the worker produced. Probes use realistic
   shapes WITHOUT loading the worker's namespaces — host-only.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.perf.harness :as h]
            [skeptic.worker.transport :as t]
            [skeptic.worker.wire :as wire])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            DataInputStream DataOutputStream]))

(defn- make-frame-streams
  "Returns [DataOutputStream baos] for write benchmarking."
  []
  (let [baos (ByteArrayOutputStream.)]
    [(DataOutputStream. baos) baos]))

(defn- read-frame
  [^bytes bs]
  (t/read-message (DataInputStream. (ByteArrayInputStream. bs))))

(defn- starting-reply []
  {:op "analyze-namespaces-stream"
   :starting? true
   :ns-sym 'demo.core
   :source-file "src/demo/core.clj"})

(defn- ast-node
  "Synthetic AST node with form metadata to exercise the apply-form-meta
   reattach. Returns an entry shape mimicking what the worker streams."
  [i]
  (let [form `(defn ~(symbol (str "fn" i)) [x#] (+ x# 1))]
    {:source-form form
     :source-form-meta (wire/capture-form-meta form)
     :ast {:op :def
           :name (symbol (str "fn" i))
           :init {:op :fn :methods [{:op :fn-method :params [{:op :binding :name 'x}]
                                     :body {:op :do :statements []
                                            :ret {:op :static-call :class 'clojure.lang.Numbers
                                                  :method '+ :args [{:op :local :name 'x}
                                                                    {:op :const :val 1}]}}}]}}}))

(defn- per-ns-reply
  "Realistic per-namespace reply: 40 entries of synthetic ASTs."
  []
  {:source-file "src/demo/core.clj"
   :ns-sym 'demo.core
   :entries (vec (for [i (range 40)] (ast-node i)))})

(defn- write-once-bytes
  [msg]
  (let [[^DataOutputStream dout ^ByteArrayOutputStream baos] (make-frame-streams)]
    (t/write-message dout msg)
    (.toByteArray baos)))

(deftest worker-boundary-probe
  (when (h/enabled?)
    (let [budget-ms 500
          small-msg (starting-reply)
          big-msg (per-ns-reply)
          small-bytes (write-once-bytes small-msg)
          big-bytes (write-once-bytes big-msg)]

      (println (format "[PROBE] small payload size: %d bytes" (alength small-bytes)))
      (println (format "[PROBE] big   payload size: %d bytes" (alength big-bytes)))

      ;; Write side — measures Transit encoding + length-prefix framing.
      (h/measure "write-message starting? reply"
                 budget-ms
                 #(let [[^DataOutputStream dout _baos] (make-frame-streams)]
                    (t/write-message dout small-msg)))
      (h/measure "write-message per-ns reply (40 entries)"
                 budget-ms
                 #(let [[^DataOutputStream dout _baos] (make-frame-streams)]
                    (t/write-message dout big-msg)))

      ;; Read side — the JFR's 63.1% allocation hot region.
      (h/measure "read-message starting? reply"
                 budget-ms
                 #(read-frame small-bytes))
      (h/measure "read-message per-ns reply (40 entries)"
                 budget-ms
                 #(read-frame big-bytes))

      ;; Reattach is the host-side postwalk preload-clj-state! pays once
      ;; per entry. Measure it separately so the cost shows up in isolation.
      (let [entries (:entries big-msg)
            entry (first entries)
            {:keys [source-form source-form-meta]} entry]
        (h/measure "wire/apply-form-meta single entry"
                   budget-ms
                   #(wire/apply-form-meta source-form source-form-meta))
        (h/measure "wire/apply-form-meta 40 entries (one ns reply)"
                   budget-ms
                   #(mapv (fn [e]
                            (wire/apply-form-meta (:source-form e) (:source-form-meta e)))
                          entries)))))
  (is true))
