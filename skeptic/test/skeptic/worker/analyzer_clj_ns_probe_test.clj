(ns skeptic.worker.analyzer-clj-ns-probe-test
  "Probe: isolate where the :clojure.core/map-key-query mis-resolution and the
   #clojure.reflect.Method wire-projection failure each originate, by calling the
   worker's real read and analyze steps directly on a real source file."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [skeptic.worker.analyzer-clj :as wac]))

(defn- read-forms-unbound
  [source-file]
  (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
    (doall (take-while #(not= ::eof %)
                       (repeatedly #(tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader))))))

(defn- read-forms-bound
  [ns-sym source-file]
  (binding [*ns* (the-ns ns-sym)]
    (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader)))))))

(defn- exact-key-query-key
  "The first map key inside exact-key-query's body, as read."
  [forms]
  (->> forms
       (filter #(and (seq? %) (= 'exact-key-query (second %))))
       (mapcat (fn [form] (filter map? (tree-seq coll? seq form))))
       (some (fn [m] (some #(when (= "map-key-query" (name %)) %) (keys m))))))

(deftest read-keyword-resolution-probe
  (require 'skeptic.analysis.map-ops)
  (let [file (io/file "src/skeptic/analysis/map_ops.clj")
        unbound-key (exact-key-query-key (read-forms-unbound file))
        bound-key (exact-key-query-key (read-forms-bound 'skeptic.analysis.map-ops file))]
    (is (not= :skeptic.analysis.map-ops/map-key-query unbound-key)
        "unbound read mis-resolves :: against ambient *ns* (reproduces Root C)")
    (is (= :skeptic.analysis.map-ops/map-key-query bound-key)
        "bound read resolves :: to the source ns")))

(defn- deep-nodes
  "All nodes of `x`, descending into both children AND metadata."
  [x]
  (tree-seq (some-fn coll? meta)
            (fn [n] (concat (when (coll? n) (seq n))
                            (when-let [m (meta n)] [m])))
            x))

(defn- contains-reflect-method?
  "True if any node in `x` (including in metadata) is a clojure.reflect.Method."
  [x]
  (boolean (some #(instance? clojure.reflect.Method %) (deep-nodes x))))

(deftest analyze-source-file-reflect-probe
  (require 'skeptic.provenance)
  (let [file (io/file "src/skeptic/provenance.clj")
        result (wac/analyze-source-file 'skeptic.provenance file)
        forms (map :source-form (:entries result))]
    (is (map? result))
    (is (not-any? contains-reflect-method? forms)
        "read source-forms must not embed clojure.reflect.Method")))
