(ns skeptic.perf.preload-state-probe-test
  "Performance probes for the per-reply hot path inside
   `skeptic.checking.pipeline/preload-clj-state!`.

   JFR (180.9 s run) attributes 60.6 % allocation total to
   preload-clj-state! and 63.1 % to worker.transport/read-message
   one frame down. The per-call probes already in
   worker_boundary_probe_test.clj cover the transit decode side. This
   file probes what the HOST does to each decoded reply:

     reattach-entry-meta   — wire/apply-form-meta over :source-form
                              and :ast (one postwalk each).
     process-stream-reply  — full per-reply handler: dissoc/assoc the
                              entry; swap! atoms; call schema-discovery.
     schema.discovery/discover — classify a namespace's source-forms.

   The probes use synthetic but realistic shapes. Each entry's
   :ast is a tools.analyzer.jvm output for a real form, so the
   postwalk visits the same node count it would in production. Each
   :source-form is the surface form with the host-read meta keys
   attached.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.walk :as walk]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.perf.harness :as h]
            [skeptic.schema.discovery :as schema-discovery]
            [skeptic.worker.wire :as wire]))

;; --- Fixture builders -------------------------------------------------

(defn- source-form-with-meta
  "Build a source-form that carries host-read meta on the head symbol.
   Mimics the meta the worker captures from the source-logging reader."
  [n]
  (with-meta
    (list 'defn (with-meta (symbol (str "fn" n))
                  {:file "src/demo/core.clj"
                   :line (+ 10 n)
                   :column 1
                   :end-line (+ 10 n)
                   :end-column 20
                   :source (str "(defn fn" n " [x] (+ x 1))")})
          ['x] (list '+ 'x 1))
    {:file "src/demo/core.clj" :line (+ 10 n) :column 1}))

(defn- entry-for-form
  "Build one worker-reply entry: source-form + source-form-meta sidecar
   + AST + ast-meta sidecar. The AST is a real tools.analyzer.jvm AST
   for the form so the wire/apply-ast-form-meta postwalk visits a
   realistic node count."
  [form]
  (let [ast (ana.jvm/analyze form)]
    {:source-form form
     :source-form-meta (wire/capture-form-meta form)
     :ast ast
     :ast-form-meta (wire/capture-ast-form-meta ast)}))

(defn- ns-reply
  [ns-sym entries]
  {:source-file "src/demo/core.clj"
   :ns-sym ns-sym
   :entries entries})

(defn- build-entries [n]
  (mapv (comp entry-for-form source-form-with-meta) (range n)))

;; --- Probe ------------------------------------------------------------

(deftest preload-state-probe
  (when (h/enabled?)
    (let [budget-ms 500
          sizes [1 10 40 200]
          entry-batches (into {} (map (fn [n] [n (build-entries n)])) sizes)]

      (println "[PROBE] === reattach-entry-meta (per entry) ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    one-entry (first entries)]]
        (h/measure (format "reattach-entry-meta single (n=%d-batch)" n)
                   budget-ms
                   #(pipeline/reattach-entry-meta one-entry))
        (h/measure (format "reattach-entry-meta batch %d entries" n)
                   budget-ms
                   #(mapv pipeline/reattach-entry-meta entries)))

      (println "[PROBE] === process-stream-reply (per reply, includes discover) ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    reply (ns-reply 'demo.core entries)
                    loaded-index {(:source-file reply) ['demo.core (:source-file reply)]}]]
        (h/measure (format "process-stream-reply %d-entry reply" n)
                   budget-ms
                   #(let [clj-state-a (atom {})
                          clj-failures-a (atom {})
                          ns-entries-a (atom {})
                          project-disc-a (atom {})
                          shadowed-a (atom {})]
                      (pipeline/process-stream-reply
                       clj-state-a clj-failures-a
                       ns-entries-a project-disc-a
                       shadowed-a loaded-index reply))))

      (println "[PROBE] === schema.discovery/discover (per namespace) ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    forms (mapv :source-form entries)]]
        (h/measure (format "discover %d forms" n)
                   budget-ms
                   #(schema-discovery/discover 'demo.core forms)))

      (println "[PROBE] === wire/apply-form-meta (source-form side) ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    {:keys [source-form source-form-meta]} (first entries)]]
        (h/measure (format "apply-form-meta single source-form (n=%d-batch)" n)
                   budget-ms
                   #(wire/apply-form-meta source-form source-form-meta))
        (h/measure (format "apply-form-meta source-forms batch %d" n)
                   budget-ms
                   #(mapv (fn [e]
                            (wire/apply-form-meta (:source-form e) (:source-form-meta e)))
                          entries)))

      (println "[PROBE] === wire/apply-form-meta (postwalk) on AST: regression counter-test ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    {:keys [ast ast-form-meta]} (first entries)]]
        (h/measure (format "apply-form-meta (postwalk) single AST (n=%d-batch)" n)
                   budget-ms
                   #(wire/apply-form-meta ast ast-form-meta))
        (h/measure (format "apply-form-meta (postwalk) ASTs batch %d" n)
                   budget-ms
                   #(mapv (fn [e]
                            (wire/apply-form-meta (:ast e) (:ast-form-meta e)))
                          entries)))

      (println "[PROBE] === scale check: entry counts in each batch ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)]]
        (println (format "[PROBE] batch n=%d: source-form-meta vec sizes %s   ast-form-meta vec sizes %s"
                         n
                         (vec (take 3 (map (comp count :source-form-meta) entries)))
                         (vec (take 3 (map (comp count :ast-form-meta) entries))))))

      (println "[PROBE] === postwalk vs spine-walk on an AST: how many nodes does each visit? ===")
      (let [ast (:ast (first (get entry-batches 1)))
            postwalk-visits (atom 0)
            _ (walk/postwalk
               (fn [x] (swap! postwalk-visits inc) x)
               ast)]
        (println (format "[PROBE] postwalk visits on one 12-meta-node AST: %d total values"
                         @postwalk-visits))
        (println (format "[PROBE]    (compare: ast-form-meta vector length = %d)"
                         (count (:ast-form-meta (first (get entry-batches 1)))))))

      (println "[PROBE] === wire/apply-ast-form-meta (spine-walk) on AST: production hot path ===")
      (doseq [n sizes
              :let [entries (get entry-batches n)
                    {:keys [ast ast-form-meta]} (first entries)]]
        (h/measure (format "apply-ast-form-meta (spine) single AST (n=%d)" n)
                   budget-ms
                   #(wire/apply-ast-form-meta ast ast-form-meta)))))
  (is true))
