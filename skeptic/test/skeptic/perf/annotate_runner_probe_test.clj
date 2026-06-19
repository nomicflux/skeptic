(ns skeptic.perf.annotate-runner-probe-test
  "Performance probe for perf-annotate-runner facet.

   Measures runner/run-with-finalizer over synthetic AnnotatedNode
   trees of varying depth and breadth. A probe-local
   `annotate-passthrough` helper either short-circuits as :done at
   leaves or pushes a :call frame to descend, exercising the
   trampoline's per-frame allocation cost (the JFR shows
   annotate.runner total% 25.3, annotate.api total% 4.4).

   The probe deliberately drives the runner via runner/sequence-children
   (the JFR-named hot helper) so the measured frame allocation matches
   the real annotator. The trampoline is what we care about, not the
   downstream type-inference cost.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.types :as at]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- leaf-node [v]
  {:op :const :type (at/->GroundT tp :int 'Int) :form v})

(defn- vector-node [items]
  {:op :vector :items items :form (vec (map :form items))})

(defn- chain-node
  "Build a chain of :do-like nodes one deep per level. Carries the
   inner node as :ret so the recursion has somewhere to go."
  [depth leaf]
  (if (zero? depth)
    leaf
    {:op :do
     :statements [leaf]
     :ret (chain-node (dec depth) leaf)
     :form 'do}))

(defn- fanout-node
  "Build a tree of fixed branching factor 5 and fixed depth — total
   nodes = 5^depth. depth=4 -> 625 nodes, depth=5 -> 3125 nodes."
  [depth]
  (if (zero? depth)
    (leaf-node 1)
    (vector-node (vec (repeatedly 5 #(fanout-node (dec depth)))))))

(defn- passthrough-step
  "Recursive helper that descends into known structural slots. Returns
   a runner Step. The leaf case returns runner/done; the structural
   case uses runner/sequence-children for vectors and a manual :call
   chain for :do.

   This matches the runner contract end state (Phase 7) and stresses
   the same heap-allocated stack the real annotate dispatch uses."
  [ctx node]
  (case (:op node)
    :const (runner/done (assoc node :type (at/->GroundT tp :int 'Int)))
    :vector
    (runner/sequence-children
     ctx (:items node)
     (fn [annotated-items]
       (runner/done (assoc node
                           :items annotated-items
                           :type (at/->GroundT tp :int 'Int)))))
    :do
    (runner/call passthrough-step ctx (:ret node)
                 (fn [ret-annotated]
                   (runner/done (assoc node
                                       :ret ret-annotated
                                       :type (:type ret-annotated)))))
    ;; default
    (runner/done (assoc node :type (at/->GroundT tp :int 'Int)))))

(defn- probe-ctx []
  (assoc (prov/set-ctx {} tp)
         :recurse (fn [_ n] n)
         :recurse-step passthrough-step))

(defn- finalizer-identity [_h _c _n v] v)

(deftest annotate-runner-probe
  (when (h/enabled?)
    (let [ctx (probe-ctx)
          leaf (leaf-node 42)
          shallow-vector (vector-node (vec (repeatedly 8 #(leaf-node 1))))
          chain-100 (chain-node 100 leaf)
          chain-1000 (chain-node 1000 leaf)
          fanout-d3 (fanout-node 3)        ;;   125 nodes
          fanout-d4 (fanout-node 4)        ;;   625 nodes
          fanout-d5 (fanout-node 5)        ;;  3125 nodes
          budget-ms 500]

      ;; Trampoline base cost: one :done immediately.
      (h/measure "runner/run leaf node"
                 budget-ms
                 #(runner/run-with-finalizer passthrough-step ctx leaf finalizer-identity))

      ;; sequence-children with 8 children — one :call per child plus a
      ;; final :done.  Measures the per-frame conj/pop.
      (h/measure "runner/run vector(8 leaves)"
                 budget-ms
                 #(runner/run-with-finalizer passthrough-step ctx shallow-vector finalizer-identity))

      ;; Chain depths exercise stack growth at fixed cost per node.
      (h/measure "runner/run chain depth=100"
                 budget-ms
                 #(runner/run-with-finalizer passthrough-step ctx chain-100 finalizer-identity))
      (h/measure "runner/run chain depth=1000"
                 budget-ms
                 #(runner/run-with-finalizer passthrough-step ctx chain-1000 finalizer-identity))

      ;; Fanout exercises sequence-children at varying breadth.
      (h/measure "runner/run fanout 5^3 (125 nodes)"
                 (long (* 2 budget-ms))
                 #(runner/run-with-finalizer passthrough-step ctx fanout-d3 finalizer-identity))
      (h/measure "runner/run fanout 5^4 (625 nodes)"
                 (long (* 2 budget-ms))
                 #(runner/run-with-finalizer passthrough-step ctx fanout-d4 finalizer-identity))
      (h/measure "runner/run fanout 5^5 (3125 nodes)"
                 (long (* 2 budget-ms))
                 #(runner/run-with-finalizer passthrough-step ctx fanout-d5 finalizer-identity))))
  (is true))
