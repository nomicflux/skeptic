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
       (schematize/resolve-all {})
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

(deftest analyse-if-test
  (let [analysed (expand-and-annotate '(if (even? 2) true "hello")
                                      sut/analyse-if)]
    (is (= [{:expr '({:expr even?, :idx 2} {:expr 2, :idx 3}),
             :idx 4,
             :local-vars {}}
            {:expr true, :idx 5, :local-vars {}}
            {:expr "hello", :idx 6, :local-vars {}}
            {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr even?, :idx 2} {:expr 2, :idx 3}), :idx 4}
               {:expr true, :idx 5}
               {:expr "hello", :idx 6}),
             :idx 7,
             :schema #:skeptic.analysis{:placeholders [5 6]}}]
           (clean-callbacks analysed)))
    (is (= '((even? 2) true "hello" (if (even? 2) true "hello"))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(if (pos? x) 1 -1) sut/analyse-if)]
    (is (= [{:expr '({:expr pos?, :idx 2} {:expr x, :idx 3}),
             :idx 4,
             :local-vars {}}
            {:expr 1, :idx 5, :local-vars {}}
            {:expr -1, :idx 6, :local-vars {}}
            {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr pos?, :idx 2} {:expr x, :idx 3}), :idx 4}
               {:expr 1, :idx 5}
               {:expr -1, :idx 6}),
             :idx 7,
             :schema #:skeptic.analysis{:placeholders [5 6]}}]
           (clean-callbacks analysed)))
    (is (= '((pos? x) 1 -1 (if (pos? x) 1 -1))
           (unannotate-expr analysed)))))

