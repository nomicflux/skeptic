(ns skeptic.perf.ast-slot-enumeration-test
  "Enumerate every keyword key that appears on any AST node in a real
   worker-projected per-namespace reply. Output is the universe of slots
   the host sees on the wire. Cross-reference against host-side readers
   to identify droppable slots.

   Gated by SKEPTIC_PROBE=1 — it's diagnostic, not a perf measurement."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [skeptic.perf.harness :as h]
            [skeptic.worker.analyzer-clj :as wac]))

(def fixture-source "src/skeptic/analysis/type_ops.clj")
(def fixture-ns 'skeptic.analysis.type-ops)

(defn- project-entry-fn []
  (require 'skeptic.worker.server)
  (resolve 'skeptic.worker.server/project-entry))

(defn- ast-node? [v]
  (and (map? v) (not (sorted? v)) (contains? v :op)))

(deftest ast-slot-enumeration
  (when (h/enabled?)
    (let [project-entry (project-entry-fn)
          {:keys [entries]} (wac/analyze-source-file fixture-ns
                                                    (io/file fixture-source) false)
          projected (mapv #(project-entry fixture-ns %) entries)
          keys-by-op (atom {})
          all-keys (atom #{})]
      (doseq [entry projected]
        (walk/prewalk
         (fn [v]
           (when (ast-node? v)
             (let [op (:op v)
                   ks (set (keys v))]
               (swap! all-keys into ks)
               (swap! keys-by-op update op (fnil into #{}) ks)))
           v)
         (:ast entry)))
      (println (format "[ENUM] %d total distinct AST keys across %d entries"
                       (count @all-keys) (count projected)))
      (println "[ENUM] every AST key seen (sorted):")
      (doseq [k (sort @all-keys)]
        (println (format "  %s" k)))
      (println "[ENUM] keys by :op (which ops attach which slots):")
      (doseq [[op ks] (sort-by (comp str key) @keys-by-op)]
        (println (format "  %s -> %s" op (sort ks))))))
  (is true))
