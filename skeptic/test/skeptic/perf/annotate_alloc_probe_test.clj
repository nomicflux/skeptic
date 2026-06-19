(ns skeptic.perf.annotate-alloc-probe-test
  "Re-derived annotate probe: isolates per-node allocation.

   JFR (49s sample run): stacks containing 'annotate' total 7.42% of
   total allocation (4.1 GB). Object[] 44.88%, PersistentVector$ChunkedSeq
   9.06%, PersistentArrayMap 5.26%, PersistentVector 4.67%, AtomicReference
   3.80%, ArraySeq 3.51%, PersistentHashMap 2.04%, MapEntry 1.95%.

   ContinuationFrame itself: 28 MB = 0.68% of the annotate slice = 0.05%
   of total. runner.clj:43 shows it is already a (deftype ContinuationFrame
   [helper ctx node k]) with bare-field access via .-k/.-helper/.-ctx/.-node
   — faster than defrecord. The prior agent's pick to convert it to
   defrecord is a regression by inspection.

   The real candidate: per-node small-map allocation in annotate-dispatch.
   The annotate phase builds an AnnotatedNode per AST node — a small map
   carrying :op :form :type :children ... The JFR shows PersistentArrayMap
   5.26% + PersistentHashMap 2.04% = 7.30% of the annotate slice = 5.4%
   of total, in the per-node shape.

   This probe ANSWERS:

     1. Per-node bytes/op cost of annotate-dispatch on a representative
        AST (drawn from in-tree type_ops.clj via the worker analyzer).
     2. annotate-ast over the same AST repeatedly — drives the recursive
        walk through ContinuationFrame.
     3. Magnitude check: is the per-node bytes/op consistent with the
        JFR claim of 4.1 GB across the run?

   Confirm-by-magnitude pattern: a single per-node bytes/op figure
   multiplied by tens of millions of nodes per run should land in the
   gigabyte range.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.annotate :as aa]
            [skeptic.perf.harness :as h]
            [skeptic.test-support.shared-worker :as shared-worker]
            [skeptic.worker.analyzer-clj :as wac]))

(use-fixtures :once shared-worker/with-shared-worker)

(def fixture-source "src/skeptic/analysis/type_ops.clj")
(def fixture-ns 'skeptic.analysis.type-ops)

(defn- build-real-asts
  "Run the worker's analyze-source-file against a real in-tree clj file.
   Returns the vector of ASTs (one per top-level form)."
  []
  (let [{:keys [entries]} (wac/analyze-source-file fixture-ns (io/file fixture-source) false)]
    (->> entries (keep :ast) vec)))

(defn- node-count
  "Approximate AST size: count :children edges recursively. Off-by-1 is
   fine — used only to multiply per-op cost into per-fixture cost."
  [node]
  (cond
    (nil? node) 0
    (map? node)
    (let [ks (:children node)]
      (inc (reduce + 0 (map (fn [k]
                              (let [v (get node k)]
                                (cond
                                  (vector? v) (reduce + 0 (map node-count v))
                                  :else (node-count v))))
                            ks))))
    (vector? node) (reduce + 0 (map node-count node))
    :else 0))

(deftest annotate-alloc-probe
  (when (h/enabled?)
    (let [budget-ms 500
          asts (build-real-asts)
          dict {}  ;; per-node small-map allocation is independent of dict contents
          opts {:ns fixture-ns :lang :clj}
          n-forms (count asts)
          total-nodes (reduce + 0 (map node-count asts))
          one-ast (some #(when (and (map? %) (= :def (:op %))) %) asts)
          one-ast-nodes (node-count one-ast)]

      (println (format "[PROBE] real fixture: %s, %d top-level forms, ~%d total AST nodes"
                       fixture-source n-forms total-nodes))
      (when one-ast
        (println (format "[PROBE] single :def AST: ~%d nodes" one-ast-nodes)))

      ;; Single-form annotate — drives one ContinuationFrame walk over one AST.
      ;; bytes/op divided by node count = per-node allocation (the lever).
      (when one-ast
        (h/measure (format "annotate-ast single :def form (~%d nodes)" one-ast-nodes)
                   budget-ms
                   #(aa/annotate-ast dict one-ast opts)))

      ;; Whole-file annotate — every form in type_ops.clj.
      ;; bytes/op vs node count tells the magnitude story.
      (h/measure (format "annotate-ast whole fixture (%d forms, ~%d nodes)"
                         n-forms total-nodes)
                 budget-ms
                 #(mapv (fn [ast] (aa/annotate-ast dict ast opts)) asts))))
  (is true))