(deftest analyse-fn-test
  (let [analysed (expand-and-annotate '(fn [x] x) sut/analyse-fn)]
    (is (= [{:expr 'x, :idx 4, :local-vars {}}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr ({:expr [{:expr x, :idx 2}], :idx 3} {:expr x, :idx 4}),
                :idx 5}),
             :idx 6,
             :schema #:skeptic.analysis{:arglists
                                        {1
                                         {:arglist ['x],
                                          :count 1,
                                          :schema
                                          [{:schema s/Any, :optional? false, :name 'x}]}}}
             :output #:skeptic.analysis{:placeholders [4]},
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema
               [{:schema s/Any, :optional? false, :name 'x}]}}}]
           (clean-callbacks analysed)))
    (is (= '(x (fn* ([x] x)))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(fn [x y] (str y x) (+ x y)) sut/analyse-fn)]
    (is (= [{:expr '({:expr str, :idx 5} {:expr y, :idx 6} {:expr x, :idx 7}),
             :idx 8,
             :local-vars {}}
            {:expr '({:expr +, :idx 9} {:expr x, :idx 10} {:expr y, :idx 11}),
             :idx 12,
             :local-vars {}}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr
                ({:expr [{:expr x, :idx 2} {:expr y, :idx 3}], :idx 4}
                 {:expr ({:expr str, :idx 5} {:expr y, :idx 6} {:expr x, :idx 7}),
                  :idx 8}
                 {:expr ({:expr +, :idx 9} {:expr x, :idx 10} {:expr y, :idx 11}),
                  :idx 12}),
                :idx 13}),
             :idx 14,
             :schema #:skeptic.analysis{:arglists
                                        {2
                                         {:arglist ['x 'y],
                                          :count 2,
                                          :schema
                                          [{:schema s/Any, :optional? false, :name 'x}
                                           {:schema s/Any, :optional? false, :name 'y}]}}}
             :output #:skeptic.analysis{:placeholders [12]},
             :arglists
             {2
              {:arglist ['x 'y],
               :count 2,
               :schema
               [{:schema s/Any, :optional? false, :name 'x}
                {:schema s/Any, :optional? false, :name 'y}]}}}]
           (clean-callbacks analysed)))
    (is (= '((str y x) (+ x y) (fn* ([x y] (str y x) (+ x y))))
           (unannotate-expr analysed))))

  (let [analysed (expand-and-annotate '(fn* ([x] (+ x 1)) ([x y] (+ x y))) sut/analyse-fn)]
    (is (= [{:expr '({:expr +, :idx 4} {:expr x, :idx 5} {:expr 1, :idx 6}),
             :idx 7,
             :local-vars {}}
            {:expr '({:expr +, :idx 12} {:expr x, :idx 13} {:expr y, :idx 14}),
             :idx 15,
             :local-vars {}}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr
                ({:expr [{:expr x, :idx 2}], :idx 3}
                 {:expr ({:expr +, :idx 4} {:expr x, :idx 5} {:expr 1, :idx 6}),
                  :idx 7}),
                :idx 8}
               {:expr
                ({:expr ({:expr x, :idx 9} {:expr y, :idx 10}), :idx 11}
                 {:expr ({:expr +, :idx 12} {:expr x, :idx 13} {:expr y, :idx 14}),
                  :idx 15}),
                :idx 16}),
             :idx 17,
             :schema #:skeptic.analysis{:arglists
                                        {1
                                         {:arglist ['x],
                                          :count 1,
                                          :schema
                                          [{:schema s/Any, :optional? false, :name 'x}]},
                                         2
                                         {:arglist ['x 'y],
                                          :count 2,
                                          :schema
                                          [{:schema s/Any, :optional? false, :name 'x}
                                           {:schema s/Any, :optional? false, :name 'y}]}}}
             :output #:skeptic.analysis{:placeholders [7 15]},
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema [{:schema s/Any, :optional? false, :name 'x}]},
              2
              {:arglist ['x 'y],
               :count 2,
               :schema
               [{:schema s/Any, :optional? false, :name 'x}
                {:schema s/Any, :optional? false, :name 'y}]}}}]
           (clean-callbacks analysed)))
    (is (= '((+ x 1) (+ x y) (fn* ([x] (+ x 1)) ([x y] (+ x y))))
           (unannotate-expr analysed)))))

(deftest analyse-def-test
  (let [analysed (expand-and-annotate '(def n 5) sut/analyse-def)]
    (is (= [{:expr 5, :idx 3, :local-vars {}}
            {:expr '({:expr def, :idx 1} {:expr n, :idx 2} {:expr 5, :idx 3}),
             :idx 4,
             :name 'n,
             :schema #:skeptic.analysis{:placeholder 3}}]
           (clean-callbacks analysed)))
    (is (= '(5 (def n 5))
           (unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(defn f [x] (println "something") (+ 1 x)) sut/analyse-def)]
    (is (= [{:expr
             '({:expr fn*, :idx 3}
               {:expr
                ({:expr [{:expr x, :idx 4}], :idx 5}
                 {:expr ({:expr println, :idx 6} {:expr "something", :idx 7}),
                  :idx 8}
                 {:expr ({:expr +, :idx 9} {:expr 1, :idx 10} {:expr x, :idx 11}),
                  :idx 12}),
                :idx 13}),
             :idx 14,
             :local-vars {}}
            {:expr
             '({:expr def, :idx 1}
               {:expr f, :idx 2}
               {:expr
                ({:expr fn*, :idx 3}
                 {:expr
                  ({:expr [{:expr x, :idx 4}], :idx 5}
                   {:expr ({:expr println, :idx 6} {:expr "something", :idx 7}),
                    :idx 8}
                   {:expr
                    ({:expr +, :idx 9} {:expr 1, :idx 10} {:expr x, :idx 11}),
                    :idx 12}),
                  :idx 13}),
                :idx 14}),
             :idx 15,
             :name 'f,
             :schema #:skeptic.analysis{:placeholder 14}}]
           (clean-callbacks analysed)))
    (is (= '((fn* ([x] (println "something") (+ 1 x)))
             (def f (fn* ([x] (println "something") (+ 1 x)))))
           (unannotate-expr analysed)))))

(deftest analyse-application-test
  (let [analysed (expand-and-annotate '(+ 1 x) sut/analyse-application)]
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

(deftest attach-schema-info-value-test
  (is (= {1 {:expr 1, :idx 1, :schema s/Int}}
         (sut/attach-schema-info-loop {} '1))))

(deftest attach-schema-info-coll-test
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
              '({:expr [{:expr :a, :idx 2} {:expr 2, :idx 3}], :idx 4}
                {:expr
                 ({:expr :b, :idx 5}
                  {:expr
                   [{:expr
                     [{:expr :c, :idx 6}
                      {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                     :idx 10}],
                   :idx 11,
                   :map? true}),
                 :idx 12}),
              :idx 13,
              :map? true,
              :schema {s/Keyword (s/either s/Int {s/Keyword #{s/Int}})},
              :finished? true},

          14 {:expr 5, :idx 14, :schema s/Int},

          15 {:expr
              '({:expr 1, :idx 1}
                {:expr
                 ({:expr [{:expr :a, :idx 2} {:expr 2, :idx 3}], :idx 4}
                  {:expr
                   [{:expr :b, :idx 5}
                    {:expr
                     [{:expr
                       [{:expr :c, :idx 6}
                        {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                       :idx 10}],
                     :idx 11,
                     :map? true}],
                   :idx 12}),
                 :idx 13,
                 :map? true}
                {:expr 5, :idx 14}),
              :idx 15,
              :schema [(s/either {s/Keyword (s/either s/Int {s/Keyword #{s/Int}})} s/Int)],
              :finished? true}}
         (sut/attach-schema-info-loop {} '[1 {:a 2 :b {:c #{3 4}}} 5]))))

(deftest attach-schema-info-application-test
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
              (sut/attach-schema-info-loop test-examples/sample-dict )))))

(deftest attach-schema-info-let-test
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

(deftest attach-schema-info-if-test
  (is (= {2 {:expr 'even?,
             :idx 2,
             :arity 1,
             :fn-position? true,
             :schema (s/=> s/Any [(s/one s/Any 'anon-arg)]),
             :output s/Any,
             :finished? true},
          3 {:expr 2, :idx 3, :local-vars {}, :schema s/Int},
          4 {:expr '({:expr even?, :idx 2} {:expr 2, :idx 3}),
             :idx 4,
             :local-vars {},
             :schema s/Any,
             :finished? true},
          5 {:expr true, :idx 5, :local-vars {}, :schema s/Bool},
          6 {:expr "hello", :idx 6, :local-vars {}, :schema s/Str},
          7 {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr even?, :idx 2} {:expr 2, :idx 3}), :idx 4}
               {:expr true, :idx 5}
               {:expr "hello", :idx 6}),
             :idx 7,
             :schema (s/either s/Str s/Bool),
             :finished? true}}
         (->> '(if (even? 2) true "hello")
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict ))))
  (is (= {2 {:expr 'pos?,
             :idx 2,
             :arity 1,
             :fn-position? true,
             :schema (s/=> s/Any [(s/one s/Any 'anon-arg)]),
             :output s/Any,
             :finished? true},
          3 {:expr 'x, :idx 3, :local-vars {}},
          4 {:expr '({:expr pos?, :idx 2} {:expr x, :idx 3}),
             :idx 4,
             :local-vars {},
             :schema s/Any,
             :finished? true},
          5 {:expr 1, :idx 5, :local-vars {}, :schema s/Int},
          6 {:expr -1, :idx 6, :local-vars {}, :schema s/Int},
          7 {:expr '({:expr if, :idx 1}
                     {:expr ({:expr pos?, :idx 2} {:expr x, :idx 3}), :idx 4}
                     {:expr 1, :idx 5}
                     {:expr -1, :idx 6}),
             :idx 7,
             :schema s/Int,
             :finished? true}}
         (->> '(if (pos? x) 1 -1)
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict )))))

(deftest attach-schema-info-fn-test
  (is (= {4 {:expr 'skeptic.test-examples/int-add,
             :idx 4,
             :arity 2,
             :fn-position? true,
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          5 {:expr 1, :idx 5, :local-vars {}, :schema s/Int},
          6 {:expr 2, :idx 6, :local-vars {}, :schema s/Int},
          7 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 4}
               {:expr 1, :idx 5}
               {:expr 2, :idx 6}),
             :idx 7,
             :local-vars {},
             :schema s/Int,
             :finished? true},
          9 {:expr
             '({:expr fn*, :idx 1}
               {:expr
                ({:expr [{:expr x, :idx 2}], :idx 3}
                 {:expr
                  ({:expr skeptic.test-examples/int-add, :idx 4}
                   {:expr 1, :idx 5}
                   {:expr 2, :idx 6}),
                  :idx 7}),
                :idx 8}),
             :idx 9,
             :schema (s/make-fn-schema s/Int [[(s/one s/Any 'x)]])
             :output s/Int,
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema [{:schema s/Any, :optional? false, :name 'x}]}},
             :finished? true}}
         (->> '(fn [x] (skeptic.test-examples/int-add 1 2))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)))))

(deftest attach-schema-info-def-test
  (is (= {3 {:expr 5, :idx 3, :local-vars {}, :schema s/Int},
          4 {:expr '({:expr def, :idx 1} {:expr n, :idx 2} {:expr 5, :idx 3}),
             :idx 4,
             :name 'n,
             :schema (sut/variable s/Int),
             :finished? true}}
         (->> '(def n 5)
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {7 {:expr 'println,
             :idx 7,
             :arity 1,
             :fn-position? true,
             :schema (s/=> s/Any [(s/one s/Any 'anon-arg)]),
             :output s/Any,
             :finished? true},
          8 {:expr "something", :idx 8, :local-vars {}, :schema java.lang.String},
          9 {:expr '({:expr println, :idx 7} {:expr "something", :idx 8}),
             :idx 9,
             :local-vars {},
             :schema s/Any,
             :finished? true},
          10 {:expr 'skeptic.test-examples/int-add,
              :idx 10,
              :arity 2,
              :fn-position? true,
              :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
              :output s/Int,
              :arglist [s/Int s/Int],
              :finished? true},
          11 {:expr 'x, :idx 11, :local-vars {}},
          12 {:expr 'y, :idx 12, :local-vars {}},
          13 {:expr '({:expr skeptic.test-examples/int-add, :idx 10} {:expr x, :idx 11} {:expr y, :idx 12}),
              :idx 13,
              :local-vars {},
              :schema s/Int,
              :finished? true},
          15 {:expr
              '({:expr fn*, :idx 3}
                {:expr
                 ({:expr [{:expr x, :idx 4} {:expr y, :idx 5}], :idx 6}
                  {:expr ({:expr println, :idx 7} {:expr "something", :idx 8}),
                   :idx 9}
                  {:expr ({:expr skeptic.test-examples/int-add, :idx 10} {:expr x, :idx 11} {:expr y, :idx 12}),
                   :idx 13}),
                 :idx 14}),
              :idx 15,
              :local-vars {},
              :schema (s/make-fn-schema s/Int [[(s/one s/Any 'x) (s/one s/Any 'y)]])
              :output s/Int,
              :arglists
              {2
               {:arglist ['x 'y],
                :count 2,
                :schema
                [{:schema s/Any, :optional? false, :name 'x}
                 {:schema s/Any, :optional? false, :name 'y}]}},
              :finished? true},
          16 {:expr
              '({:expr def, :idx 1}
                {:expr f, :idx 2}
                {:expr
                 ({:expr fn*, :idx 3}
                  {:expr
                   ({:expr [{:expr x, :idx 4} {:expr y, :idx 5}], :idx 6}
                    {:expr ({:expr println, :idx 7} {:expr "something", :idx 8}),
                     :idx 9}
                    {:expr
                     ({:expr skeptic.test-examples/int-add, :idx 10} {:expr x, :idx 11} {:expr y, :idx 12}),
                     :idx 13}),
                   :idx 14}),
                 :idx 15}),
              :idx 16,
              :name 'f,
              :schema (sut/variable (s/make-fn-schema s/Int [[(s/one s/Any 'x) (s/one s/Any 'y)]])),
              :finished? true}}
         (->> '(defn f [x y] (println "something") (skeptic.test-examples/int-add x y))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (into (sorted-map))))))
