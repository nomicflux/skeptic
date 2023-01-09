(ns skeptic.analysis-test
  (:require [skeptic.analysis :as sut]
            [clojure.test :refer [deftest is are]]
            [clojure.walk :as walk]
            [skeptic.schematize :as schematize]
            [schema.core :as s]
            [skeptic.test-examples :as test-examples]))

(defn expand-and-annotate
  [expr f]
  (->> expr
       schematize/macroexpand-all
       sut/annotate-expr
       f))

(defn clean-callbacks
  [expr]
  (walk/postwalk #(if (map? %) (dissoc % :dep-callback) %) expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk #(if (and (map? %) (contains? % :expr)) (:expr %) %) expr))

(deftest analyse-let-test
  (let [analysed (expand-and-annotate '(let [x 1] (+ 1 x)) sut/analyse-let)]
    (is (= [{:expr 1, :idx 3, :local-vars {}, :name 'x}
            {:expr '({:expr +, :idx 5} {:expr 1, :idx 6} {:expr x, :idx 7}),
             :idx 8
             :local-vars {'x #::sut{:placeholder 3}}},
            {:expr
             '({:expr let*, :idx 1}
               {:expr [{:expr x, :idx 2} {:expr 1, :idx 3}], :idx 4}
               {:expr [{:expr +, :idx 5} {:expr 1, :idx 6} {:expr x, :idx 7}],
                :idx 8}),
             :idx 9
             :schema #:skeptic.analysis{:placeholder 8}}]
           (clean-callbacks analysed)))
    (is (= '(1 (+ 1 x) (let* [x 1] (+ 1 x)))
           (unannotate-expr analysed))))

  (let [analysed (expand-and-annotate '(let [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y))
                                      sut/analyse-let)]
    (is (= [{:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
             :idx 6,
             :local-vars {},
             :name 'x}
            {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
             :idx 11,
             :local-vars {'x #::sut{:placeholder 6}}
             :name 'y}
            {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
             :idx 16,
             :local-vars {'x #::sut{:placeholder 6}
                          'y #::sut{:placeholder 11}}}
            {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
             :idx 20,
             :local-vars {'x #::sut{:placeholder 6}
                          'y #::sut{:placeholder 11}}}
            {:expr
             [{:expr 'let*, :idx 1}
              {:expr
               [{:expr 'x, :idx 2}
                {:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
                 :idx 6}
                {:expr 'y, :idx 7}
                {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
                 :idx 11}],
               :idx 12}
              {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
               :idx 16}
              {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
               :idx 20}],
             :idx 21
             :schema #:skeptic.analysis{:placeholder 20}}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2)
             (+ 3 x)
             (+ 7 x)
             (+ x y)
             (let* [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y)))
           (unannotate-expr analysed)))))

(deftest analyse-application-test
  (let [analysed (expand-and-annotate '(+ 1 x)
                                      sut/analyse-application)]
    (is (= [{:expr 1, :idx 2, :local-vars {}}
            {:expr 'x, :idx 3, :local-vars {}}
            {:expr '+, :idx 1, :arity 2, :fn-position? true}
            {:expr [{:expr '+, :idx 1} {:expr 1, :idx 2} {:expr 'x, :idx 3},]
             :idx 4,
             :schema #:skeptic.analysis{:placeholder 1}}]
           (clean-callbacks analysed)))
    (is (= '(1 x + (+ 1 x))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(f)
                                      sut/analyse-application)]
    (is (= [{:expr 'f, :idx 1, :arity 0, :fn-position? true}
            {:expr [{:expr 'f, :idx 1}],
             :idx 2,
             :schema #:skeptic.analysis{:placeholder 1}}]
           (clean-callbacks analysed)))
    (is (= '(f (f))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '((f 1) 3 4)
                                      sut/analyse-application)]
    (is (= [{:expr 3, :idx 4, :local-vars {}}
            {:expr 4, :idx 5, :local-vars {}}
            {:expr [{:expr 'f, :idx 1} {:expr 1, :idx 2}],
             :idx 3,
             :arity 2,
             :fn-position? true}
            {:expr
             [{:expr [{:expr 'f, :idx 1} {:expr 1, :idx 2}], :idx 3}
              {:expr 3, :idx 4}
              {:expr 4, :idx 5}],
             :idx 6,
             :schema #:skeptic.analysis{:placeholder 3}}]
           (clean-callbacks analysed)))
    (is (= '(3 4 (f 1) ((f 1) 3 4))
           (unannotate-expr analysed)))))

(deftest analyse-coll-test
  (let [analysed (expand-and-annotate '[1 2 :a "hello"]
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 1}
            {:expr 2, :idx 2}
            {:expr :a, :idx 3}
            {:expr "hello", :idx 4}
            {:expr
             [{:expr 1, :idx 1}
              {:expr 2, :idx 2}
              {:expr :a, :idx 3}
              {:expr "hello", :idx 4}],
             :idx 5,
             :schema #:skeptic.analysis{:placeholders '(1 2 3 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 :a "hello" '(1 2 :a "hello")]
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '#{1 2 :a "hello"}
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 2}
            {:expr 2, :idx 3}
            {:expr "hello", :idx 1}
            {:expr :a, :idx 4}
            {:expr
             #{{:expr 1, :idx 2}
               {:expr 2, :idx 3}
               {:expr "hello", :idx 1}
               {:expr :a, :idx 4}},
             :idx 5,
             :schema #:skeptic.analysis{:placeholders '(2 3 1 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 "hello" :a #{1 2 :a "hello"}]
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(1 2 :a "hello")
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 1}
            {:expr 2, :idx 2}
            {:expr :a, :idx 3}
            {:expr "hello", :idx 4}
            {:expr
             [{:expr 1, :idx 1}
              {:expr 2, :idx 2}
              {:expr :a, :idx 3}
              {:expr "hello", :idx 4}],
             :idx 5,
             :schema #:skeptic.analysis{:placeholders '(1 2 3 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 :a "hello" '(1 2 :a "hello")]
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '{:a 1 :b 2 :c 3}
                                      sut/analyse-coll)]
    (is (= [{:expr :a, :idx 1}
            {:expr 1, :idx 2}
            {:expr :b, :idx 4}
            {:expr 2, :idx 5}
            {:expr :c, :idx 7}
            {:expr 3, :idx 8}
            {:expr
             [{:expr [{:expr :a, :idx 1} {:expr 1, :idx 2}], :idx 3}
              {:expr [{:expr :b, :idx 4} {:expr 2, :idx 5}], :idx 6}
              {:expr [{:expr :c, :idx 7} {:expr 3, :idx 8}], :idx 9}],
             :idx 10,
             :map? true,
             :schema
             #:skeptic.analysis{:key-placeholders [1 4 7,]
                                :val-placeholders [2 5 8]}}]
           (clean-callbacks analysed)))
    (is (= '(:a 1 :b 2 :c 3 ([:a 1] [:b 2] [:c 3]))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '[1 2 [3 4 [5]]]
                                      sut/analyse-coll)]
    (is (= '({:expr 1, :idx 1}
             {:expr 2, :idx 2}
             {:expr
              [{:expr 3, :idx 3}
               {:expr 4, :idx 4}
               {:expr [{:expr 5, :idx 5}], :idx 6}],
              :idx 7}
             {:expr
              [{:expr 1, :idx 1}
               {:expr 2, :idx 2}
               {:expr
                [{:expr 3, :idx 3}
                 {:expr 4, :idx 4}
                 {:expr [{:expr 5, :idx 5}], :idx 6}],
                :idx 7}],
              :idx 8,
              :schema #:skeptic.analysis{:placeholders (1 2 7)}})
           (clean-callbacks analysed)))
    (is (= '(1 2 [3 4 [5]] [1 2 [3 4 [5]]])
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '{:a 1 :b [:z "hello" #{1 2}] :c {:d 7 :e {:f 9}}}
                                      sut/analyse-coll)]
    (is (= '({:expr :a, :idx 1}
             {:expr 1, :idx 2}
             {:expr :b, :idx 4}
             {:expr
              [{:expr :z, :idx 5}
               {:expr "hello", :idx 6}
               {:expr #{{:expr 2, :idx 8} {:expr 1, :idx 7}}, :idx 9}],
              :idx 10}
             {:expr :c, :idx 12}
             {:expr
              ({:expr [{:expr :d, :idx 13} {:expr 7, :idx 14}], :idx 15}
               {:expr
                [{:expr :e, :idx 16}
                 {:expr
                  ({:expr [{:expr :f, :idx 17} {:expr 9, :idx 18}], :idx 19}),
                  :idx 20,
                  :map? true}],
                :idx 21}),
              :idx 22,
              :map? true}
             {:expr
              ({:expr [{:expr :a, :idx 1} {:expr 1, :idx 2}], :idx 3}
               {:expr
                [{:expr :b, :idx 4}
                 {:expr
                  [{:expr :z, :idx 5}
                   {:expr "hello", :idx 6}
                   {:expr #{{:expr 2, :idx 8} {:expr 1, :idx 7}}, :idx 9}],
                  :idx 10}],
                :idx 11}
               {:expr
                [{:expr :c, :idx 12}
                 {:expr
                  ({:expr [{:expr :d, :idx 13} {:expr 7, :idx 14}], :idx 15}
                   {:expr
                    [{:expr :e, :idx 16}
                     {:expr
                      ({:expr [{:expr :f, :idx 17} {:expr 9, :idx 18}], :idx 19}),
                      :idx 20,
                      :map? true}],
                    :idx 21}),
                  :idx 22,
                  :map? true}],
                :idx 23}),
              :idx 24,
              :map? true,
              :schema
              #:skeptic.analysis{:key-placeholders (1 4 12),
                                 :val-placeholders (2 10 22)}})
           (clean-callbacks analysed)))
    (is (= '(:a
             1
             :b
             [:z "hello" #{1 2}]
             :c
             ([:d 7] [:e ([:f 9])])
             ([:a 1] [:b [:z "hello" #{1 2}]] [:c ([:d 7] [:e ([:f 9])])]))
           (unannotate-expr analysed)))))

(deftest attach-schema-info-test
  (is (= {1 {:expr 1, :idx 1, :schema s/Int}}
         (sut/attach-schema-info-loop {} '1)))
  (is (= {1 {:expr 1, :idx 1, :schema s/Int},
          2 {:expr 2, :idx 2, :schema s/Int},
          3
          {:expr [{:expr 1, :idx 1} {:expr 2, :idx 2}],
           :idx 3,
           :schema [s/Int],
           :finished? true}}
         (sut/attach-schema-info-loop {} '[1 2])))
  (is (= {1 {:expr 1, :idx 1, :schema s/Int},
          2 {:expr :a, :idx 2, :schema s/Keyword},
          3 {:expr 2, :idx 3, :schema s/Int},
          5 {:expr :b, :idx 5, :schema s/Keyword},
          6 {:expr :c, :idx 6, :schema s/Keyword},
          7 {:expr 4, :idx 7, :schema s/Int},
          8 {:expr 3, :idx 8, :schema s/Int}

          9 {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}},
             :idx 9,
             :schema #{s/Int},
             :finished? true},

          11 {:expr
              [{:expr
                [{:expr :c, :idx 6}
                 {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                :idx 10}],
              :idx 11,
              :map? true,
              :schema
              {s/Keyword #{s/Int}},
              :finished? true},

          13 {:expr
              [{:expr [{:expr :a, :idx 2} {:expr 2, :idx 3}], :idx 4}
               {:expr
                [{:expr :b, :idx 5}
                 {:expr
                  [{:expr
                    [{:expr :c, :idx 6}
                     {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                    :idx 10}],
                  :idx 11,
                  :map? true}],
                :idx 12}],
              :idx 13,
              :map? true,
              :schema {s/Keyword s/Int},
              :finished? true},

          14 {:expr 5, :idx 14, :schema s/Int},

          15 {:expr
              [{:expr 1, :idx 1}
               {:expr
                [{:expr [{:expr :a, :idx 2} {:expr 2, :idx 3}], :idx 4}
                 {:expr
                  [{:expr :b, :idx 5}
                   {:expr
                    [{:expr
                      [{:expr :c, :idx 6}
                       {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                      :idx 10}],
                    :idx 11,
                    :map? true}],
                  :idx 12}],
                :idx 13,
                :map? true}
               {:expr 5, :idx 14}],
              :idx 15,
              :schema [s/Int],
              :finished? true}}
         (sut/attach-schema-info-loop {} '[1 {:a 2 :b {:c #{3 4}}} 5])))

  (is (= {1 {:expr '+,
             :idx 1,
             :arity 2,
             :fn-position? true,
             :schema (s/=> s/Any [(s/one s/Any 'anon-arg) (s/one s/Any 'anon-arg)]),
             :output s/Any,
             :finished? true},
          2 {:expr 1, :idx 2, :local-vars {}, :schema s/Int},
          3 {:expr 2, :idx 3, :local-vars {}, :schema s/Int},
          4 {:expr [{:expr '+, :idx 1} {:expr 1, :idx 2} {:expr 2, :idx 3}],
             :idx 4,
             :schema s/Any,
             :finished? true}}
         (sut/attach-schema-info-loop {} '(+ 1 2))))

  (is (= {1 {:expr 'skeptic.test-examples/int-add,
             :idx 1,
             :arity 2,
             :fn-position? true,
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          2 {:expr 1, :idx 2, :local-vars {}, :schema s/Int},
          3 {:expr 2, :idx 3, :local-vars {}, :schema s/Int},
          4 {:expr [{:expr 'skeptic.test-examples/int-add, :idx 1} {:expr 1, :idx 2} {:expr 2, :idx 3}],
             :idx 4,
             :schema s/Int,
             :finished? true}}
         (->> '(skeptic.test-examples/int-add 1 2)
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict ))))

  (is (= {3 {:expr 'skeptic.test-examples/int-add,
             :idx 3,
             :arity 2,
             :fn-position? true,
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          4 {:expr 1, :idx 4, :local-vars {}, :schema s/Int},
          5 {:expr 2, :idx 5, :local-vars {}, :schema s/Int},
          6 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 3}
               {:expr 1, :idx 4}
               {:expr 2, :idx 5}),
             :idx 6,
             :local-vars {},
             :name 'x,
             :schema s/Int,
             :finished? true},
          8 {:expr 'skeptic.test-examples/int-add,
             :idx 8,
             :arity 2
             :fn-position? true
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]])
             :output s/Int
             :arglist [s/Int s/Int]
             :finished? true}
          9 {:expr 'x, :idx 9, :local-vars {'x #:skeptic.analysis{:placeholder 6}}, :schema s/Int},
          10 {:expr 2, :idx 10, :local-vars {'x #:skeptic.analysis{:placeholder 6}}, :schema s/Int},
          11 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 8}
                {:expr x, :idx 9}
                {:expr 2, :idx 10}),
              :local-vars {'x #:skeptic.analysis{:placeholder 6}}
              :schema s/Int
              :finished? true
              :idx 11}
          12 {:expr
              '({:expr let*, :idx 1}
                {:expr
                 [{:expr x, :idx 2}
                  {:expr
                   ({:expr skeptic.test-examples/int-add, :idx 3}
                    {:expr 1, :idx 4}
                    {:expr 2, :idx 5}),
                   :idx 6}],
                 :idx 7}
                {:expr
                 ({:expr skeptic.test-examples/int-add, :idx 8}
                  {:expr x, :idx 9}
                  {:expr 2, :idx 10}),
                 :idx 11}),
              :idx 12,
              :schema s/Int,
              :finished? true}}
         (->> '(let [x (skeptic.test-examples/int-add 1 2)]
                 (skeptic.test-examples/int-add x 2))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict )))))
