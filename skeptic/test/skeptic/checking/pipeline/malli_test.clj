(ns skeptic.checking.pipeline.malli-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.test-examples.malli]
            [skeptic.typed-decls :as typed-decls]))

(deftest malli-=>-int-mismatch-on-keyword-arg
  (let [dict (merge ps/test-dict (typed-decls/typed-ns-entries {} 'skeptic.test-examples.malli))
        form '(defn wrapper [x] (demo-fn :nope))
        results (vec (sut/check-s-expr dict
                                       form
                                       {:ns 'skeptic.test-examples.malli
                                        :remove-context true}))]
    (is (pos? (count results)))
    (is (seq (:errors (first results))))))
