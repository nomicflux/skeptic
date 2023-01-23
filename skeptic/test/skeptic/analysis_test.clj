(ns skeptic.analysis-test
  (:require [skeptic.analysis :as sut]
            [clojure.test :refer [deftest is are]]
            [clojure.walk :as walk]
            [skeptic.schematize :as schematize]
            [schema.core :as s]
            [skeptic.test-examples :as test-examples]
            [skeptic.analysis.resolvers :as analysis-resolvers]
            [skeptic.analysis.schema :as analysis-schema]
            [plumbing.core :as p]
            [clojure.string :as str]))

(defn expand-and-annotate
  [expr f]
  (->> expr
       (schematize/resolve-all {})
       sut/annotate-expr
       f))

(defn clean-callbacks
  [expr]
  (walk/postwalk #(if (map? %) (dissoc % :dep-callback) %) expr))

(defn update-when
  [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

(defn clean-gen-var
  [var-name expr]
  (try (->> expr
            (p/map-vals #(update-when % :name (fn [k] (if (and (symbol? k) (str/starts-with? (name k) (str (name var-name) "__"))) var-name k))))
            (p/map-vals #(update-when % :path (fn [p]
                                                     (mapv
                                                      (fn [k] (if (and (symbol? k) (str/starts-with? (name k) (str (name var-name) "__"))) var-name k))
                                                      p))))
            (p/map-vals #(update-when % :expr (fn [ex]
                                                     (walk/postwalk
                                                      (fn [k] (if (and (symbol? k) (str/starts-with? (name k) (str (name var-name) "__"))) var-name k))
                                                      ex))))
            (p/map-vals #(update-when % :local-vars (fn [lv]
                                                           (p/map-keys
                                                            (fn [k] (if (and (symbol? k) (str/starts-with? (name k) (str (name var-name) "__"))) var-name k))
                                                            (clean-gen-var var-name lv))))))
       (catch Exception e
         (println "Exception for " var-name " & " (pr-str expr))
         (throw e))))

(defn unannotate-results
  [exprv]
  (p/map-vals #(update % :expr sut/unannotate-expr) exprv))

(deftest analyse-throw-test
  (let [analysed (expand-and-annotate '(throw (UnsupportedOperationException. "oops, not done yet")) sut/analyse-throw)]
    (is (= {:expr
            '({:expr throw, :idx 1}
              {:expr
               ({:expr new, :idx 2}
                {:expr UnsupportedOperationException, :idx 3}
                {:expr "oops, not done yet", :idx 4}),
               :idx 5}),
            :idx 6,
            :schema analysis-schema/Bottom}
           (clean-callbacks analysed)))
    (is (= '(throw (new UnsupportedOperationException "oops, not done yet"))
           (sut/unannotate-expr analysed)))))

(deftest analyse-try-test
  (let [analysed (expand-and-annotate '(try (+ 1 2) (catch UnsupportedOperationException e (println "doesn't work")))
                                      sut/analyse-try)]
    (is (= [{:expr '({:expr +, :idx 2} {:expr 1, :idx 3} {:expr 2, :idx 4}),
             :idx 5,
             :path []
             :local-vars {}}
            {:expr '({:expr println, :idx 9} {:expr "doesn't work", :idx 10}),
             :idx 11,
             :path []
             :local-vars {}}
            {:expr
             '({:expr try, :idx 1}
               {:expr ({:expr +, :idx 2} {:expr 1, :idx 3} {:expr 2, :idx 4}),
                :idx 5}
               {:expr
                ({:expr catch, :idx 6}
                 {:expr UnsupportedOperationException, :idx 7}
                 {:expr e, :idx 8}
                 {:expr ({:expr println, :idx 9} {:expr "doesn't work", :idx 10}),
                  :idx 11}),
                :idx 12}),
             :idx 13,
             :schema {::analysis-resolvers/placeholder 5}}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2)
             (println "doesn't work")
             (try
               (+ 1 2)
               (catch UnsupportedOperationException e (println "doesn't work"))))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(try (str 3) (+ 1 2)
                                            (catch UnsupportedOperationException e
                                              (println "doesn't work")
                                              (println "still doesn't"))
                                            (finally (println "got something")
                                                     (+ 7 8)))
                                      sut/analyse-try)]
    (is (= [{:expr '({:expr str, :idx 2} {:expr 3, :idx 3}),
             :idx 4,
             :path []
             :local-vars {}}
            {:expr '({:expr +, :idx 5} {:expr 1, :idx 6} {:expr 2, :idx 7}),
             :idx 8,
             :path []
             :local-vars {}}
            {:expr '({:expr println, :idx 12} {:expr "doesn't work", :idx 13}),
             :idx 14,
             :path []
             :local-vars {}}
            {:expr '({:expr println, :idx 15} {:expr "still doesn't", :idx 16}),
             :idx 17,
             :path []
             :local-vars {}}
            {:expr '({:expr println, :idx 20} {:expr "got something", :idx 21}),
             :idx 22,
             :path []
             :local-vars {}}
            {:expr '({:expr +, :idx 23} {:expr 7, :idx 24} {:expr 8, :idx 25}),
             :idx 26,
             :path []
             :local-vars {}}
            {:expr
             '({:expr try, :idx 1}
               {:expr ({:expr str, :idx 2} {:expr 3, :idx 3}), :idx 4}
               {:expr ({:expr +, :idx 5} {:expr 1, :idx 6} {:expr 2, :idx 7}),
                :idx 8}
               {:expr
                ({:expr catch, :idx 9}
                 {:expr UnsupportedOperationException, :idx 10}
                 {:expr e, :idx 11}
                 {:expr ({:expr println, :idx 12} {:expr "doesn't work", :idx 13}),
                  :idx 14}
                 {:expr
                  ({:expr println, :idx 15} {:expr "still doesn't", :idx 16}),
                  :idx 17}),
                :idx 18}
               {:expr
                ({:expr finally, :idx 19}
                 {:expr
                  ({:expr println, :idx 20} {:expr "got something", :idx 21}),
                  :idx 22}
                 {:expr ({:expr +, :idx 23} {:expr 7, :idx 24} {:expr 8, :idx 25}),
                  :idx 26}),
                :idx 27}),
             :idx 28,
             :schema {::analysis-resolvers/placeholder 8}}]
           (clean-callbacks analysed)))
    (is (= '((str 3)
             (+ 1 2)
             (println "doesn't work")
             (println "still doesn't")
             (println "got something")
             (+ 7 8)
             (try
               (str 3)
               (+ 1 2)
               (catch
                   UnsupportedOperationException
                   e
                 (println "doesn't work")
                 (println "still doesn't"))
               (finally (println "got something") (+ 7 8))))
           (sut/unannotate-expr analysed)))))

