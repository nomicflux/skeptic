(ns skeptic.perf.cast-hotloop-probe-test
  "Performance probe for perf-cast-hotloop facet.

   Measures dispatch-cast, run-cast, at/type=?, semantic-type=?,
   cast.map/map-children, ato/normalize, type-hash, cast.collection
   against source/target type pairs the analyzer sees in real workloads
   (ground equal, ground vs big union, function vs function, deeply
   nested 30-key map vs map, union of 10 members, vectors and sets).

   Input sizes are deliberately larger than toy examples so the cost
   reflects what the profiler observed on a 45s run (dispatch-cast 21.9%
   total, map-children 17.7%, semantic-type=? 9.5%, type-hash 1.9%).

   Gated by SKEPTIC_PROBE=1 so the default test run stays a
   correctness check."
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.cast.collection :as ccoll]
            [skeptic.analysis.cast.map :as cmap]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- T [schema] (ab/schema->type tp schema))

(defn- big-map-schema
  "30-key map schema with 3 nested levels — the shape of a typical
   project's domain record (config map, request body, dict entry)."
  []
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

(defn- fixture-types []
  (let [int-t (T s/Int)
        str-t (T s/Str)
        any-t (T s/Any)
        kw-t (T s/Keyword)
        union-int-str (T (s/either s/Int s/Str))
        union-many (T (s/either s/Int s/Str s/Keyword s/Bool s/Num))
        union-10 (T (s/either s/Int s/Str s/Keyword s/Bool s/Num
                              {:a s/Int} {:b s/Str}
                              [s/Int] #{s/Str}
                              (s/maybe s/Int)))
        big-map-src (T (big-map-schema))
        big-map-tgt-same (T (big-map-schema))
        ;; Same schema but with one leaf altered deep in nested-b
        big-map-tgt-diff
        (T (assoc-in (big-map-schema) [:nested-b :inner :p] s/Keyword))
        vec-int (T [s/Int])
        vec-int-diff (T [s/Str])
        set-int (T #{s/Int})
        set-str (T #{s/Str})
        fn-src (T (s/=> s/Int s/Int s/Str))
        fn-tgt (T (s/=> s/Int s/Int s/Str))
        fn-tgt-diff (T (s/=> s/Str s/Int s/Str))]
    {:int-t int-t :str-t str-t :any-t any-t :kw-t kw-t
     :union-int-str union-int-str :union-many union-many :union-10 union-10
     :big-map-src big-map-src
     :big-map-tgt-same big-map-tgt-same
     :big-map-tgt-diff big-map-tgt-diff
     :vec-int vec-int :vec-int-diff vec-int-diff
     :set-int set-int :set-str set-str
     :fn-src fn-src :fn-tgt-same fn-tgt :fn-tgt-diff fn-tgt-diff}))

(deftest cast-hotloop-probe
  (when (h/enabled?)
    (let [{:keys [int-t str-t any-t kw-t union-int-str union-10
                  big-map-src big-map-tgt-same big-map-tgt-diff
                  vec-int vec-int-diff set-int set-str
                  fn-src fn-tgt-same fn-tgt-diff]} (fixture-types)
          budget-ms 500
          type-hash-fn @#'at/type-hash]

      ;; type=? — the JFR #2 self-time hit. Regimes the analyzer hits:
      ;;  - identical refs: identical? short-circuit (read of dict entry vs itself)
      ;;  - shape-equal distinct refs: bridge mints fresh prov on every read
      ;;  - early tag mismatch: short-circuit
      ;;  - deeply-nested map equal shape: full structural walk
      ;;  - big union equal: per-member match
      (h/measure "type=? identical refs (int=int)"
                 budget-ms #(at/type=? int-t int-t))
      (h/measure "type=? distinct refs same shape (int =? int)"
                 budget-ms #(at/type=? int-t (T s/Int)))
      (h/measure "type=? early mismatch (int =? str)"
                 budget-ms #(at/type=? int-t str-t))
      (h/measure "type=? big-map equal shape (30 keys, 3 nested)"
                 budget-ms #(at/type=? big-map-src big-map-tgt-same))
      (h/measure "type=? big-map differs at deep leaf"
                 budget-ms #(at/type=? big-map-src big-map-tgt-diff))
      (h/measure "type=? union 10 members distinct refs"
                 budget-ms #(at/type=? union-10 (T (s/either s/Int s/Str s/Keyword s/Bool s/Num
                                                             {:a s/Int} {:b s/Str}
                                                             [s/Int] #{s/Str}
                                                             (s/maybe s/Int)))))

      ;; type-hash — JFR shows type-hash/<inner> at 1.9% total. Hash is
      ;; called per-element by dedup-types and by any cache key.
      (h/measure "type-hash int (ground leaf)"
                 budget-ms #(type-hash-fn int-t))
      (h/measure "type-hash big-map (30 keys, 3 nested)"
                 budget-ms #(type-hash-fn big-map-src))
      (h/measure "type-hash union 10 members"
                 budget-ms #(type-hash-fn union-10))

      ;; normalize — claimed idempotent + cheap; measure repeated re-norm cost.
      (h/measure "normalize int (idempotent input)"
                 budget-ms #(ato/normalize int-t))
      (h/measure "normalize big-map (idempotent input)"
                 budget-ms #(ato/normalize big-map-src))

      ;; dispatch-cast — the JFR hottest path (total% 21.9% — 173 samples).
      ;;  - exact same-type fast path
      ;;  - target-dyn fast path
      ;;  - union branch (10 members reflects realistic project unions)
      ;;  - function vs function
      ;;  - big-map vs big-map (drives map-children at 17.7% total)
      (h/measure "check-cast exact (int->int)"
                 budget-ms #(cast/check-cast int-t int-t))
      (h/measure "check-cast target-dyn (int->Any)"
                 budget-ms #(cast/check-cast int-t any-t))
      (h/measure "check-cast int->union(2 members)"
                 budget-ms #(cast/check-cast int-t union-int-str))
      (h/measure "check-cast int->union(10 members)"
                 budget-ms #(cast/check-cast int-t union-10))
      (h/measure "check-cast kw->union(2 members) (fails)"
                 budget-ms #(cast/check-cast kw-t union-int-str))
      (h/measure "check-cast fn ok (same)"
                 budget-ms #(cast/check-cast fn-src fn-tgt-same))
      (h/measure "check-cast fn domain mismatch"
                 budget-ms #(cast/check-cast fn-src fn-tgt-diff))
      (h/measure "check-cast big-map ok (30 keys, 3 nested, same)"
                 budget-ms #(cast/check-cast big-map-src big-map-tgt-same))
      (h/measure "check-cast big-map deep-leaf mismatch"
                 budget-ms #(cast/check-cast big-map-src big-map-tgt-diff))

      ;; map-children explicitly (JFR total% 17.7%; total# 140 in the run).
      ;; run-child is the single-arg request fn from cast.clj. The probe-local
      ;; wrapper reproduces enough of that shape to call check-map-cast directly.
      (let [probe-run-child
            (fn [{:keys [source-type target-type opts]}]
              (cast/check-cast source-type target-type opts))]
        (h/measure "cmap/check-map-cast big-map ok (30 keys, 3 nested)"
                   budget-ms #(cmap/check-map-cast
                               probe-run-child
                               big-map-src big-map-tgt-same
                               {:polarity :positive}))
        (h/measure "cmap/check-map-cast big-map mismatch"
                   budget-ms #(cmap/check-map-cast
                               probe-run-child
                               big-map-src big-map-tgt-diff
                               {:polarity :positive}))

        ;; cast.collection — JFR shows cast.collection/<eval> 0.8% total.
        ;; ordered-coll on vectors and set check-cast for sets are both
        ;; hit by projects that pass vectors/sets to declared schemas.
        (h/measure "ccoll/check-ordered-coll-cast vec(int)->vec(int) ok"
                   budget-ms #(ccoll/check-ordered-coll-cast
                               probe-run-child
                               vec-int vec-int
                               {:polarity :positive}))
        (h/measure "ccoll/check-ordered-coll-cast vec(int)->vec(str) mismatch"
                   budget-ms #(ccoll/check-ordered-coll-cast
                               probe-run-child
                               vec-int vec-int-diff
                               {:polarity :positive}))
        (h/measure "ccoll/check-set-cast set(int)->set(int) ok"
                   budget-ms #(ccoll/check-set-cast
                               probe-run-child
                               set-int set-int
                               {:polarity :positive}))
        (h/measure "ccoll/check-set-cast set(int)->set(str) mismatch"
                   budget-ms #(ccoll/check-set-cast
                               probe-run-child
                               set-int set-str
                               {:polarity :positive})))))
  (is true))
