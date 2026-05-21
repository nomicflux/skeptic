(ns skeptic.cljs-fixtures.p9-recursive-specialization.core
  (:require-macros [schema.core :as s])
  (:require [schema.core :as s :include-macros true]))

(s/defn map-shaped-success :- [s/Int]
  [colls :- [[s/Int]]]
  (let [step (fn step [cs]
               (lazy-seq
                (let [ss (map seq cs)]
                  (when (every? identity ss)
                    (cons (map first ss)
                          (step (map rest ss)))))))]
    (map #(apply + %) (step colls))))

(s/defn recursive-local-output-bad :- s/Int
  [xs :- [s/Int]]
  (let [step (fn step [cs]
               (if (seq cs)
                 (step (map inc cs))
                 "done"))]
    (step xs)))
