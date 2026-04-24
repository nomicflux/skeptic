(ns skeptic.test-examples.named-fold-contract-probe
  (:require [schema.core :as s]))

(def Foo (s/named s/Int 'Foo))
(def Bar (s/named s/Int 'Foo))

(s/def x :- Foo 1)
(s/def y :- Bar 1)
(s/def z :- (s/named s/Int 'Foo) 1)
(def MyInt s/Int)
(s/def w :- MyInt 1)
(s/def q :- s/Int 1)

(s/defschema RecursiveNamed [#{RecursiveNamed}])
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})
(s/defn fn-with-call :- {:result RecursiveNamed}
  []
  {:result (produce-inner-set)})
(s/defn fn-with-composed :- {:result RecursiveNamed}
  []
  {:result [(produce-inner-set)]})
(s/defn fn-with-literal :- {:result RecursiveNamed}
  []
  {:result [#{1 2 3}]})

(s/defschema Threal [#{Threal} #{Threal} #{Threal}])
(s/defschema ThrealCache {Threal Threal})
(s/defschema ThrealPairCache {[Threal Threal] Threal})

(s/defn compute-result :- Threal [] [#{} #{} #{}])
(s/defn compute-cache :- ThrealCache [] {})
(s/defn compute-pair-cache :- ThrealPairCache [] {})

(s/defn cache-hit-analogue :- {:result Threal :cache ThrealPairCache}
  [x :- Threal
   y :- Threal
   cache :- ThrealPairCache]
  {:result (or (get cache [x y]) x)
   :cache cache})

(s/defn add-with-cache-analogue :- {:result Threal :cache ThrealCache}
  []
  {:result (compute-result) :cache (compute-cache)})

(s/defn visible-add-with-cache-mismatch :- s/Int
  []
  (add-with-cache-analogue))

(s/defn visible-cache-hit-mismatch :- s/Int
  []
  (cache-hit-analogue (compute-result) (compute-result) (compute-pair-cache)))

(s/defn grow-pair-cache :- ThrealPairCache
  [cache :- ThrealPairCache
   x :- Threal
   y :- Threal]
  (assoc cache [x y] x))

(s/defn recur-cache-fold-probe :- s/Int
  []
  (loop [cache {}
         x (compute-result)
         n 1]
    (if (zero? n)
      0
      (recur (grow-pair-cache cache x x) x (dec n)))))

(def IntBranch s/Int)
(def StrBranch s/Str)

(s/defn produce-conditional
  :- (s/conditional integer? IntBranch string? StrBranch)
  [x :- (s/conditional integer? IntBranch string? StrBranch)]
  x)

(s/defn conditional-branch-render :- {:result s/Any}
  [x :- (s/conditional integer? IntBranch string? StrBranch)]
  {:result (produce-conditional x)})

(s/defn visible-conditional-branch-mismatch :- s/Int
  []
  {:result (produce-conditional 1)})

(s/defn produce-either :- (s/either IntBranch StrBranch)
  [x :- (s/either IntBranch StrBranch)]
  x)

(s/defn produce-cond-pre :- (s/cond-pre IntBranch StrBranch)
  [x :- (s/cond-pre IntBranch StrBranch)]
  x)

(s/defn produce-both :- (s/both IntBranch StrBranch)
  [x :- (s/both IntBranch StrBranch)]
  x)

(s/defn produce-maybe :- (s/maybe IntBranch)
  [x :- (s/maybe IntBranch)]
  x)

(s/defn produce-constrained :- (s/constrained IntBranch pos?)
  [x :- (s/constrained IntBranch pos?)]
  x)

(s/defn visible-either-branch-mismatch :- s/Int
  []
  {:result (produce-either 1)})

(s/defn visible-cond-pre-branch-mismatch :- s/Int
  []
  {:result (produce-cond-pre 1)})

(s/defn visible-both-branch-mismatch :- s/Int
  []
  {:result (produce-both 1)})

(s/defn visible-maybe-branch-mismatch :- s/Int
  []
  {:result (produce-maybe 1)})

(s/defn visible-constrained-branch-mismatch :- s/Int
  []
  {:result (produce-constrained 1)})

(s/defschema FlowCache {s/Int s/Int})
(s/def flow-cache-value :- FlowCache {})

(defn cache-id
  [x]
  x)

(def inferred-cache (cache-id flow-cache-value))
(def inferred-cache-vector [(cache-id flow-cache-value)])

(s/defschema Map1 {:a s/Int})
(s/defschema Map2 {:a s/Int})

(s/def map1-value :- Map1 {:a 1})
(s/def map2-value :- Map2 {:a 1})

(s/defn add-with-cache-input-probe :- {:result Threal :cache ThrealCache}
  ([]
   {:result (compute-result) :cache (compute-cache)})
  ([[x-reds x-greens x-blues :as x] :- Threal
    [y-reds y-greens y-blues :as y] :- Threal
    cache :- ThrealCache]
   {:result x :cache cache}))

(s/defn varargs-input-probe :- s/Int
  [head :- Threal & rest]
  1)
