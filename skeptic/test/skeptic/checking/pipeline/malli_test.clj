(ns skeptic.checking.pipeline.malli-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]))

(intern 'skeptic.checking.pipeline.malli-test
        (with-meta 'demo-fn {:malli/schema [:=> [:cat :int] :int]})
        (fn [x] x))

(defn- malli-fun-dict-entry
  [sym malli-schema]
  (let [fun-type (amb/malli-spec->type malli-schema)
        method (first (:methods fun-type))
        inputs (:inputs method)
        output (:output method)
        arity (count inputs)
        arg-types (mapv (fn [i t] {:name (symbol (str "arg" i)) :optional? false :type t})
                        (range)
                        inputs)]
    {sym {:name (str sym)
          :type fun-type
          :output-type output
          :arglists {arity {:arglist [] :types arg-types :count arity}}}}))

(deftest malli-=>-int-mismatch-on-keyword-arg
  (let [ns-sym 'skeptic.checking.pipeline.malli-test
        demo-sym 'skeptic.checking.pipeline.malli-test/demo-fn
        dict (merge ps/test-dict (malli-fun-dict-entry demo-sym [:=> [:cat :int] :int]))
        form '(defn demo-fn [x] (demo-fn :nope))
        results (vec (sut/check-s-expr dict
                                       form
                                       {:ns ns-sym
                                        :remove-context true}))]
    (is (= 1 (count results)))
    (is (seq (:errors (first results))))))
