(ns skeptic.analysis-test
  (:require [skeptic.analysis :as sut]
            [clojure.test :refer [deftest is are]]
            [clojure.walk :as walk]
            [skeptic.schematize :as schematize]
            [schema.core :as s]))

(defn clean-callbacks
  [expr]
  (walk/postwalk #(if (map? %) (dissoc % :dep-callback) %) expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk #(if (and (map? %) (contains? % :expr)) (:expr %) %) expr))

(deftest resolve-local-vars
  (is (= {:expr '(+ 1 x), :local-vars {}}
         (sut/resolve-local-vars {} {:expr '(+ 1 x)
                                 :local-vars {}})))
  (is (= {:expr '(+ 1 x), :local-vars {'x s/Int}}
         (sut/resolve-local-vars {} {:expr '(+ 1 x)
                                     :local-vars {'x s/Int}})))
  (is (= {:expr '(+ y x), :local-vars {'x s/Int
                                       'y s/Int}}
         (sut/resolve-local-vars {3 {:expr '(+ 1 2)
                                     :name 'x
                                     :schema s/Int}
                                  4 {:expr nil
                                     :name 'y
                                     :schema (s/maybe s/Int)}}
                                 {:expr '(+ y x)
                                     :local-vars {'y s/Int
                                                  'x #::sut{:placeholder 3}}}))))

(deftest analyse-let-test
  (let [analysed (->> '(let [x 1] (+ 1 x))
                      schematize/macroexpand-all
                      sut/annotate-expr
                      sut/analyse-let)]
    (is (= [{:expr 1, :idx 3, :local-vars {}}
             {:expr
              {:expr [{:expr '+, :idx 5} {:expr 1, :idx 6} {:expr 'x, :idx 7}],
               :idx 8},
              :local-vars {'x #::sut{:placeholder 3}}}
             {:expr
              [{:expr 'let*, :idx 1}
               {:expr [{:expr 'x, :idx 2} {:expr 1, :idx 3}], :idx 4}
               {:expr [{:expr '+, :idx 5} {:expr 1, :idx 6} {:expr 'x, :idx 7}],
                :idx 8}],
              :idx 9}]
           (clean-callbacks analysed)))
    (is (= '(1 (+ 1 x) (let* [x 1] (+ 1 x)))
           (unannotate-expr analysed))))

  (let [analysed (->> '(let [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y))
                      schematize/macroexpand-all
                      sut/annotate-expr
                      sut/analyse-let)]
    (is (= [{:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
              :idx 6,
             :local-vars {}}
            {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
              :idx 11,
              :local-vars {'x #::sut{:placeholder 6}}}
             {:expr
              {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
               :idx 16},
              :local-vars {'x #::sut{:placeholder 6}
                           'y #::sut{:placeholder 11}}}
             {:expr
              {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
               :idx 20},
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
              :idx 21}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2)
             (+ 3 x)
             (+ 7 x)
             (+ x y)
             (let* [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y)))
           (unannotate-expr analysed)))))
