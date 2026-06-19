(ns skeptic.perf.cast-dispatch-alloc-probe-test
  "Re-derived cast hotloop probe: isolates per-dispatch allocation.

   JFR (49s sample run): dispatch.cast/run.cast/semantic.type=? stacks
   total 12.68% of total allocation (7.0 GB). Object[] 32%,
   PersistentVector 12%, MapEntry 8%, PersistentHashMap$BitmapIndexedNode
   4.9%, PersistentArrayMap$Seq 3.25%.

   Inspection of cast-ok (cast/support.clj:37-49) shows EVERY cast-ok
   call allocates a 7-key map `{:ok? :source-type :target-type :rule
   :polarity :children :details}`. The map literal becomes a
   PersistentArrayMap with a 14-slot Object[] backing. with-cast-path
   (L66-69) adds an 8th key (:path) which triggers conversion to
   PersistentHashMap. Aggregate-children walks results vectors.

   This probe ANSWERS:

     1. Does the per-cast-ok 7-key map account for the Object[]
        dominance in the cast slice? A/B: cast-ok call alone vs the
        full check-cast pipeline; if bytes/op of cast-ok approximates
        the per-check-cast call's bytes/op, the map IS the lever.

     2. Does at/type=? on its own allocate? Per perf-cast-hotloop
        facet logical: 'semantic-type=? short-circuits on tag mismatch
        before any recursive call'. If true, type=? bytes/op should be
        near-zero on early-mismatch input. If allocation appears, the
        claim is wrong.

     3. Does dispatch-cast's exact fast path (type=? returns true →
        cast-ok :exact) cost the same as a full check-cast? Tests
        whether the fast path is genuinely fast or whether the cast-ok
        map allocation dominates either way.

   Reports bytes/op separately from ns/op so the allocation pressure
   shows independent of wall-clock.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.types :as at]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- T [schema] (ab/schema->type tp schema))

(defn- big-map-schema []
  (into {}
        (concat
         (for [i (range 10)] [(keyword (str "f" i)) s/Int])
         (for [i (range 10)] [(keyword (str "s" i)) s/Str])
         [[:nested-a {:x s/Int :y s/Str :z s/Keyword
                      :inner {:p s/Int :q s/Bool}}]
          [:nested-b {:m s/Keyword :n s/Bool
                      :inner {:p s/Num :q (s/maybe s/Int)}}]
          [:nested-c {:opt (s/maybe s/Int) :req s/Str
                      :inner {:items [s/Int]}}]])))

(deftest cast-dispatch-alloc-probe
  (when (h/enabled?)
    (let [budget-ms 500
          int-t (T s/Int)
          str-t (T s/Str)
          any-t (T s/Any)
          union-10 (T (s/either s/Int s/Str s/Keyword s/Bool s/Num
                                {:a s/Int} {:b s/Str}
                                [s/Int] #{s/Str}
                                (s/maybe s/Int)))
          big-map-src (T (big-map-schema))
          big-map-tgt-same (T (big-map-schema))]

      ;; ── Q2: type=? allocation on early-mismatch and on big-map walk
      (h/measure "type=? early mismatch (int vs str) — alloc baseline"
                 budget-ms #(at/type=? int-t str-t))
      (h/measure "type=? big-map equal shape (full structural walk)"
                 budget-ms #(at/type=? big-map-src big-map-tgt-same))

      ;; ── Q1: cast-ok call alone (the construction the JFR points at)
      ;; Three-arg form -> recurses to 5-arg form -> builds the result map.
      (h/measure "cast-ok 3-arg (constructs 7-key result map)"
                 budget-ms #(ascs/cast-ok int-t int-t :exact))
      (h/measure "cast-ok 5-arg with [] children + nil details"
                 budget-ms #(ascs/cast-ok int-t int-t :exact [] nil))

      ;; ── Q3: dispatch-cast exact fast path — type=? returns true, cast-ok :exact.
      ;; If most cost is cast-ok's map, exact fast path bytes/op ≈ cast-ok bytes/op.
      (let [exact-base   (h/measure "check-cast exact (int->int) [baseline]"
                                    budget-ms #(cast/check-cast int-t int-t))
            target-dyn   (h/measure "check-cast target-dyn (int->Any) [target-dyn fast path]"
                                    budget-ms #(cast/check-cast int-t any-t))
            cast-ok-only (h/measure "cast-ok alone (3-arg)"
                                    budget-ms #(ascs/cast-ok int-t int-t :exact))]
        (println (format "[A/B] check-cast exact vs cast-ok-alone: %s"
                         (pr-str (h/compare-configs cast-ok-only exact-base))))
        (println (format "[A/B] check-cast target-dyn vs cast-ok-alone: %s"
                         (pr-str (h/compare-configs cast-ok-only target-dyn)))))

      ;; Heavy branches: union dispatch, big-map dispatch — establish the
      ;; per-cast-ok-call multiplier for production-realistic inputs.
      (h/measure "check-cast int->union(10 members) [branch dispatch]"
                 budget-ms #(cast/check-cast int-t union-10))
      (h/measure "check-cast big-map ok (30 keys, 3 nested) [map dispatch]"
                 budget-ms #(cast/check-cast big-map-src big-map-tgt-same))))
  (is true))
