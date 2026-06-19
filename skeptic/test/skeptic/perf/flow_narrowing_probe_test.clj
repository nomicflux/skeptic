(ns skeptic.perf.flow-narrowing-probe-test
  "Performance probe for flow-sensitive narrowing.

   New profile (2026-06-18) ranks:
     skeptic.analysis.origin/<eval>      total% 8.6   self% 0.3
     skeptic.analysis.origin/local-type-and-origin self% 0.1
     skeptic.analysis.narrowing/<eval>   total% 1.0   self% 0.4
     skeptic.analysis.map_ops/<eval>     total% 5.1   self% 0.4
     skeptic.analysis.value/<eval>       total% 2.4   self% 0.6
     skeptic.analysis.sum_types/open-type-QMARK-  self% 0.1

   These are the per-branch and per-call helpers that build refined
   local environments. They run on every if/case/when/cond/let in
   the project source, often several times per node as branches
   recurse.

   This probe drives the helpers directly with realistic assumption
   shapes and map types so the cost is attributed at the function
   level, separate from the annotate dispatch above and the cast
   engine below.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.narrowing :as anr]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- ground [g sym] (at/->GroundT tp g sym))

(defn- fixtures []
  (let [int-t (ground :int 'Int)
        str-t (ground :str 'Str)
        kw-t  (ground :kw  'Keyword)
        bool-t (ground :bool 'Bool)
        nil-val (at/->ValueT tp (at/Dyn tp) nil)
        false-val (at/->ValueT tp bool-t false)
        true-val (at/->ValueT tp bool-t true)
        maybe-int (at/->MaybeT tp int-t)
        union-int-str (at/->UnionT tp #{int-t str-t})
        union-int-nil-false (at/->UnionT tp #{int-t nil-val false-val})
        small-map (at/->MapT tp {(at/->ValueT tp kw-t :a) int-t
                                 (at/->ValueT tp kw-t :b) str-t})
        deep-map (at/->MapT tp
                            {(at/->ValueT tp kw-t :a) int-t
                             (at/->ValueT tp kw-t :b) str-t
                             (at/->ValueT tp kw-t :c)
                             (at/->MapT tp {(at/->ValueT tp kw-t :x) int-t
                                            (at/->ValueT tp kw-t :y) str-t})
                             (at/->ValueT tp kw-t :d) maybe-int})
        root-int (ao/root-origin 'x int-t)
        root-maybe (ao/root-origin 'y maybe-int)
        root-union (ao/root-origin 'z union-int-str)
        root-map (ao/root-origin 'm small-map)
        truthy-pos (ao/truthy-local-assumption root-maybe true)
        truthy-neg (ao/truthy-local-assumption root-maybe false)
        nil-pred-pos (ao/type-predicate-assumption root-maybe
                                                   {:pred :nil?}
                                                   true)
        nil-pred-neg (ao/type-predicate-assumption root-maybe
                                                   {:pred :nil?}
                                                   false)
        contains-key-pos (ao/contains-key-assumption root-map :a true)
        path-pred (ao/path-type-predicate-assumption
                   root-map [(amo/exact-key-query tp :a)] {:pred :nil?} false)]
    {:int-t int-t :str-t str-t :kw-t kw-t :bool-t bool-t
     :nil-val nil-val :false-val false-val :true-val true-val
     :maybe-int maybe-int :union-int-str union-int-str
     :union-int-nil-false union-int-nil-false
     :small-map small-map :deep-map deep-map
     :root-int root-int :root-maybe root-maybe
     :root-union root-union :root-map root-map
     :truthy-pos truthy-pos :truthy-neg truthy-neg
     :nil-pred-pos nil-pred-pos :nil-pred-neg nil-pred-neg
     :contains-key-pos contains-key-pos :path-pred path-pred}))

(deftest flow-narrowing-probe
  (when (h/enabled?)
    (let [{:keys [int-t str-t kw-t maybe-int
                  union-int-str union-int-nil-false
                  small-map deep-map
                  root-maybe truthy-pos truthy-neg
                  nil-pred-pos nil-pred-neg contains-key-pos path-pred]} (fixtures)
          budget-ms 500
          nil-pred {:pred :nil?}
          string-pred {:pred :string?}]

      ;; narrowing/apply-truthy-local — pos branch removes nil/false members
      ;; from a union; neg returns input.
      (h/measure "apply-truthy-local Maybe(int) polarity=true"
                 budget-ms #(anr/apply-truthy-local maybe-int true))
      (h/measure "apply-truthy-local Maybe(int) polarity=false"
                 budget-ms #(anr/apply-truthy-local maybe-int false))
      (h/measure "apply-truthy-local Union(int nil false) polarity=true"
                 budget-ms #(anr/apply-truthy-local union-int-nil-false true))
      (h/measure "apply-truthy-local Union(int str) polarity=true"
                 budget-ms #(anr/apply-truthy-local union-int-str true))
      (h/measure "apply-truthy-local Ground (no Maybe layer)"
                 budget-ms #(anr/apply-truthy-local int-t true))

      ;; narrowing/partition-type-for-predicate — the workhorse of every
      ;; type-predicate refinement. Ground hit, ground miss, maybe layer,
      ;; union of mixed members.
      (h/measure "partition-type-for-predicate :nil? on Maybe(int) pos"
                 budget-ms #(anr/partition-type-for-predicate maybe-int nil-pred true))
      (h/measure "partition-type-for-predicate :nil? on Maybe(int) neg"
                 budget-ms #(anr/partition-type-for-predicate maybe-int nil-pred false))
      (h/measure "partition-type-for-predicate :string? on Union(int str) pos"
                 budget-ms #(anr/partition-type-for-predicate union-int-str string-pred true))
      (h/measure "partition-type-for-predicate :string? on Ground int (always miss)"
                 budget-ms #(anr/partition-type-for-predicate int-t string-pred true))

      ;; narrowing/can-be-falsy-type? — called repeatedly on test
      ;; expressions inside if/when.
      (h/measure "can-be-falsy-type? Maybe(int)"
                 budget-ms #(anr/can-be-falsy-type? maybe-int))
      (h/measure "can-be-falsy-type? Union(int nil false)"
                 budget-ms #(anr/can-be-falsy-type? union-int-nil-false))
      (h/measure "can-be-falsy-type? Ground int"
                 budget-ms #(anr/can-be-falsy-type? int-t))

      ;; origin/apply-assumption-to-root-type — the dispatch table for
      ;; every refined-binding emission.
      (h/measure "apply-assumption-to-root-type truthy-local pos on Maybe"
                 budget-ms #(ao/apply-assumption-to-root-type maybe-int truthy-pos))
      (h/measure "apply-assumption-to-root-type truthy-local neg on Maybe"
                 budget-ms #(ao/apply-assumption-to-root-type maybe-int truthy-neg))
      (h/measure "apply-assumption-to-root-type type-predicate :nil? pos"
                 budget-ms #(ao/apply-assumption-to-root-type maybe-int nil-pred-pos))
      (h/measure "apply-assumption-to-root-type type-predicate :nil? neg"
                 budget-ms #(ao/apply-assumption-to-root-type maybe-int nil-pred-neg))
      (h/measure "apply-assumption-to-root-type contains-key pos on map"
                 budget-ms #(ao/apply-assumption-to-root-type small-map contains-key-pos))
      (h/measure "apply-assumption-to-root-type path-type-predicate"
                 budget-ms #(ao/apply-assumption-to-root-type deep-map path-pred))

      ;; origin/refine-root-type — reduces a list of assumptions.
      (h/measure "refine-root-type 1 assumption (matching root)"
                 budget-ms #(ao/refine-root-type root-maybe [truthy-pos]))
      (h/measure "refine-root-type 3 assumptions (matching root)"
                 budget-ms #(ao/refine-root-type root-maybe
                                                 [truthy-pos nil-pred-neg nil-pred-pos]))
      (h/measure "refine-root-type 8 assumptions (matching root)"
                 budget-ms #(ao/refine-root-type root-maybe
                                                 [truthy-pos truthy-neg
                                                  nil-pred-pos nil-pred-neg
                                                  truthy-pos truthy-neg
                                                  nil-pred-pos nil-pred-neg]))

      ;; origin/local-type-and-origin — JFR shows local-type-and-origin
      ;; self% 0.1; called once per local lookup during annotation. Probe
      ;; the private fn via the var; the call hits the plain-map dispatch
      ;; arm (the most common entry shape). ctx needs a prov set because
      ;; the Dyn-fallback arm calls aapi/dyn ctx, which requires it.
      (let [local-tao @#'ao/local-type-and-origin
            ctx (prov/set-ctx {} tp)
            map-entry {:type maybe-int}
            type-entry int-t
            empty-entry {}]
        (h/measure "local-type-and-origin plain-map entry"
                   budget-ms #(local-tao ctx 'x map-entry))
        (h/measure "local-type-and-origin SemanticType entry"
                   budget-ms #(local-tao ctx 'x type-entry))
        (h/measure "local-type-and-origin missing entry (Dyn fallback)"
                   budget-ms #(local-tao ctx 'x empty-entry)))

      ;; origin/node-origin / root-origin / opaque-origin — cheap helpers
      ;; that build the origin records narrowing consults.
      (h/measure "root-origin (already normalized)"
                 budget-ms #(ao/root-origin 'x int-t))
      (h/measure "opaque-origin (already normalized)"
                 budget-ms #(ao/opaque-origin int-t))

      ;; origin/assumption builders themselves — the call sites
      ;; emit hundreds of these per namespace.
      (h/measure "truthy-local-assumption (build)"
                 budget-ms #(ao/truthy-local-assumption root-maybe true))
      (h/measure "type-predicate-assumption (build)"
                 budget-ms #(ao/type-predicate-assumption root-maybe nil-pred true))
      (h/measure "contains-key-assumption (build)"
                 budget-ms #(ao/contains-key-assumption root-maybe :a true))

      ;; map_ops/map-get-type — every (:k m) call site burns this.
      ;; Callers wrap the lookup key with exact-key-query (literal) or
      ;; domain-key-query (Type-typed); raw keys are not the public API.
      (let [q-a (amo/exact-key-query tp :a)
            q-missing (amo/exact-key-query tp :missing)
            q-c (amo/exact-key-query tp :c)]
        (h/measure "map-get-type small-map :a (hit)"
                   budget-ms #(amo/map-get-type small-map q-a))
        (h/measure "map-get-type small-map :missing (miss)"
                   budget-ms #(amo/map-get-type small-map q-missing))
        (h/measure "map-get-type deep-map :c (nested hit)"
                   budget-ms #(amo/map-get-type deep-map q-c)))

      ;; map_ops/map-type-at-path — drives :path-* assumptions.
      ;; Path elements are exact-key-query records, mirroring how
      ;; call_kinds.assumption builds them.
      (let [p-a [(amo/exact-key-query tp :a)]
            p-c-x [(amo/exact-key-query tp :c) (amo/exact-key-query tp :x)]
            p-missing [(amo/exact-key-query tp :missing)]
            ;; 3-level descent on a deeper map fixture
            big-kw-t kw-t
            three-deep-map
            (at/->MapT tp
                       {(at/->ValueT tp big-kw-t :L1)
                        (at/->MapT tp
                                   {(at/->ValueT tp big-kw-t :L2)
                                    (at/->MapT tp
                                               {(at/->ValueT tp big-kw-t :L3) int-t
                                                (at/->ValueT tp big-kw-t :other) str-t})})})
            p-deep [(amo/exact-key-query tp :L1)
                    (amo/exact-key-query tp :L2)
                    (amo/exact-key-query tp :L3)]]
        (h/measure "map-type-at-path [:a]"
                   budget-ms #(amo/map-type-at-path deep-map p-a))
        (h/measure "map-type-at-path [:c :x] (depth 2)"
                   budget-ms #(amo/map-type-at-path deep-map p-c-x))
        (h/measure "map-type-at-path [:L1 :L2 :L3] (depth 3)"
                   budget-ms #(amo/map-type-at-path three-deep-map p-deep))
        (h/measure "map-type-at-path [:missing] (miss)"
                   budget-ms #(amo/map-type-at-path deep-map p-missing)))

      ;; map_ops/refine-by-contains-key — assumption-applies dispatch.
      (h/measure "refine-by-contains-key :a true"
                 budget-ms #(amo/refine-by-contains-key small-map :a true))
      (h/measure "refine-by-contains-key :missing true"
                 budget-ms #(amo/refine-by-contains-key small-map :missing true))

      ;; map_ops/merge-map-types — anchor-prov branch on every Map
      ;; constructed from N maps.
      (h/measure "merge-map-types 2 small maps"
                 budget-ms #(amo/merge-map-types tp [small-map small-map]))

      ;; analysis.value/type-of-value, exact-runtime-value-type — the
      ;; analyzer hits these on every literal node in the AST.
      (h/measure "type-of-value 42"
                 budget-ms #(av/type-of-value tp 42))
      (h/measure "type-of-value :keyword"
                 budget-ms #(av/type-of-value tp :hello))
      (h/measure "type-of-value small map"
                 budget-ms #(av/type-of-value tp {:a 1 :b 2}))
      (h/measure "exact-runtime-value-type :keyword"
                 budget-ms #(av/exact-runtime-value-type tp :hello))
      (h/measure "type-of-value vector(8 ints)"
                 budget-ms #(av/type-of-value tp [1 2 3 4 5 6 7 8]))
      (h/measure "value/join Ground int + str"
                 budget-ms #(av/join tp [int-t str-t]))
      (h/measure "value/join Ground int + Maybe(int)"
                 budget-ms #(av/join tp [int-t maybe-int]))

      ;; Reference the unused kw-t binding so the deftest binding form
      ;; doesn't get a clj-kondo warning. (No timing cost: not in a
      ;; measure thunk.)
      (is (some? kw-t))))
  (is true))
