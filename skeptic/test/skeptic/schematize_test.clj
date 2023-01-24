(ns skeptic.schematize-test
  (:require [skeptic.schematize :as sut]
            [clojure.test :refer [is are deftest]]))

(deftest arg-list-only-varargs
  (is (= {:count 2, :args '[x y], :with-varargs false, :varargs []}
         (sut/arg-list '[x y])))
  (is (= {:count 1, :args [], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[& rest])))
  (is (= {:count 2, :args '[x], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[x & rest]))))
