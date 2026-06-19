(ns skeptic.perf.schema-bridge-probe-test
  "Performance probe for the Schema→Type admission boundary.

   New profile (Skeptic on the schema repo, 45.8s window, 2026-06-18)
   ranks:
     skeptic.analysis.bridge/<eval>                 total% 12.4
     skeptic.analysis.bridge/import-schema-type*    total% 10.0
     skeptic.analysis.bridge.localize/localize-value* total% 1.6
     skeptic.analysis.schema_base/canonical-scalar-schema total% 4.2

   These run upstream of the cast hot loop: every project-declared
   schema enters the type domain through this layer once per Var
   admission, and every test/check round-trip pays the localize cost
   for embedded Var refs. Existing probes (cast_hotloop) only call
   `bridge/schema->type` to BUILD fixtures and never measure that build.

   Probes drive three representative shapes:

     - canonical-scalar-schema  — scalar coercion (chain of `=`).
     - bridge/admit-schema      — admission only (no type construction).
     - bridge/schema->type      — full admission + import, ground/maybe/
                                  map/union/=> fixtures from cheap to deep.
     - bridge/import-schema-type — same as schema->type but takes an
                                  already-admitted value, isolating the
                                  import-schema-type* recursive runner.
     - bridge.localize/localize-value — Var/collection localization
                                        cost on raw schema bodies.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.localize :as localize]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- deep-map-schema []
  {:a s/Int :b s/Str :c s/Keyword :d s/Bool
   :e {:x s/Int :y s/Str}
   :f {:m s/Keyword :n s/Bool}
   :g s/Num :h (s/maybe s/Int)})

(defn- big-map-schema
  "30-key, 3-level-nested project record schema. Representative of the
   shape Schema admission walks for a typical domain Var."
  []
  (into {}
        (concat
         (for [i (range 10)] [(keyword (str "f" i)) s/Int])
         (for [i (range 10)] [(keyword (str "s" i)) s/Str])
         [[:nested-a {:x s/Int :y s/Str :z s/Keyword
                      :inner {:p s/Int :q s/Bool :r (s/maybe s/Int)}}]
          [:nested-b {:m s/Keyword :n s/Bool
                      :inner {:p s/Num :q (s/maybe s/Int)}}]
          [:nested-c {:opt (s/maybe s/Int) :req s/Str
                      :inner {:items [s/Int]
                              :meta {:label s/Keyword}}}]])))

