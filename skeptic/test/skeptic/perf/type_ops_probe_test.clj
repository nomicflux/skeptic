(ns skeptic.perf.type-ops-probe-test
  "Performance probe for skeptic.analysis.type-ops.

   New profile (2026-06-18) ranks
     skeptic.analysis.type_ops/<eval>   total% 8.7   self% 1.5

   Existing cast_hotloop probes only call `normalize` on idempotent
   inputs (an already-canonical SemanticType). The hot paths inside
   type-ops also include:
     - normalize-type   raw schema map / vector / set inputs
     - union-type       member dedup + Maybe lifting
     - intersection-type member dedup
     - de-maybe-type    walks union/conditional members
     - nil-bearing-type-members  feeds union-type-with-normalize
     - uninformative-for-narrowing?  predicate over nested members
     - exact-value-type / literal-ground-type  hot value boundary

   This probe covers each of those with shapes the analyzer sees
   millions of times per run.

   Gated by SKEPTIC_PROBE=1."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

(defn- ground [g sym] (at/->GroundT tp g sym))

(defn- fixtures []
  (let [int-t (ground :int 'Int)
        str-t (ground :str 'Str)
        kw-t  (ground :kw  'Keyword)
        bool-t (ground :bool 'Bool)
        num-t (ground :num 'Num)
        maybe-int (at/->MaybeT tp int-t)
        union-3 (at/->UnionT tp #{int-t str-t kw-t})
        union-5 (at/->UnionT tp #{int-t str-t kw-t bool-t num-t})
        union-10-members (set (for [i (range 10)]
                                (at/->GroundT tp (keyword (str "g" i)) (symbol (str "G" i)))))
        union-10 (at/->UnionT tp union-10-members)
        union-with-maybe (at/->UnionT tp #{maybe-int str-t})
        intersection-2 (at/->IntersectionT tp #{int-t (at/Dyn tp)})
        ;; 12-entry map with one nested level (realistic project record shape).
        ;; Spelled out as a literal so Skeptic infers a MapT here, not a seq.
        big-map-entries
        {(at/->ValueT tp kw-t :nested) (at/->MapT tp {(at/->ValueT tp kw-t :x) int-t
                                                      (at/->ValueT tp kw-t :y) str-t
                                                      (at/->ValueT tp kw-t :z) maybe-int})
         (at/->ValueT tp kw-t :k0) int-t
         (at/->ValueT tp kw-t :k1) str-t
         (at/->ValueT tp kw-t :k2) kw-t
         (at/->ValueT tp kw-t :k3) maybe-int
         (at/->ValueT tp kw-t :k4) int-t
         (at/->ValueT tp kw-t :k5) str-t
         (at/->ValueT tp kw-t :k6) kw-t
         (at/->ValueT tp kw-t :k7) maybe-int
         (at/->ValueT tp kw-t :k8) int-t
         (at/->ValueT tp kw-t :k9) str-t
         (at/->ValueT tp kw-t :k10) kw-t}
        big-map (at/->MapT tp big-map-entries)
        deep-map (at/->MapT tp {(at/->ValueT tp kw-t :a) int-t
                                (at/->ValueT tp kw-t :b) str-t
                                (at/->ValueT tp kw-t :c) (at/->MapT tp
                                                                    {(at/->ValueT tp kw-t :x) int-t
                                                                     (at/->ValueT tp kw-t :y) str-t})
                                (at/->ValueT tp kw-t :d) maybe-int})]
    {:int-t int-t :str-t str-t :kw-t kw-t :num-t num-t :bool-t bool-t
     :maybe-int maybe-int
     :union-3 union-3 :union-5 union-5 :union-10 union-10
     :union-with-maybe union-with-maybe
     :intersection-2 intersection-2
     :deep-map deep-map :big-map big-map}))

(deftest type-ops-probe
  (when (h/enabled?)
    (let [{:keys [int-t str-t kw-t maybe-int
                  union-3 union-5 union-10 union-with-maybe
                  intersection-2 deep-map big-map]} (fixtures)
          budget-ms 500
          raw-map-input {:a 1 :b "x" :c :y}
          ;; 10 distinct ground types (matched to union-10 shape) for
          ;; the union-type dedup probe — exercises member-by-shape
          ;; comparison at realistic counts.
          ten-grounds (mapv #(at/->GroundT tp (keyword (str "g" %)) (symbol (str "G" %)))
                            (range 10))]

      ;; literal-ground-type — fast leaf, hit and miss cases.
      (h/measure "literal-ground-type int"
                 budget-ms #(ato/literal-ground-type tp 42))
      (h/measure "literal-ground-type string"
                 budget-ms #(ato/literal-ground-type tp "hello"))
      (h/measure "literal-ground-type miss (vector)"
                 budget-ms #(ato/literal-ground-type tp [1 2 3]))

      ;; exact-value-type — `(exact-value-type prov v)` is on the hot
      ;; literal path inside annotate.
      (h/measure "exact-value-type nil"
                 budget-ms #(ato/exact-value-type tp nil))
      (h/measure "exact-value-type keyword"
                 budget-ms #(ato/exact-value-type tp :hello))
      (h/measure "exact-value-type small map"
                 budget-ms #(ato/exact-value-type tp {:a 1}))

      ;; derive-prov — single-arg and varargs versions are called
      ;; on every union/intersection/de-maybe convenience invocation.
      (h/measure "derive-prov single"
                 budget-ms #(ato/derive-prov int-t))
      (h/measure "derive-prov 3-arg"
                 budget-ms #(ato/derive-prov int-t str-t kw-t))

      ;; normalize-type — idempotent SemanticType fast path; raw map;
      ;; raw vector; raw set.
      (h/measure "normalize-type SemanticType (fast path)"
                 budget-ms #(ato/normalize-type tp int-t))
      (h/measure "normalize-type raw small map"
                 budget-ms #(ato/normalize-type tp raw-map-input))
      (h/measure "normalize-type raw vector"
                 budget-ms #(ato/normalize-type tp [1 :k "s"]))
      (h/measure "normalize-type raw set"
                 budget-ms #(ato/normalize-type tp #{1 2 3}))
      (h/measure "normalize-type schema-literal nil"
                 budget-ms #(ato/normalize-type tp nil))

      ;; nil-bearing-type-members — feeds union-type. Distinct shapes
      ;; for membership without nil, with nil, and with maybe member.
      (h/measure "nil-bearing-type-members [int str]"
                 budget-ms #(ato/nil-bearing-type-members ato/normalize-type tp [int-t str-t]))
      (h/measure "nil-bearing-type-members [maybe-int str]"
                 budget-ms #(ato/nil-bearing-type-members ato/normalize-type tp [maybe-int str-t]))

      ;; union-type — single member fast path; many distinct members;
      ;; members that include a Maybe (Maybe-lifting branch).
      (h/measure "union-type 1 member (collapse)"
                 budget-ms #(ato/union-type tp [int-t]))
      (h/measure "union-type 3 distinct members"
                 budget-ms #(ato/union-type tp [int-t str-t kw-t]))
      (h/measure "union-type 5 distinct members"
                 budget-ms #(ato/union-type tp [int-t str-t kw-t
                                                (at/->GroundT tp :bool 'Bool)
                                                (at/->GroundT tp :num 'Num)]))
      (h/measure "union-type 10 distinct members"
                 budget-ms #(ato/union-type tp ten-grounds))
      (h/measure "union-type with maybe member (Maybe lifting)"
                 budget-ms #(ato/union-type tp [maybe-int str-t]))

      ;; intersection-type — empty (→ Dyn), single (collapse),
      ;; flat 2-member, nested 2-member (flatten branch).
      (h/measure "intersection-type empty"
                 budget-ms #(ato/intersection-type tp []))
      (h/measure "intersection-type 1 member"
                 budget-ms #(ato/intersection-type tp [int-t]))
      (h/measure "intersection-type 2 distinct"
                 budget-ms #(ato/intersection-type tp [int-t str-t]))
      (h/measure "intersection-type nested intersection (flatten)"
                 budget-ms #(ato/intersection-type tp [intersection-2 str-t]))

      ;; de-maybe-type — fast path (Maybe → inner), union with Maybe
      ;; members, plain non-Maybe pass-through.
      (h/measure "de-maybe-type Maybe (fast path)"
                 budget-ms #(ato/de-maybe-type tp maybe-int))
      (h/measure "de-maybe-type union-with-maybe"
                 budget-ms #(ato/de-maybe-type tp union-with-maybe))
      (h/measure "de-maybe-type plain (pass-through)"
                 budget-ms #(ato/de-maybe-type tp int-t))

      ;; uninformative-for-narrowing? — predicate that recursive
      ;; narrowing consults on every step. Maybe/Union/Conditional
      ;; recurse over members.
      (h/measure "uninformative-for-narrowing? Dyn"
                 budget-ms #(ato/uninformative-for-narrowing? (at/Dyn tp)))
      (h/measure "uninformative-for-narrowing? Maybe(int)"
                 budget-ms #(ato/uninformative-for-narrowing? maybe-int))
      (h/measure "uninformative-for-narrowing? Union 3 grounds"
                 budget-ms #(ato/uninformative-for-narrowing? union-3))
      (h/measure "uninformative-for-narrowing? Union 5 grounds"
                 budget-ms #(ato/uninformative-for-narrowing? union-5))
      (h/measure "uninformative-for-narrowing? Union 10 grounds"
                 budget-ms #(ato/uninformative-for-narrowing? union-10))
      (h/measure "uninformative-for-narrowing? Ground (fast false)"
                 budget-ms #(ato/uninformative-for-narrowing? int-t))

      ;; Convenience wrappers — derive-prov + dispatch. Identifies
      ;; the cost of the convenience layer over the explicit-prov
      ;; calls above.
      (h/measure "normalize (convenience, idempotent)"
                 budget-ms #(ato/normalize int-t))
      (h/measure "union (convenience, 3 members)"
                 budget-ms #(ato/union [int-t str-t kw-t]))
      (h/measure "intersection (convenience, 2 members)"
                 budget-ms #(ato/intersection [int-t str-t]))
      (h/measure "de-maybe (convenience)"
                 budget-ms #(ato/de-maybe maybe-int))

      ;; Sink reference so deep-map participates in JIT (avoid DCE).
      (h/measure "normalize-type deep-map SemanticType (fast path)"
                 budget-ms #(ato/normalize-type tp deep-map))
      (h/measure "normalize-type big-map (12 keys, nested) SemanticType (fast path)"
                 budget-ms #(ato/normalize-type tp big-map))))
  (is true))
