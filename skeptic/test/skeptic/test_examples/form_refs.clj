(ns skeptic.test-examples.form-refs
  (:require [schema.core :as s]))

(s/defschema MapBody {:a s/Int :b s/Str})
(s/defschema VecBody [s/Int])
(s/defschema NamedVecBody [#{s/Int}])
(s/defschema RecursiveNamed [#{RecursiveNamed}])
(s/defn fn-with-map-ann :- {:result s/Int :cache s/Str}
  [x :- s/Int]
  {:result x :cache "k"})
(s/defn fn-with-named-map-ann :- {:result RecursiveNamed :cache MapBody}
  [_x :- s/Int]
  {:result [#{[#{}]}] :cache {:a 1 :b "k"}})
(s/defn produce-recursive :- RecursiveNamed [] [#{[#{}]}])
(s/defn produce-map-body :- MapBody [] {:a 1 :b "k"})
(s/defn fn-with-call-results :- {:result RecursiveNamed :cache MapBody}
  []
  {:result (produce-recursive) :cache (produce-map-body)})
(s/defn produce-inner-set :- #{RecursiveNamed} [] #{[#{}]})
(s/defn fn-with-composed-body :- {:result RecursiveNamed :cache MapBody}
  []
  {:result [(produce-inner-set)] :cache (produce-map-body)})