(deftest analyse-let-test
  (let [analysed (expand-and-annotate '(let [] (+ 1 2)) sut/analyse-let)]
    (is (= [{:expr '({:expr +, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}),
             :idx 6,
             :local-vars {},
             :path []}
            {:expr
             '({:expr let*, :idx 1}
               {:expr [], :idx 2}
               {:expr ({:expr +, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}),
                :idx 6}),
             :idx 7,
             :schema #:skeptic.analysis.resolvers{:placeholder 6}}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2) (let* [] (+ 1 2)))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(let [x 1] (+ 1 x)) sut/analyse-let)]
    (is (= [{:expr 1, :idx 3, :local-vars {}, :name 'x, :path []}
            {:expr '({:expr +, :idx 5} {:expr 1, :idx 6} {:expr x, :idx 7}),
             :path []
             :idx 8
             :local-vars {'x {::analysis-resolvers/placeholder 3}}},
            {:expr
             '({:expr let*, :idx 1}
               {:expr [{:expr x, :idx 2} {:expr 1, :idx 3}], :idx 4}
               {:expr [{:expr +, :idx 5} {:expr 1, :idx 6} {:expr x, :idx 7}],
                :idx 8}),
             :idx 9
             :schema {::analysis-resolvers/placeholder 8}}]
           (clean-callbacks analysed)))
    (is (= '(1 (+ 1 x) (let* [x 1] (+ 1 x)))
           (sut/unannotate-expr analysed))))

  (let [analysed (expand-and-annotate '(let [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y))
                                      sut/analyse-let)]
    (is (= [{:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
             :idx 6,
             :path []
             :local-vars {},
             :name 'x}
            {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
             :idx 11,
             :path []
             :local-vars {'x {::analysis-resolvers/placeholder 6}}
             :name 'y}
            {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
             :idx 16,
             :path []
             :local-vars {'x {::analysis-resolvers/placeholder 6}
                          'y {::analysis-resolvers/placeholder 11}}}
            {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
             :idx 20,
             :path []
             :local-vars {'x {::analysis-resolvers/placeholder 6}
                          'y {::analysis-resolvers/placeholder 11}}}
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
             :schema {::analysis-resolvers/placeholder 20}}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2)
             (+ 3 x)
             (+ 7 x)
             (+ x y)
             (let* [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y)))
           (sut/unannotate-expr analysed)))))

(deftest analyse-if-test
  (let [analysed (expand-and-annotate '(if (even? 2) true "hello")
                                      sut/analyse-if)]
    (is (= [{:expr '({:expr even?, :idx 2} {:expr 2, :idx 3}),
             :idx 4,
             :path []
             :local-vars {}}
            {:expr true, :idx 5, :local-vars {}, :path []}
            {:expr "hello", :idx 6, :local-vars {}, :path []}
            {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr even?, :idx 2} {:expr 2, :idx 3}), :idx 4}
               {:expr true, :idx 5}
               {:expr "hello", :idx 6}),
             :idx 7,
             :schema {::analysis-resolvers/placeholders [5 6]}}]
           (clean-callbacks analysed)))
    (is (= '((even? 2) true "hello" (if (even? 2) true "hello"))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(if (pos? x) 1 -1) sut/analyse-if)]
    (is (= [{:expr '({:expr pos?, :idx 2} {:expr x, :idx 3}),
             :idx 4,
             :path []
             :local-vars {}}
            {:expr 1, :idx 5, :local-vars {}, :path []}
            {:expr -1, :idx 6, :local-vars {}, :path []}
            {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr pos?, :idx 2} {:expr x, :idx 3}), :idx 4}
               {:expr 1, :idx 5}
               {:expr -1, :idx 6}),
             :idx 7,
             :schema {::analysis-resolvers/placeholders [5 6]}}]
           (clean-callbacks analysed)))
    (is (= '((pos? x) 1 -1 (if (pos? x) 1 -1))
           (sut/unannotate-expr analysed)))))

(deftest analyse-fn-test
  (let [analysed (expand-and-annotate '(fn [x] x) sut/analyse-fn)]
    (is (= [{:expr 'x, :idx 4, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}, :path []}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr ({:expr [{:expr x, :idx 2}], :idx 3} {:expr x, :idx 4}),
                :idx 5}),
             :idx 6,
             :schema {::analysis-resolvers/arglists
                      {1
                       {:arglist ['x],
                        :count 1,
                        :schema
                        [{:schema s/Any, :optional? false, :name 'x}]}}}
             :output {::analysis-resolvers/placeholders [4]},
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema
               [{:schema s/Any, :optional? false, :name 'x}]}}}]
           (clean-callbacks analysed)))
    (is (= '(x (fn* ([x] x)))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(fn [x y] (str y x) (+ x y)) sut/analyse-fn)]
    (is (= [{:expr '({:expr str, :idx 5} {:expr y, :idx 6} {:expr x, :idx 7}),
             :idx 8,
             :path []
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                          'y {:expr 'y, :name 'y, :schema s/Any}}}
            {:expr '({:expr +, :idx 9} {:expr x, :idx 10} {:expr y, :idx 11}),
             :idx 12,
             :path []
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                          'y {:expr 'y, :name 'y, :schema s/Any}}}
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
             :schema {::analysis-resolvers/arglists
                      {2
                       {:arglist ['x 'y],
                        :count 2,
                        :schema
                        [{:schema s/Any, :optional? false, :name 'x}
                         {:schema s/Any, :optional? false, :name 'y}]}}}
             :output {::analysis-resolvers/placeholders [12]},
             :arglists
             {2
              {:arglist ['x 'y],
               :count 2,
               :schema
               [{:schema s/Any, :optional? false, :name 'x}
                {:schema s/Any, :optional? false, :name 'y}]}}}]
           (clean-callbacks analysed)))
    (is (= '((str y x) (+ x y) (fn* ([x y] (str y x) (+ x y))))
           (sut/unannotate-expr analysed))))

  (let [analysed (expand-and-annotate '(fn* ([x] (+ x 1)) ([x y] (+ x y))) sut/analyse-fn)]
    (is (= [{:expr '({:expr +, :idx 4} {:expr x, :idx 5} {:expr 1, :idx 6}),
             :idx 7,
             :path [],
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}}
            {:expr '({:expr +, :idx 12} {:expr x, :idx 13} {:expr y, :idx 14}),
             :idx 15,
             :path [],
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                          'y {:expr 'y, :name 'y, :schema s/Any}}}
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
             :schema {::analysis-resolvers/arglists
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
             :output {::analysis-resolvers/placeholders [7 15]},
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
           (sut/unannotate-expr analysed)))))

(deftest analyse-fn-once-test
  (let [analysed (expand-and-annotate '(fn* [x] (str "hello") (+ 1 x)) sut/analyse-fn-once)]
    (is (= [{:expr '({:expr str, :idx 4} {:expr "hello", :idx 5}),
             :idx 6,
             :path []
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}}
            {:expr '({:expr +, :idx 7} {:expr 1, :idx 8} {:expr x, :idx 9}),
             :idx 10,
             :path []
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr [{:expr x, :idx 2}], :idx 3}
               {:expr ({:expr str, :idx 4} {:expr "hello", :idx 5}), :idx 6}
               {:expr ({:expr +, :idx 7} {:expr 1, :idx 8} {:expr x, :idx 9}),
                :idx 10}),
             :idx 11,
             :output {::analysis-resolvers/placeholder 10},
             :schema
             {::analysis-resolvers/arglist
              {1
               {:arglist ['x],
                :count 1,
                :schema
                [{:schema s/Any, :optional? false, :name 'x}]}}},
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema [{:schema s/Any, :optional? false, :name 'x}]}}}]
           (clean-callbacks analysed)))
    (is (= '((str "hello") (+ 1 x) (fn* [x] (str "hello") (+ 1 x)))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(^{:once true} fn* [y] (int-add y nil)) sut/analyse-fn-once)]
    (is (= [{:expr
             '({:expr int-add, :idx 4} {:expr y, :idx 5} {:expr nil, :idx 6}),
             :idx 7,
             :path []
             :local-vars {'y {:expr 'y, :name 'y, :schema s/Any}}}
            {:expr
             '({:expr fn*, :idx 1}
               {:expr [{:expr y, :idx 2}], :idx 3}
               {:expr
                ({:expr int-add, :idx 4} {:expr y, :idx 5} {:expr nil, :idx 6}),
                :idx 7}),
             :idx 8,
             :output {::analysis-resolvers/placeholder 7},
             :schema
             {::analysis-resolvers/arglist
              {1
               {:arglist ['y],
                :count 1,
                :schema
                [{:schema s/Any, :optional? false, :name 'y}]}}},
             :arglists
             {1
              {:arglist ['y],
               :count 1,
               :schema [{:schema s/Any, :optional? false, :name 'y}]}}}]
           (clean-callbacks analysed)))
    (is (= '((int-add y nil) (fn* [y] (int-add y nil)))
           (sut/unannotate-expr analysed)))))

(deftest analyse-def-test
  (let [analysed (expand-and-annotate '(def n 5) sut/analyse-def)]
    (is (= [{:expr 5, :idx 3, :local-vars {}, :path ['n]}
            {:expr '({:expr def, :idx 1} {:expr n, :idx 2} {:expr 5, :idx 3}),
             :idx 4,
             :name 'n,
             :path ['n]
             :schema {::analysis-resolvers/placeholder 3}}]
           (clean-callbacks analysed)))
    (is (= '(5 (def n 5))
           (sut/unannotate-expr analysed))))
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
             :path ['f]
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
             :path ['f]
             :schema {::analysis-resolvers/placeholder 14}}]
           (clean-callbacks analysed)))
    (is (= '((fn* ([x] (println "something") (+ 1 x)))
             (def f (fn* ([x] (println "something") (+ 1 x)))))
           (sut/unannotate-expr analysed)))))

(deftest analyse-do-test
  (let [analysed (expand-and-annotate '(do (str "hello") (+ 1 2)) sut/analyse-do)]
    (is (= [{:expr '({:expr str, :idx 2} {:expr "hello", :idx 3}),
             :idx 4,
             :path []
             :local-vars {}}
            {:expr '({:expr +, :idx 5} {:expr 1, :idx 6} {:expr 2, :idx 7}),
             :idx 8,
             :path []
             :local-vars {}}
            {:expr
             '({:expr do, :idx 1}
               {:expr ({:expr str, :idx 2} {:expr "hello", :idx 3}), :idx 4}
               {:expr ({:expr +, :idx 5} {:expr 1, :idx 6} {:expr 2, :idx 7}),
                :idx 8}),
             :idx 9,
             :schema {::analysis-resolvers/placeholder 8}}]
           (clean-callbacks analysed)))
    (is (= '((str "hello") (+ 1 2) (do (str "hello") (+ 1 2)))
           (sut/unannotate-expr analysed)))))

(deftest analyse-application-test
  (let [analysed (expand-and-annotate '(+ 1 x) sut/analyse-application)]
    (is (= [{:expr 1, :idx 2, :local-vars {}, :path []}
            {:expr 'x, :idx 3, :local-vars {}, :path []}
            {:expr '+, :idx 1, :args [2 3], :local-vars {}, :fn-position? true, :path []}
            {:expr [{:expr '+, :idx 1} {:expr 1, :idx 2} {:expr 'x, :idx 3}],
             :idx 4,
             :actual-arglist {::analysis-resolvers/placeholders [2 3]},
             :expected-arglist {::analysis-resolvers/placeholder 1},
             :schema {::analysis-resolvers/placeholder 1}}]
           (clean-callbacks analysed)))
    (is (= '(1 x + (+ 1 x))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(f)
                                      sut/analyse-application)]
    (is (= [{:expr 'f, :idx 1, :args [], :local-vars {}, :fn-position? true, :path []}
            {:expr [{:expr 'f, :idx 1}],
             :idx 2,
             :actual-arglist {::analysis-resolvers/placeholders []},
             :expected-arglist {::analysis-resolvers/placeholder 1},
             :schema {::analysis-resolvers/placeholder 1}}]
           (clean-callbacks analysed)))
    (is (= '(f (f))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '((f 1) 3 4)
                                      sut/analyse-application)]
    (is (= [{:expr 3, :idx 4, :local-vars {}, :path []}
            {:expr 4, :idx 5, :local-vars {}, :path []}
            {:expr [{:expr 'f, :idx 1} {:expr 1, :idx 2}],
             :idx 3,
             :path []
             :args [4 5],
             :local-vars {}
             :fn-position? true}
            {:expr
             [{:expr [{:expr 'f, :idx 1} {:expr 1, :idx 2}], :idx 3}
              {:expr 3, :idx 4}
              {:expr 4, :idx 5}],
             :idx 6,
             :actual-arglist {::analysis-resolvers/placeholders [4 5]},
             :expected-arglist {::analysis-resolvers/placeholder 3},
             :schema {::analysis-resolvers/placeholder 3}}]
           (clean-callbacks analysed)))
    (is (= '(3 4 (f 1) ((f 1) 3 4))
           (sut/unannotate-expr analysed)))))

(deftest analyse-coll-test
  (let [analysed (expand-and-annotate '[1 2 :a "hello"]
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 1, :path [], :local-vars {}}
            {:expr 2, :idx 2, :path [], :local-vars {}}
            {:expr :a, :idx 3, :path [], :local-vars {}}
            {:expr "hello", :idx 4, :path [], :local-vars {}}
            {:expr
             [{:expr 1, :idx 1}
              {:expr 2, :idx 2}
              {:expr :a, :idx 3}
              {:expr "hello", :idx 4}],
             :idx 5,
             :schema {::analysis-resolvers/placeholders '(1 2 3 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 :a "hello" '(1 2 :a "hello")]
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '#{1 2 :a "hello"}
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 2, :path [], :local-vars {}}
            {:expr 2, :idx 3, :path [], :local-vars {}}
            {:expr "hello", :idx 1, :path [], :local-vars {}}
            {:expr :a, :idx 4, :path [], :local-vars {}}
            {:expr
             #{{:expr 1, :idx 2}
               {:expr 2, :idx 3}
               {:expr "hello", :idx 1}
               {:expr :a, :idx 4}},
             :idx 5,
             :schema {::analysis-resolvers/placeholders '(2 3 1 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 "hello" :a #{1 2 :a "hello"}]
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '(1 2 :a "hello")
                                      sut/analyse-coll)]
    (is (= [{:expr 1, :idx 1, :path [], :local-vars {}}
            {:expr 2, :idx 2, :path [], :local-vars {}}
            {:expr :a, :idx 3, :path [], :local-vars {}}
            {:expr "hello", :idx 4, :path [], :local-vars {}}
            {:expr
             [{:expr 1, :idx 1}
              {:expr 2, :idx 2}
              {:expr :a, :idx 3}
              {:expr "hello", :idx 4}],
             :idx 5,
             :schema {::analysis-resolvers/placeholders '(1 2 3 4)}}]
           (clean-callbacks analysed)))
    (is (= [1 2 :a "hello" '(1 2 :a "hello")]
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '{:a 1 :b 2 :c 3}
                                      sut/analyse-coll)]
    (is (= [{:expr :a, :idx 1, :path [], :local-vars {}}
            {:expr 1, :idx 2, :path [], :local-vars {}}
            {:expr :b, :idx 4, :path [], :local-vars {}}
            {:expr 2, :idx 5, :path [], :local-vars {}}
            {:expr :c, :idx 7, :path [], :local-vars {}}
            {:expr 3, :idx 8, :path [], :local-vars {}}
            {:expr
             [{:expr [{:expr :a, :idx 1} {:expr 1, :idx 2}], :idx 3}
              {:expr [{:expr :b, :idx 4} {:expr 2, :idx 5}], :idx 6}
              {:expr [{:expr :c, :idx 7} {:expr 3, :idx 8}], :idx 9}],
             :idx 10,
             :map? true,
             :schema
             {::analysis-resolvers/key-placeholders [1 4 7],
              ::analysis-resolvers/val-placeholders [2 5 8]}}]
           (clean-callbacks analysed)))
    (is (= '(:a 1 :b 2 :c 3 ([:a 1] [:b 2] [:c 3]))
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '[1 2 [3 4 [5]]]
                                      sut/analyse-coll)]
    (is (= '({:expr 1, :idx 1, :path [], :local-vars {}}
             {:expr 2, :idx 2, :path [], :local-vars {}}
             {:expr
              [{:expr 3, :idx 3}
               {:expr 4, :idx 4}
               {:expr [{:expr 5, :idx 5}], :idx 6}],
              :idx 7, :path [], :local-vars {}}
             {:expr
              [{:expr 1, :idx 1}
               {:expr 2, :idx 2}
               {:expr
                [{:expr 3, :idx 3}
                 {:expr 4, :idx 4}
                 {:expr [{:expr 5, :idx 5}], :idx 6}],
                :idx 7}],
              :idx 8,
              :schema {::analysis-resolvers/placeholders (1 2 7)}})
           (clean-callbacks analysed)))
    (is (= '(1 2 [3 4 [5]] [1 2 [3 4 [5]]])
           (sut/unannotate-expr analysed))))
  (let [analysed (expand-and-annotate '{:a 1 :b [:z "hello" #{1 2}] :c {:d 7 :e {:f 9}}}
                                      sut/analyse-coll)]
    (is (= '({:expr :a, :idx 1, :local-vars {}, :path []}
             {:expr 1, :idx 2, :local-vars {}, :path []}
             {:expr :b, :idx 4, :local-vars {}, :path []}
             {:expr
              [{:expr :z, :idx 5}
               {:expr "hello", :idx 6}
               {:expr #{{:expr 2, :idx 8} {:expr 1, :idx 7}}, :idx 9}],
              :idx 10, :local-vars {}, :path []}
             {:expr :c, :idx 12, :local-vars {}, :path []}
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
              :local-vars {},
              :path [],
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
                  :idx 22
                  :map? true}],
                :idx 23}),
              :idx 24,
              :map? true,
              :schema
              {::analysis-resolvers/key-placeholders (1 4 12),
               ::analysis-resolvers/val-placeholders (2 10 22)}})
           (clean-callbacks analysed)))
    (is (= '(:a
             1
             :b
             [:z "hello" #{1 2}]
             :c
             ([:d 7] [:e ([:f 9])])
             ([:a 1] [:b [:z "hello" #{1 2}]] [:c ([:d 7] [:e ([:f 9])])]))
           (sut/unannotate-expr analysed)))))

(deftest attach-schema-info-value-test
  (is (= {1 {:expr 1, :idx 1, :schema s/Int}}
         (sut/attach-schema-info-loop {} '1))))

(deftest attach-schema-info-coll-test
  (is (= {1 {:expr '(), :idx 1, :schema [s/Any], :finished? true}}
         (sut/attach-schema-info-loop {} '())))
  (is (= {1 {:expr 1, :idx 1, :schema s/Int, :local-vars {}, :path []},
          2 {:expr 2, :idx 2, :schema s/Int, :local-vars {}, :path []},
          3 {:expr [{:expr 1, :idx 1} {:expr 2, :idx 2}],
             :idx 3,
             :schema [s/Int],
             :finished? true}}
         (sut/attach-schema-info-loop {} '[1 2])))
  (is (= {1 {:expr 1, :idx 1, :schema s/Int, :local-vars {}, :path []},
          2 {:expr :a, :idx 2, :schema s/Keyword, :local-vars {}, :path []},
          3 {:expr 2, :idx 3, :schema s/Int, :local-vars {}, :path []},
          5 {:expr :b, :idx 5, :schema s/Keyword, :local-vars {}, :path []},
          6 {:expr :c, :idx 6, :schema s/Keyword, :local-vars {}, :path []},
          7 {:expr 4, :idx 7, :schema s/Int, :local-vars {}, :path []},
          8 {:expr 3, :idx 8, :schema s/Int, :local-vars {}, :path []}

          9 {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}},
             :idx 9,
             :schema #{s/Int},
             :local-vars {},
             :path [],
             :finished? true},

          11 {:expr
              [{:expr
                [{:expr :c, :idx 6}
                 {:expr #{{:expr 4, :idx 7} {:expr 3, :idx 8}}, :idx 9}],
                :idx 10}],
              :idx 11,
              :local-vars {},
              :path [],
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
              :local-vars {},
              :path [],
              :map? true,
              :schema {s/Keyword (analysis-schema/join {s/Keyword #{s/Int}} s/Int)},
              :finished? true},

          14 {:expr 5, :idx 14, :schema s/Int, :local-vars {}, :path []},

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
              :schema [(analysis-schema/join s/Int {s/Keyword (analysis-schema/join {s/Keyword #{s/Int}} s/Int)})],
              :finished? true}}
         (sut/attach-schema-info-loop {} '[1 {:a 2 :b {:c #{3 4}}} 5]))))

(deftest attach-schema-info-application-test
  (is (= {1 {:expr '+,
             :idx 1,
             :args [2 3],
             :local-vars {},
             :path [],
             :fn-position? true,
             :arglist [s/Any s/Any]
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg) (s/one s/Any 'anon-arg)]]),
             :output s/Any,
             :finished? true},
          2 {:expr 1, :idx 2, :local-vars {}, :path [], :schema s/Int},
          3 {:expr 2, :idx 3, :local-vars {}, :path [], :schema s/Int},
          4 {:expr [{:expr '+, :idx 1} {:expr 1, :idx 2} {:expr 2, :idx 3}],
             :idx 4,
             :schema s/Any,
             :actual-arglist [s/Int s/Int]
             :expected-arglist [s/Any s/Any]
             :finished? true}}
         (sut/attach-schema-info-loop {} '(+ 1 2))))

  (is (= {1 {:expr 'skeptic.test-examples/int-add,
             :idx 1,
             :args [2 3],
             :fn-position? true,
             :path [],
             :local-vars {},
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          2 {:expr 1, :idx 2, :local-vars {}, :path [], :schema s/Int},
          3 {:expr 2, :idx 3, :local-vars {}, :path [], :schema s/Int},
          4 {:expr [{:expr 'skeptic.test-examples/int-add, :idx 1} {:expr 1, :idx 2} {:expr 2, :idx 3}],
             :idx 4,
             :schema s/Int,
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
             :finished? true}}
         (->> '(skeptic.test-examples/int-add 1 2)
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)))))

(deftest attach-schema-info-let-test
  (is (= {3 {:args [4 5],
             :path [],
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :local-vars {},
             :arglist [s/Int s/Int],
             :output s/Int,
             :expr 'skeptic.test-examples/int-add,
             :finished? true,
             :fn-position? true,
             :idx 3},
          4 {:expr 1, :idx 4, :local-vars {}, :path [], :schema s/Int},
          5 {:expr 2, :idx 5, :local-vars {}, :path [], :schema s/Int},
          6 {:expr '({:expr skeptic.test-examples/int-add, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}),
             :idx 6,
             :local-vars {},
             :path [],
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
             :schema s/Int,
             :finished? true},
          7 {:expr
             '({:expr let*, :idx 1}
               {:expr [], :idx 2}
               {:expr ({:expr skeptic.test-examples/int-add, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}),
                :idx 6}),
             :idx 7,
             :schema s/Int,
             :finished? true}}
         (->> '(let [] (skeptic.test-examples/int-add 1 2))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (into (sorted-map)))))
  (is (= {3 {:expr 'skeptic.test-examples/int-add,
             :idx 3,
             :args [4 5],
             :fn-position? true,
             :local-vars {},
             :path ['x],
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          4 {:expr 1, :idx 4, :local-vars {}, :path ['x], :schema s/Int},
          5 {:expr 2, :idx 5, :local-vars {}, :path ['x], :schema s/Int},
          6 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 3}
               {:expr 1, :idx 4}
               {:expr 2, :idx 5}),
             :idx 6,
             :local-vars {},
             :path [],
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
             :name 'x,
             :schema s/Int,
             :finished? true},
          8 {:expr 'skeptic.test-examples/int-add,
             :idx 8,
             :args [9 10],
             :path [],
             :fn-position? true,
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :local-vars {'x {:schema s/Int
                              :resolution-path [{:idx 6}]}},
             :output s/Int
             :arglist [s/Int s/Int]
             :finished? true}
          9 {:expr 'x, :idx 9, :local-vars {'x {:schema s/Int :resolution-path [{:idx 6}]}}, :path [], :schema s/Int},
          10 {:expr 2, :idx 10, :local-vars {'x {:schema s/Int :resolution-path [{:idx 6}]}}, :path [], :schema s/Int},
          11 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 8}
                {:expr x, :idx 9}
                {:expr 2, :idx 10}),
              :local-vars {'x {:schema s/Int :resolution-path [{:idx 6}]}}
              :schema s/Int
              :path [],
              :actual-arglist [s/Int s/Int],
              :expected-arglist [s/Int s/Int],
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
             :args [3],
             :fn-position? true,
             :local-vars {}
             :path []
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :arglist [s/Any]
             :output s/Any,
             :finished? true},
          3 {:expr 2, :idx 3, :local-vars {}, :path [], :schema s/Int},
          4 {:expr '({:expr even?, :idx 2} {:expr 2, :idx 3}),
             :idx 4,
             :local-vars {},
             :path []
             :actual-arglist [s/Int],
             :expected-arglist [s/Any],
             :schema s/Any,
             :finished? true},
          5 {:expr true, :idx 5, :local-vars {}, :path [], :schema s/Bool},
          6 {:expr "hello", :idx 6, :local-vars {}, :path [], :schema s/Str},
          7 {:expr
             '({:expr if, :idx 1}
               {:expr ({:expr even?, :idx 2} {:expr 2, :idx 3}), :idx 4}
               {:expr true, :idx 5}
               {:expr "hello", :idx 6}),
             :idx 7,
             :schema (analysis-schema/join s/Str s/Bool),
             :finished? true}}
         (->> '(if (even? 2) true "hello")
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict ))))
  (is (= {2 {:expr 'pos?,
             :idx 2,
             :args [3],
             :fn-position? true,
             :local-vars {},
             :path [],
             :arglist [s/Any]
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :output s/Any,
             :finished? true},
          3 {:expr 'x, :idx 3, :schema s/Any, :path [], :local-vars {}},
          4 {:expr '({:expr pos?, :idx 2} {:expr x, :idx 3}),
             :idx 4,
             :local-vars {},
             :path [],
             :schema s/Any,
             :actual-arglist [s/Any],
             :expected-arglist [s/Any],
             :finished? true},
          5 {:expr 1, :idx 5, :local-vars {}, :path [], :schema s/Int},
          6 {:expr -1, :idx 6, :local-vars {}, :path [], :schema s/Int},
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
             :args [5 6],
             :fn-position? true,
             :path []
             :local-vars {'x {:expr 'x :name 'x :schema s/Any}},
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          5 {:expr 1, :idx 5, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}, :path [], :schema s/Int},
          6 {:expr 2, :idx 6, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}, :path [], :schema s/Int},
          7 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 4}
               {:expr 1, :idx 5}
               {:expr 2, :idx 6}),
             :idx 7,
             :path []
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
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
  (is (= {3 {:expr 5, :idx 3, :local-vars {}, :path ['n] :schema s/Int},
          4 {:expr '({:expr def, :idx 1} {:expr n, :idx 2} {:expr 5, :idx 3}),
             :idx 4,
             :name 'n,
             :path ['n]
             :schema (analysis-schema/variable s/Int),
             :finished? true}}
         (->> '(def n 5)
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {7 {:expr 'println,
             :idx 7,
             :args [8],
             :path ['f]
             :fn-position? true,
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                          'y {:expr 'y, :name 'y, :schema s/Any}},
             :arglist [s/Any]
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :output s/Any,
             :finished? true},
          8 {:expr "something", :idx 8, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                                                     'y {:expr 'y, :name 'y, :schema s/Any}},
             :path ['f]
             :schema java.lang.String},
          9 {:expr '({:expr println, :idx 7} {:expr "something", :idx 8}),
             :idx 9,
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                          'y {:expr 'y, :name 'y, :schema s/Any}},
             :schema s/Any,
             :path ['f]
             :actual-arglist [s/Str]
             :expected-arglist [s/Any]
             :finished? true},
          10 {:expr 'skeptic.test-examples/int-add,
              :idx 10,
              :args [11 12],
              :fn-position? true,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                           'y {:expr 'y, :name 'y, :schema s/Any}},
              :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
              :path ['f]
              :output s/Int,
              :arglist [s/Int s/Int],
              :finished? true},
          11 {:expr 'x, :idx 11, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                                              'y {:expr 'y, :name 'y, :schema s/Any}}
              :path ['f]
              :schema s/Any},
          12 {:expr 'y, :idx 12, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                                              'y {:expr 'y, :name 'y, :schema s/Any}}
              :path ['f]
              :schema s/Any},
          13 {:expr '({:expr skeptic.test-examples/int-add, :idx 10} {:expr x, :idx 11} {:expr y, :idx 12}),
              :idx 13,
              :actual-arglist [s/Any s/Any]
              :expected-arglist [s/Int s/Int]
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}
                           'y {:expr 'y, :name 'y, :schema s/Any}},
              :path ['f]
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
              :path ['f]
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
              :path ['f]
              :schema (analysis-schema/variable (s/make-fn-schema s/Int [[(s/one s/Any 'x) (s/one s/Any 'y)]])),
              :finished? true}}
         (->> '(defn f [x y] (println "something") (skeptic.test-examples/int-add x y))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)))))

(deftest attach-schema-info-do-test
  (is (= {2 {:expr 'println,
             :idx 2,
             :args [3],
             :local-vars {}
             :path []
             :arglist [s/Any]
             :fn-position? true,
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :output s/Any,
             :finished? true},
          3 {:expr "something", :idx 3, :local-vars {}, :path [], :schema java.lang.String},
          4 {:expr '({:expr println, :idx 2} {:expr "something", :idx 3}),
             :idx 4,
             :local-vars {},
             :path []
             :schema s/Any,
             :actual-arglist [s/Str]
             :expected-arglist [s/Any]
             :finished? true},
          5 {:expr 'skeptic.test-examples/int-add,
             :idx 5,
             :args [6 7],
             :fn-position? true,
             :local-vars {}
             :path []
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          6 {:expr 'x, :idx 6, :schema s/Any, :path [], :local-vars {}},
          7 {:expr 'y, :idx 7, :schema s/Any, :path [], :local-vars {}},
          8 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 5}
               {:expr x, :idx 6}
               {:expr y, :idx 7}),
             :idx 8,
             :actual-arglist [s/Any s/Any],
             :expected-arglist [s/Int s/Int],
             :local-vars {},
             :path []
             :schema s/Int,
             :finished? true},
          9 {:expr
             '({:expr do, :idx 1}
               {:expr ({:expr println, :idx 2} {:expr "something", :idx 3}),
                :idx 4}
               {:expr
                ({:expr skeptic.test-examples/int-add, :idx 5}
                 {:expr x, :idx 6}
                 {:expr y, :idx 7}),
                :idx 8}),
             :idx 9,
             :schema s/Int,
             :finished? true}}
         (->> '(do (println "something") (skeptic.test-examples/int-add x y))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)))))

(deftest attach-schema-info-try-throw-test
  (is (= {2 {:expr 'skeptic.test-examples/int-add,
             :idx 2,
             :args [3 4],
             :fn-position? true,
             :local-vars {}
             :path []
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          3 {:expr 1, :idx 3, :local-vars {}, :path [], :schema s/Int},
          4 {:expr 2, :idx 4, :local-vars {}, :path [], :schema s/Int},
          5 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 2}
               {:expr 1, :idx 3}
               {:expr 2, :idx 4}),
             :idx 5,
             :local-vars {},
             :path []
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
             :schema s/Int,
             :finished? true},
          11 {:expr '({:expr throw, :idx 9} {:expr e, :idx 10}),
              :idx 11,
              :local-vars {},
              :path []
              :schema analysis-schema/Bottom},
          13 {:expr
              '({:expr try, :idx 1}
                {:expr
                 ({:expr skeptic.test-examples/int-add, :idx 2}
                  {:expr 1, :idx 3}
                  {:expr 2, :idx 4}),
                 :idx 5}
                {:expr
                 ({:expr catch, :idx 6}
                  {:expr UnsupportedOperationException, :idx 7}
                  {:expr e, :idx 8}
                  {:expr ({:expr throw, :idx 9} {:expr e, :idx 10}), :idx 11}),
                 :idx 12}),
              :idx 13,
              :schema s/Int,
              :finished? true}}
         (->> '(try (skeptic.test-examples/int-add 1 2) (catch UnsupportedOperationException e (throw e)))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {2 {:expr 'clojure.core/str,
             :idx 2,
             :args [3],
             :fn-position? true,
             :local-vars {}
             :path []
             :schema (s/make-fn-schema s/Str [[(s/one s/Any 's)]]),
             :output java.lang.String,
             :arglist [s/Any],
             :finished? true},
          3 {:expr "hello", :idx 3, :local-vars {}, :path [], :schema java.lang.String},
          4 {:expr '({:expr clojure.core/str, :idx 2} {:expr "hello", :idx 3}),
             :idx 4,
             :local-vars {},
             :path []
             :schema java.lang.String,
             :actual-arglist [s/Str],
             :expected-arglist [s/Any],
             :finished? true},
          5 {:expr 'skeptic.test-examples/int-add,
             :idx 5,
             :args [6 7],
             :local-vars {},
             :path []
             :fn-position? true,
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :arglist [s/Int s/Int],
             :finished? true},
          6 {:expr 1, :idx 6, :local-vars {}, :path [], :schema s/Int},
          7 {:expr 2, :idx 7, :local-vars {}, :path [], :schema s/Int},
          8 {:expr
             '({:expr skeptic.test-examples/int-add, :idx 5}
               {:expr 1, :idx 6}
               {:expr 2, :idx 7}),
             :idx 8,
             :local-vars {},
             :path []
             :actual-arglist [s/Int s/Int],
             :expected-arglist [s/Int s/Int],
             :schema s/Int,
             :finished? true},
          12 {:expr 'println,
              :idx 12,
              :args [13],
              :local-vars {}
              :path []
              :fn-position? true,
              :arglist [s/Any],
              :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
              :output s/Any,
              :finished? true},
          13 {:expr "oops", :idx 13, :local-vars {}, :path [], :schema java.lang.String},
          14 {:expr '({:expr println, :idx 12} {:expr "oops", :idx 13}),
              :idx 14,
              :local-vars {},
              :path []
              :schema s/Any,
              :actual-arglist [s/Str],
              :expected-arglist [s/Any],
              :finished? true},
          17 {:expr '({:expr throw, :idx 15} {:expr e, :idx 16}),
              :idx 17,
              :local-vars {},
              :path []
              :schema analysis-schema/Bottom},
          20 {:expr 'skeptic.test-examples/int-add,
              :idx 20,
              :args [21 22],
              :fn-position? true,
              :local-vars {}
              :path []
              :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
              :output s/Int,
              :arglist [s/Int s/Int],
              :finished? true},
          21 {:expr 3, :idx 21, :local-vars {}, :path [], :schema s/Int},
          22 {:expr 4, :idx 22, :local-vars {}, :path [], :schema s/Int},
          23 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 20}
                {:expr 3, :idx 21}
                {:expr 4, :idx 22}),
              :idx 23,
              :local-vars {},
              :path []
              :schema s/Int,
              :actual-arglist [s/Int s/Int],
              :expected-arglist [s/Int s/Int],
              :finished? true},
          24 {:expr 'clojure.core/str,
              :idx 24,
              :args [25],
              :fn-position? true,
              :local-vars {},
              :path []
              :schema (s/make-fn-schema s/Str [[(s/one s/Any 's)]]),
              :output java.lang.String,
              :arglist [s/Any],
              :finished? true},
          25 {:expr "world", :idx 25, :local-vars {}, :path [], :schema java.lang.String},
          26 {:expr '({:expr clojure.core/str, :idx 24} {:expr "world", :idx 25}),
              :idx 26,
              :local-vars {},
              :path []
              :schema java.lang.String,
              :actual-arglist [s/Str],
              :expected-arglist [s/Any],
              :finished? true},
          28 {:expr
              '({:expr try, :idx 1}
                {:expr ({:expr clojure.core/str, :idx 2} {:expr "hello", :idx 3}),
                 :idx 4}
                {:expr
                 ({:expr skeptic.test-examples/int-add, :idx 5}
                  {:expr 1, :idx 6}
                  {:expr 2, :idx 7}),
                 :idx 8}
                {:expr
                 ({:expr catch, :idx 9}
                  {:expr UnsupportedOperationException, :idx 10}
                  {:expr e, :idx 11}
                  {:expr ({:expr println, :idx 12} {:expr "oops", :idx 13}),
                   :idx 14}
                  {:expr ({:expr throw, :idx 15} {:expr e, :idx 16}), :idx 17}),
                 :idx 18}
                {:expr
                 ({:expr finally, :idx 19}
                  {:expr
                   ({:expr skeptic.test-examples/int-add, :idx 20}
                    {:expr 3, :idx 21}
                    {:expr 4, :idx 22}),
                   :idx 23}
                  {:expr
                   ({:expr clojure.core/str, :idx 24} {:expr "world", :idx 25}),
                   :idx 26}),
                 :idx 27}),
              :idx 28,
              :schema s/Int,
              :finished? true}}
         (->> '(try (clojure.core/str "hello") (skeptic.test-examples/int-add 1 2)
                    (catch UnsupportedOperationException e (println "oops") (throw e))
                    (finally (skeptic.test-examples/int-add 3 4) (clojure.core/str "world")))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)))))

(deftest attach-schema-info-misc-tests
  (is (= {6 {:expr 'skeptic.test-examples/int-add,
             :idx 6,
             :args [7 11],
             :fn-position? true,
             :path ['sample-bad-fn]
             :arglist [s/Int s/Int],
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :finished? true},
          7 {:expr 1,
             :idx 7,
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
             :path ['sample-bad-fn]
             :schema s/Int},
          8 {:expr 'skeptic.test-examples/int-add,
             :idx 8,
             :args [9 10],
             :fn-position? true,
             :arglist [s/Int s/Int],
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}
             :path ['sample-bad-fn]
             :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y) (s/one s/Int 'z)]]),
             :output s/Int,
             :finished? true},
          9 {:expr nil,
             :idx 9,
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
             :path ['sample-bad-fn]
             :schema (s/maybe s/Any)},
          10 {:expr 'x,
              :idx 10,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-bad-fn]
              :schema s/Any},
          11 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 8}
                {:expr nil, :idx 9}
                {:expr x, :idx 10}),
              :idx 11,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-bad-fn]
              :actual-arglist [(s/maybe s/Any) s/Any],
              :expected-arglist [s/Int s/Int],
              :schema s/Int,
              :finished? true},
          12 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 6}
                {:expr 1, :idx 7}
                {:expr
                 ({:expr skeptic.test-examples/int-add, :idx 8}
                  {:expr nil, :idx 9}
                  {:expr x, :idx 10}),
                 :idx 11}),
              :idx 12,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-bad-fn]
              :actual-arglist [s/Int s/Int],
              :expected-arglist [s/Int s/Int],
              :schema s/Int,
              :finished? true},
          14 {:expr
              '({:expr fn*, :idx 3}
                {:expr
                 ({:expr [{:expr x, :idx 4}], :idx 5}
                  {:expr
                   ({:expr skeptic.test-examples/int-add, :idx 6}
                    {:expr 1, :idx 7}
                    {:expr
                     ({:expr skeptic.test-examples/int-add, :idx 8}
                      {:expr nil, :idx 9}
                      {:expr x, :idx 10}),
                     :idx 11}),
                   :idx 12}),
                 :idx 13}),
              :idx 14,
              :local-vars {},
              :path ['sample-bad-fn]
              :output s/Int,
              :schema (s/make-fn-schema s/Int [[(s/one s/Any 'x)]]),
              :arglists
              {1
               {:arglist ['x],
                :count 1,
                :schema [{:schema s/Any, :optional? false, :name 'x}]}},
              :finished? true},
          15 {:expr
              '({:expr def, :idx 1}
                {:expr sample-bad-fn, :idx 2}
                {:expr
                 ({:expr fn*, :idx 3}
                  {:expr
                   ({:expr [{:expr x, :idx 4}], :idx 5}
                    {:expr
                     ({:expr skeptic.test-examples/int-add, :idx 6}
                      {:expr 1, :idx 7}
                      {:expr
                       ({:expr skeptic.test-examples/int-add, :idx 8}
                        {:expr nil, :idx 9}
                        {:expr x, :idx 10}),
                       :idx 11}),
                     :idx 12}),
                   :idx 13}),
                 :idx 14}),
              :idx 15,
              :name 'sample-bad-fn,
              :path ['sample-bad-fn]
              :schema (analysis-schema/variable (s/make-fn-schema s/Int [[(s/one s/Any 'x)]])),
              :finished? true}}
         (->> '(defn sample-bad-fn
                 [x]
                 (skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x)))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {6 {:expr nil,
             :idx 6,
             :path ['f]
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
             :schema (s/maybe s/Any)},
          8 {:expr
             '({:expr fn*, :idx 3}
               {:expr ({:expr [{:expr x, :idx 4}], :idx 5} {:expr nil, :idx 6}),
                :idx 7}),
             :idx 8,
             :local-vars {},
             :path []
             :name 'f,
             :output (s/maybe s/Any),
             :schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
             :arglists
             {1
              {:arglist ['x],
               :count 1,
               :schema [{:schema s/Any, :optional? false, :name 'x}]}},
             :finished? true},
          10 {:expr 'skeptic.test-examples/int-add,
              :idx 10,
              :args [11 14],
              :fn-position? true,
              :arglist [s/Int s/Int],
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}},
              :path []
              :schema (s/make-fn-schema s/Int [[(s/one s/Int 'y)  (s/one s/Int 'z)]]),
              :output s/Int,
              :finished? true},
          11 {:expr 1,
              :idx 11,
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}},
              :path []
              :schema s/Int},
          12 {:expr 'f,
              :idx 12,
              :args [13],
              :path []
              :fn-position? true,
              :arglist [s/Any],
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}},
              :schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
              :output (s/maybe s/Any),
              :finished? true},
          13 {:expr 'x,
              :idx 13,
              :path []
              :schema s/Any
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}}},
          14 {:expr '({:expr f, :idx 12} {:expr x, :idx 13}),
              :idx 14,
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}},
              :path []
              :actual-arglist [s/Any],
              :expected-arglist [s/Any],
              :schema (s/maybe s/Any),
              :finished? true},
          15 {:expr
              '({:expr skeptic.test-examples/int-add, :idx 10}
                {:expr 1, :idx 11}
                {:expr ({:expr f, :idx 12} {:expr x, :idx 13}), :idx 14}),
              :idx 15,
              :local-vars {'f
                           {:schema (s/make-fn-schema (s/maybe s/Any) [[(s/one s/Any 'x)]]),
                            :output (s/maybe s/Any),
                            :resolution-path [{:idx 8}]
                            :arglists
                            {1
                             {:arglist ['x],
                              :count 1,
                              :schema [{:schema s/Any, :optional? false, :name 'x}]}}}},
              :path []
              :actual-arglist [s/Int (s/maybe s/Any)],
              :expected-arglist [s/Int s/Int],
              :schema s/Int,
              :finished? true},
          16 {:expr
              '({:expr let*, :idx 1}
                {:expr
                 [{:expr f, :idx 2}
                  {:expr
                   ({:expr fn*, :idx 3}
                    {:expr
                     ({:expr [{:expr x, :idx 4}], :idx 5} {:expr nil, :idx 6}),
                     :idx 7}),
                   :idx 8}],
                 :idx 9}
                {:expr
                 ({:expr skeptic.test-examples/int-add, :idx 10}
                  {:expr 1, :idx 11}
                  {:expr ({:expr f, :idx 12} {:expr x, :idx 13}), :idx 14}),
                 :idx 15}),
              :idx 16,
              :schema s/Int,
              :finished? true}}
         (->> '(let [f (fn [x] nil)]
                 (skeptic.test-examples/int-add 1 (f x)))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {9 {:args [10 11],
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg) (s/one s/Any 'anon-arg)]]),
             :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                          'y {:expr 'y, :name 'y, :schema s/Any}},
             :path ['sample-fn-once]
             :arglist [s/Any s/Any],
             :output s/Any,
             :expr 'int-add,
             :finished? true,
             :fn-position? true,
             :idx 9},
          10 {:expr 'y,
              :idx 10,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                           'y {:expr 'y, :name 'y, :schema s/Any}},
              :path ['sample-fn-once]
              :schema s/Any},
          11 {:expr nil,
              :idx 11,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                           'y {:expr 'y, :name 'y, :schema s/Any}},
              :path ['sample-fn-once]
              :schema (s/maybe s/Any)},
          12 {:expr
              '({:expr int-add, :idx 9} {:expr y, :idx 10} {:expr nil, :idx 11}),
              :idx 12,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                           'y {:expr 'y, :name 'y, :schema s/Any}},
              :path ['sample-fn-once]
              :actual-arglist [s/Any (s/maybe s/Any),]
              :expected-arglist [s/Any s/Any],
              :schema s/Any,
              :finished? true},
          13 {:args [14],
              :schema (s/make-fn-schema s/Any [[(s/one s/Any 'y)]]),
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-fn-once]
              :arglist [s/Any],
              :output s/Any,
              :expr
              '({:expr fn*, :idx 6}
                {:expr [{:expr y, :idx 7}], :idx 8}
                {:expr
                 ({:expr int-add, :idx 9} {:expr y, :idx 10} {:expr nil, :idx 11}),
                 :idx 12}),
              :finished? true,
              :fn-position? true,
              :idx 13},
          14 {:expr 'x,
              :idx 14,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-fn-once]
              :schema s/Any},
          15 {:expr
              '({:expr
                 ({:expr fn*, :idx 6}
                  {:expr [{:expr y, :idx 7}], :idx 8}
                  {:expr
                   ({:expr int-add, :idx 9}
                    {:expr y, :idx 10}
                    {:expr nil, :idx 11}),
                   :idx 12}),
                 :idx 13}
                {:expr x, :idx 14}),
              :idx 15,
              :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
              :path ['sample-fn-once]
              :actual-arglist [s/Any],
              :expected-arglist [s/Any],
              :schema s/Any,
              :finished? true},
          17 {:expr
              '({:expr fn*, :idx 3}
                {:expr
                 ({:expr [{:expr x, :idx 4}], :idx 5}
                  {:expr
                   ({:expr
                     ({:expr fn*, :idx 6}
                      {:expr [{:expr y, :idx 7}], :idx 8}
                      {:expr
                       ({:expr int-add, :idx 9}
                        {:expr y, :idx 10}
                        {:expr nil, :idx 11}),
                       :idx 12}),
                     :idx 13}
                    {:expr x, :idx 14}),
                   :idx 15}),
                 :idx 16}),
              :idx 17,
              :local-vars {},
              :path ['sample-fn-once]
              :output s/Any,
              :schema (s/make-fn-schema s/Any [[(s/one s/Any 'x)]]),
              :arglists
              {1
               {:arglist ['x],
                :count 1,
                :schema [{:schema s/Any, :optional? false, :name 'x}]}},
              :finished? true},
          18 {:expr
              '({:expr def, :idx 1}
                {:expr sample-fn-once, :idx 2}
                {:expr
                 ({:expr fn*, :idx 3}
                  {:expr
                   ({:expr [{:expr x, :idx 4}], :idx 5}
                    {:expr
                     ({:expr
                       ({:expr fn*, :idx 6}
                        {:expr [{:expr y, :idx 7}], :idx 8}
                        {:expr
                         ({:expr int-add, :idx 9}
                          {:expr y, :idx 10}
                          {:expr nil, :idx 11}),
                         :idx 12}),
                       :idx 13}
                      {:expr x, :idx 14}),
                     :idx 15}),
                   :idx 16}),
                 :idx 17}),
              :idx 18,
              :name 'sample-fn-once,
              :path ['sample-fn-once]
              :schema (analysis-schema/variable (s/make-fn-schema s/Any [[(s/one s/Any 'x)]])),
              :finished? true}}
         (->> '(defn sample-fn-once
                 [x]
                 ((^{:once true} fn* [y] (int-add y nil))
                  x))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict))))
  (is (= {8 {:expr '+, :idx 8, :path ['sample-path-fn 'f]},
          9 {:expr 1, :idx 9, :path ['sample-path-fn 'f]},
          10 {:expr 'x, :idx 10, :path ['sample-path-fn 'f]},
          11 {:expr '({:expr +, :idx 8} {:expr 1, :idx 9} {:expr x, :idx 10}),
              :idx 11,
              :path ['sample-path-fn]},
          13 {:expr 'f, :idx 13, :path ['sample-path-fn]},
          14 {:expr 'x, :idx 14, :path ['sample-path-fn]},
          15 {:expr '({:expr f, :idx 13} {:expr x, :idx 14}),
              :idx 15,
              :path ['sample-path-fn]},
          16 {:expr
              '({:expr let*, :idx 6}
                {:expr
                 [{:expr f, :idx 7}
                  {:expr ({:expr +, :idx 8} {:expr 1, :idx 9} {:expr x, :idx 10}),
                   :idx 11}],
                 :idx 12}
                {:expr ({:expr f, :idx 13} {:expr x, :idx 14}), :idx 15}),
              :idx 16,
              :path ['sample-path-fn]},
          18 {:expr
              '({:expr fn*, :idx 3}
                {:expr
                 ({:expr [{:expr x, :idx 4}], :idx 5}
                  {:expr
                   ({:expr let*, :idx 6}
                    {:expr
                     [{:expr f, :idx 7}
                      {:expr
                       ({:expr +, :idx 8} {:expr 1, :idx 9} {:expr x, :idx 10}),
                       :idx 11}],
                     :idx 12}
                    {:expr ({:expr f, :idx 13} {:expr x, :idx 14}), :idx 15}),
                   :idx 16}),
                 :idx 17}),
              :idx 18,
              :path ['sample-path-fn]},
          19 {:expr
              '({:expr def, :idx 1}
                {:expr sample-path-fn, :idx 2}
                {:expr
                 ({:expr fn*, :idx 3}
                  {:expr
                   ({:expr [{:expr x, :idx 4}], :idx 5}
                    {:expr
                     ({:expr let*, :idx 6}
                      {:expr
                       [{:expr f, :idx 7}
                        {:expr
                         ({:expr +, :idx 8} {:expr 1, :idx 9} {:expr x, :idx 10}),
                         :idx 11}],
                       :idx 12}
                      {:expr ({:expr f, :idx 13} {:expr x, :idx 14}), :idx 15}),
                     :idx 16}),
                   :idx 17}),
                 :idx 18}),
              :idx 19,
              :path ['sample-path-fn]}}
         (->> '(defn sample-path-fn
                 [x]
                 (let [f (+ 1 x)]
                   (f x)))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (p/map-vals #(select-keys % [:expr :idx :path])))))
  (is (= {3 {:args [10],
             :path ['G],
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :local-vars {},
             :arglist [s/Any],
             :output s/Any,
             :expr 'make-component,
             :finished? true,
             :fn-position? true,
             :idx 3},
          4 {:expr :a,
             :idx 4,
             :local-vars {},
             :path ['G],
             :schema s/Keyword},
          5 {:expr 1, :idx 5, :local-vars {}, :path ['G], :schema s/Int},
          7 {:expr :b,
             :idx 7,
             :local-vars {},
             :path ['G],
             :schema s/Keyword},
          8 {:expr 2, :idx 8, :local-vars {}, :path ['G], :schema s/Int},
          10 {:expr [[:a 1] [:b 2]],
              :idx 10,
              :map? true,
              :local-vars {},
              :path ['G],
              :schema {s/Keyword s/Int},
              :finished? true},
          11 {:path [],
              :schema s/Any,
              :local-vars {},
              :name 'G,
              :expr '(make-component [[:a 1] [:b 2]]),
              :finished? true,
              :expected-arglist [s/Any],
              :idx 11,
              :actual-arglist [{s/Keyword s/Int}]},
          13 {:args [14 18],
              :path [],
              :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg) (s/one s/Any 'anon-arg)]]),
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :arglist [s/Any s/Any],
              :output s/Any,
              :expr 'start,
              :finished? true,
              :fn-position? true,
              :idx 13},
          14 {:expr 'G,
              :idx 14,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :path [],
              :schema s/Any},
          15 {:expr :opt1,
              :idx 15,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :path [],
              :schema s/Keyword},
          16 {:expr true,
              :idx 16,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :path [],
              :schema java.lang.Boolean},
          18 {:expr [[:opt1 true]],
              :idx 18,
              :map? true,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :path [],
              :schema {s/Keyword java.lang.Boolean},
              :finished? true},
          19 {:path [],
              :schema s/Any,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :expr '(start G [[:opt1 true]]),
              :finished? true,
              :expected-arglist [s/Any s/Any],
              :idx 19,
              :actual-arglist [s/Any {s/Keyword java.lang.Boolean}]},
          20 {:expr 'G,
              :idx 20,
              :local-vars {'G {:schema s/Any
                               :resolution-path [{:idx 11}]}},
              :path [],
              :schema s/Any},
          21 {:expr
              '(let* [G (make-component [[:a 1] [:b 2]])],
                 (start G [[:opt1 true]]),
                 G),
              :idx 21,
              :schema s/Any,
              :finished? true}}
         (->> '(doto (make-component {:a 1 :b 2})
                 (start {:opt1 true}))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (clean-gen-var 'G)
              unannotate-results))))

(deftest analyse-problematic-let-test
  (is (= {3 {:args [4],
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg)]]),
             :arglist [s/Any],
             :output s/Any,
             :expr 'set-cache-value,
             :idx 3},
          4 {:expr 1, :idx 4, :schema s/Int},
          5 {:schema s/Any,
             :expr '(set-cache-value 1),
             :idx 5},
          7 {:expr 'G,
             :idx 7,
             :schema s/Any},
          8 {:expr '(let* [G (set-cache-value 1)] G),
             :idx 8,
             :schema s/Any}}
         (->> '(doto (set-cache-value 1))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (clean-gen-var 'G)
              unannotate-results
              (p/map-vals #(select-keys % [:expr :idx :schema :args :output :arglist])))))
  (is (= {3 {:expr :invalid,
             :idx 3,
             :schema s/Keyword},
          6 {:expr true,
             :idx 6,
             :schema java.lang.Boolean},
          7 {:args [8 9],
             :schema (s/make-fn-schema s/Any [[(s/one s/Any 'anon-arg) (s/one s/Any 'anon-arg)]]),
             :arglist [s/Any s/Any],
             :output s/Any,
             :expr '=,
             :idx 7},
          8 {:expr 'G,
             :idx 8,
             :schema s/Keyword},
          9 {:expr :valid,
             :idx 9,
             :schema s/Keyword},
          10 {:schema s/Any,
              :expr '(= G :valid),
              :idx 10},
          11 {:expr 'G,
              :idx 11,
              :schema s/Keyword},
          12 {:expr '(if true (= G :valid) G),
              :idx 12,
              :schema (analysis-schema/join s/Any s/Keyword)},
          13 {:expr '(let* [G :invalid] (if true (= G :valid) G)),
              :idx 13,
              :schema (analysis-schema/join s/Any s/Keyword)}}
         (->> '(cond-> :invalid
                 true
                 (= :valid))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (clean-gen-var 'G)
              unannotate-results
              (p/map-vals #(select-keys % [:expr :idx :schema :args :output :arglist])))))
  (is (= {4 {:expr 'set-cache-value, :idx 4, :schema s/Any},
           6 {:expr 'G, :idx 6, :schema s/Any},
           7 {:expr '(let* cache [G set-cache-value] G), :idx 7, :schema s/Any}}
         (->> '(-> cache
                 (doto set-cache-value))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (clean-gen-var 'G)
              unannotate-results
              (p/map-vals #(select-keys % [:expr :idx :schema :args :output :arglist])))))
  (is (= {3 {:expr :invalid, :idx 3, :schema s/Keyword},
           6 {:expr true, :idx 6, :schema java.lang.Boolean},
           10 {:expr 'set-cache-value, :idx 10, :schema s/Any},
           12 {:expr 'G, :idx 12, :schema s/Any},
           13 {:expr '(let* G [G set-cache-value] G), :idx 13, :schema s/Any},
           14 {:expr 'G, :idx 14, :schema s/Keyword},
           15 {:expr '(if true (let* G [G set-cache-value] G) G),
            :idx 15,
               :schema (analysis-schema/join s/Any s/Keyword)},
           16 {:expr '(let* [G :invalid] (if true (let* G [G set-cache-value] G) G)),
            :idx 16,
               :schema (analysis-schema/join s/Any s/Keyword)}}
         (->> '(cond-> :invalid
                 true
                 (doto set-cache-value))
              (schematize/resolve-all {})
              (sut/attach-schema-info-loop test-examples/sample-dict)
              (clean-gen-var 'G)
              unannotate-results
              (p/map-vals #(select-keys % [:expr :idx :schema :args :output :arglist]))
              (into (sorted-map))
              ))))

;; (deftest attach-schema-info-added-maybe
;;   (is (= {}
;;          (->> '(s/defn same-day-at-time :- common-schema/DateTimeZ
;;                  "Return a `DateTimeZ` for a `DateTime` and anything that supports the interface for
;;    `(time/{hour,minute,second,milli})`. Assumes for the moment that the given `DateTime`
;;    is for Chicago time."
;;                  [dt
;;                   time
;;                   timezone :- common-schema/TimeZone]
;;                  (let [at-local-time (cond-> dt
;;                                        (instance? DateTime dt) (utils/date-time->local-date-time timezone))]
;;                    (-> (time/date-time
;;                         (time/year at-local-time)
;;                         (time/month at-local-time)
;;                         (time/day at-local-time)
;;                         (time/hour time)
;;                         (time/minute time)
;;                         (time/second time)
;;                         (time/milli time))
;;                        (time/from-time-zone timezone)
;;                        (time/to-time-zone time/utc))))
;;               (schematize/resolve-all {})
;;               (sut/attach-schema-info-loop test-examples/sample-dict)
;;               (clean-gen-var 'G)
;;               unannotate-results
;;               (p/map-vals #(select-keys % [:expr :idx :schema :args :output :arglist]))
;;               (into (sorted-map))

;;               ))))
