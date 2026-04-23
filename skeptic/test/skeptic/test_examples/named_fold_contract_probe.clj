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

(s/defn compute-result :- Threal [] [#{} #{} #{}])
(s/defn compute-cache :- ThrealCache [] {})

(s/defn add-with-cache-analogue :- {:result Threal :cache ThrealCache}
  []
  {:result (compute-result) :cache (compute-cache)})

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
