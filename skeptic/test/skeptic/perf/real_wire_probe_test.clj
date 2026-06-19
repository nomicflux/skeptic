(ns skeptic.perf.real-wire-probe-test
  "Re-derived wire-layer probe: drives write-message/read-message against a
   per-namespace reply built from a REAL worker-side analyze-source-file run
   over an in-tree source file. The prior worker-boundary probe used synthetic
   backtick forms whose `:source-form-meta` sidecars are vectors of nils — it
   did not model production allocation shape.

   This probe answers the question the JFR raised:

     read-message accounts for 13.34% of total allocation (7.4 GB / 55 GB
     sampled, 49s). The breakdown is Object[] 41%, int[] 29%, byte[] 20%.
     What drives the per-namespace reply payload size: :ast, :source-form,
     or the *-meta sidecars?

   A/B regimes on the SAME real-shape reply:

     1. round-trip baseline: write+read the full projected entry vector.
     2. round-trip without :ast slot.
     3. round-trip without :source-form-meta + :ast-form-meta.
     4. round-trip with only :source-form (the prior agent's 'drop :source-form'
        candidate — to be refuted by allocation pressure, not by intuition).

   Confirms or disconfirms which slot drives the wire-in allocation mass.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skeptic.perf.harness :as h]
            [skeptic.worker.analyzer-clj :as wac]
            [skeptic.worker.transport :as t])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            DataInputStream DataOutputStream]))

(def fixture-source
  "Real in-tree clj file: 5–14 KB, dozens of s/defn forms with metadata."
  "src/skeptic/analysis/type_ops.clj")

(def fixture-ns 'skeptic.analysis.type-ops)

(defn- project-entry-fn []
  (require 'skeptic.worker.server)
  (or (resolve 'skeptic.worker.server/project-entry)
      (some-> (ns-interns 'skeptic.worker.server) (get 'project-entry))))

(defn- build-real-entries
  "Run the worker's analyze-source-file against a real in-tree clj file and
   project each result through project-entry. Result mirrors what
   `analyze-namespace-reply` ships over the wire."
  []
  (let [project-entry (project-entry-fn)
        _ (assert project-entry "project-entry not resolvable")
        {:keys [entries]} (wac/analyze-source-file fixture-ns (io/file fixture-source) false)]
    (mapv #(project-entry fixture-ns %) entries)))

(defn- without-ast [entry]
  (dissoc entry :ast :ast-form-meta))

(defn- without-meta-sidecars [entry]
  (dissoc entry :source-form-meta :ast-form-meta))

(defn- only-source-form [entry]
  (select-keys entry [:source-form]))

(defn- per-ns-reply [entries]
  {:source-file fixture-source :ns-sym fixture-ns :entries (vec entries)})

(defn- write-once-bytes [msg]
  (let [baos (ByteArrayOutputStream.)
        dout (DataOutputStream. baos)]
    (t/write-message dout msg)
    (.toByteArray baos)))

(defn- read-frame [^bytes bs]
  (t/read-message (DataInputStream. (ByteArrayInputStream. bs))))

(deftest real-wire-probe
  (when (h/enabled?)
    (let [budget-ms 500
          full-entries (build-real-entries)
          n-entries (count full-entries)
          full-msg     (per-ns-reply full-entries)
          no-ast-msg   (per-ns-reply (mapv without-ast full-entries))
          no-meta-msg  (per-ns-reply (mapv without-meta-sidecars full-entries))
          only-sf-msg  (per-ns-reply (mapv only-source-form full-entries))
          full-bytes    (write-once-bytes full-msg)
          no-ast-bytes  (write-once-bytes no-ast-msg)
          no-meta-bytes (write-once-bytes no-meta-msg)
          only-sf-bytes (write-once-bytes only-sf-msg)]

      (println (format "[PROBE] real fixture: %s, %d projected entries"
                       fixture-source n-entries))
      (println (format "[PROBE] payload sizes (bytes):  full=%d  no-ast=%d  no-meta=%d  only-source-form=%d"
                       (alength full-bytes) (alength no-ast-bytes)
                       (alength no-meta-bytes) (alength only-sf-bytes)))

      (let [base    (h/measure "read-message full real reply"
                               budget-ms #(read-frame full-bytes))
            no-ast  (h/measure "read-message reply without :ast slot"
                               budget-ms #(read-frame no-ast-bytes))
            no-meta (h/measure "read-message reply without form-meta sidecars"
                               budget-ms #(read-frame no-meta-bytes))
            only-sf (h/measure "read-message only :source-form (refute-candidate)"
                               budget-ms #(read-frame only-sf-bytes))]
        (println (format "[A/B] read-message no-ast vs base:    %s"
                         (pr-str (h/compare-configs base no-ast))))
        (println (format "[A/B] read-message no-meta vs base:   %s"
                         (pr-str (h/compare-configs base no-meta))))
        (println (format "[A/B] read-message only-sf vs base:   %s"
                         (pr-str (h/compare-configs base only-sf)))))

      (let [w-base    (h/measure "write-message full real reply"
                                 budget-ms #(write-once-bytes full-msg))
            w-no-ast  (h/measure "write-message reply without :ast slot"
                                 budget-ms #(write-once-bytes no-ast-msg))
            w-no-meta (h/measure "write-message reply without form-meta sidecars"
                                 budget-ms #(write-once-bytes no-meta-msg))]
        (println (format "[A/B] write-message no-ast vs base:  %s"
                         (pr-str (h/compare-configs w-base w-no-ast))))
        (println (format "[A/B] write-message no-meta vs base: %s"
                         (pr-str (h/compare-configs w-base w-no-meta)))))))
  (is true))
