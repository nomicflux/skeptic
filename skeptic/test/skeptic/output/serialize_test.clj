(ns skeptic.output.serialize-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [are deftest is testing]]
            [skeptic.output.serialize :as sut]))

(defrecord ExampleRec [x y])

(deftest json-safe-primitives
  (are [in] (= in (sut/json-safe in))
    nil "hi" 42 3.14 true false :kw))

(deftest json-safe-symbol-stringifies
  (is (= "foo/bar" (sut/json-safe 'foo/bar))))

(deftest json-safe-var
  (let [v #'clojure.core/inc
        out (sut/json-safe v)]
    (is (= "var-ref" (:t out)))
    (is (= "#'clojure.core/inc" (:sym out)))))

(deftest json-safe-class
  (is (= {:t "class" :name "java.lang.String"}
         (sut/json-safe String))))

(deftest json-safe-namespace
  (let [out (sut/json-safe (the-ns 'clojure.core))]
    (is (= {:t "ns" :name "clojure.core"} out))))

(deftest json-safe-fn
  (let [out (sut/json-safe inc)]
    (is (= "fn" (:t out)))
    (is (string? (:pr out)))))

(deftest json-safe-record
  (let [out (sut/json-safe (->ExampleRec 1 2))]
    (is (= 1 (:x out)))
    (is (= 2 (:y out)))
    (is (= "skeptic.output.serialize_test.ExampleRec" (:_class out)))))

(deftest json-safe-collections
  (is (= [1 2 "a"] (sut/json-safe [1 2 'a])))
  (is (= #{"a" "b"} (sut/json-safe #{'a 'b})))
  (is (= [1 2] (sut/json-safe (list 1 2)))))

(deftest json-safe-nested-map
  (is (= {:outer {:inner "sym"}}
         (sut/json-safe {:outer {:inner 'sym}}))))

(deftest json-safe-drops-nil-keys
  (testing "nil keys in maps are removed, not serialized"
    (is (= {:a 1} (sut/json-safe {:a 1 nil 2})))))

(deftest json-safe-output-is-json-writable
  (testing "nothing throws when writing to JSON"
    (let [v {:vals [(->ExampleRec 1 2)
                    #'clojure.core/inc
                    String
                    inc
                    {nil :dropped :kept 1}
                    {:skeptic.analysis.types/semantic-type :ground
                     :skeptic.analysis.types/kind :int
                     :skeptic.analysis.types/name 'Int}]}
          safe (sut/json-safe v)]
      (is (string? (json/write-str safe))))))

(deftest json-safe-sorted-map-survives
  (testing "sorted-maps with non-keyword keys don't trip the serializer"
    (let [m (sorted-map 1 :a 2 :b)
          out (sut/json-safe m)]
      (is (= :a (get out 1)))
      (is (= :b (get out 2)))
      (is (string? (json/write-str out))))))
