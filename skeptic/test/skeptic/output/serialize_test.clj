(ns skeptic.output.serialize-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [are deftest is testing]]
            [skeptic.output.serialize :as sut]))

(defrecord ExampleRec [x y])

(deftest json-safe-returns-a-string
  (testing "every value coerces to a pr-str string"
    (are [v] (string? (sut/json-safe v))
      nil "hi" 42 :kw 'foo/bar
      [1 2 'a] {:a 1 nil 2}
      String #'clojure.core/inc (the-ns 'clojure.core) inc
      (->ExampleRec 1 2))))

(deftest json-safe-is-json-writable
  (testing "data.json accepts the output"
    (let [out (sut/json-safe {:vals [(->ExampleRec 1 2)
                                     #'clojure.core/inc
                                     String
                                     inc
                                     {nil :dropped :kept 1}]})]
      (is (string? (json/write-str out))))))

(deftest json-safe-is-readable-edn
  (testing "output round-trips through the reader for diffable values"
    (is (= {:a 1 :b [2 'sym]}
           (read-string (sut/json-safe {:a 1 :b [2 'sym]}))))))