(defn- big-union-schema []
  (apply s/either [s/Int s/Str s/Keyword s/Bool s/Num
                   {:a s/Int} (s/maybe s/Int) [s/Int] #{s/Str}]))

(defn- huge-union-schema
  "10-member union mixing scalars, maybe, vec, set, map."
  []
  (apply s/either [s/Int s/Str s/Keyword s/Bool s/Num
                   {:a s/Int :b s/Str} (s/maybe s/Keyword)
                   [s/Num] #{s/Bool} (s/=> s/Int s/Int)]))

(defn- fn-schema []
  (s/=> s/Int s/Int s/Str))

(deftest schema-bridge-probe
  (when (h/enabled?)
    (let [budget-ms 500
          int-s       s/Int
          maybe-int-s (s/maybe s/Int)
          union-s     (s/either s/Int s/Str)
          map-s       {:a s/Int :b s/Str}
          deep-map-s  (deep-map-schema)
          big-map-s   (big-map-schema)
          big-union-s (big-union-schema)
          huge-union-s (huge-union-schema)
          fn-s        (fn-schema)
          int-admit       (ab/admit-schema int-s)
          maybe-admit     (ab/admit-schema maybe-int-s)
          map-admit       (ab/admit-schema map-s)
          deep-map-admit  (ab/admit-schema deep-map-s)
          big-map-admit   (ab/admit-schema big-map-s)
          union-admit     (ab/admit-schema union-s)
          big-union-admit (ab/admit-schema big-union-s)
          huge-union-admit (ab/admit-schema huge-union-s)
          fn-admit        (ab/admit-schema fn-s)]

      ;; canonical-scalar-schema — JFR total% 4.2. Pure cond chain over `=`.
      ;; First-match (s/Int alias), late-match (java.lang.Boolean),
      ;; and miss-then-passthrough fall through different cond arms.
      (h/measure "canonical-scalar-schema s/Int (early hit)"
                 budget-ms #(sb/canonical-scalar-schema s/Int))
      (h/measure "canonical-scalar-schema java.lang.Long (mid hit)"
                 budget-ms #(sb/canonical-scalar-schema java.lang.Long))
      (h/measure "canonical-scalar-schema java.lang.Boolean (late hit)"
                 budget-ms #(sb/canonical-scalar-schema java.lang.Boolean))
      (h/measure "canonical-scalar-schema map (miss, passthrough)"
                 budget-ms #(sb/canonical-scalar-schema {:a s/Int}))

      ;; admit-schema — the admission half of schema->type, isolated.
      ;; Walks the schema once without minting Type records.
      (h/measure "admit-schema s/Int"
                 budget-ms #(ab/admit-schema int-s))
      (h/measure "admit-schema (maybe s/Int)"
                 budget-ms #(ab/admit-schema maybe-int-s))
      (h/measure "admit-schema small map"
                 budget-ms #(ab/admit-schema map-s))
      (h/measure "admit-schema deep map (8 keys, 2 nested)"
                 budget-ms #(ab/admit-schema deep-map-s))
      (h/measure "admit-schema big map (30 keys, 3 nested)"
                 budget-ms #(ab/admit-schema big-map-s))
      (h/measure "admit-schema union (2 members)"
                 budget-ms #(ab/admit-schema union-s))
      (h/measure "admit-schema union (8 mixed members)"
                 budget-ms #(ab/admit-schema big-union-s))
      (h/measure "admit-schema union (10 mixed members + fn)"
                 budget-ms #(ab/admit-schema huge-union-s))
      (h/measure "admit-schema (=> int int str)"
                 budget-ms #(ab/admit-schema fn-s))

      ;; import-schema-type — the import half, on already-admitted input.
      ;; This is the JFR's import-schema-type* hot recursive runner.
      (h/measure "import-schema-type s/Int (admitted)"
                 budget-ms #(ab/import-schema-type tp int-admit))
      (h/measure "import-schema-type (maybe s/Int) (admitted)"
                 budget-ms #(ab/import-schema-type tp maybe-admit))
      (h/measure "import-schema-type small map (admitted)"
                 budget-ms #(ab/import-schema-type tp map-admit))
      (h/measure "import-schema-type deep map (admitted)"
                 budget-ms #(ab/import-schema-type tp deep-map-admit))
      (h/measure "import-schema-type big map 30k 3-nest (admitted)"
                 budget-ms #(ab/import-schema-type tp big-map-admit))
      (h/measure "import-schema-type union (admitted)"
                 budget-ms #(ab/import-schema-type tp union-admit))
      (h/measure "import-schema-type big union (admitted)"
                 budget-ms #(ab/import-schema-type tp big-union-admit))
      (h/measure "import-schema-type huge union 10m (admitted)"
                 budget-ms #(ab/import-schema-type tp huge-union-admit))
      (h/measure "import-schema-type fn (admitted)"
                 budget-ms #(ab/import-schema-type tp fn-admit))

      ;; schema->type — full pipeline (admit + import). The cost the
      ;; analyzer pays per Var. Compare to import-only to attribute
      ;; how much is admission vs import.
      (h/measure "schema->type s/Int (full)"
                 budget-ms #(ab/schema->type tp int-s))
      (h/measure "schema->type small map (full)"
                 budget-ms #(ab/schema->type tp map-s))
      (h/measure "schema->type deep map (full)"
                 budget-ms #(ab/schema->type tp deep-map-s))
      (h/measure "schema->type big map 30k 3-nest (full)"
                 budget-ms #(ab/schema->type tp big-map-s))
      (h/measure "schema->type big union (full)"
                 budget-ms #(ab/schema->type tp big-union-s))
      (h/measure "schema->type huge union 10m (full)"
                 budget-ms #(ab/schema->type tp huge-union-s))
      (h/measure "schema->type fn (full)"
                 budget-ms #(ab/schema->type tp fn-s))

      ;; localize-value — walks raw schema values, copying Vars and
      ;; collections. The JFR's localize-value* sits inside
      ;; bridge/<eval> recursion when descriptors carry context maps.
      (h/measure "localize-value s/Int (already-typed)"
                 budget-ms #(localize/localize-value s/Int))
      (h/measure "localize-value small map"
                 budget-ms #(localize/localize-value map-s))
      (h/measure "localize-value deep map"
                 budget-ms #(localize/localize-value deep-map-s))
      (h/measure "localize-value big map 30k 3-nest"
                 budget-ms #(localize/localize-value big-map-s))))
  (is true))
