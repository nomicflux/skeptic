(ns skeptic.analysis.annotation
  (:require [clojure.walk :as walk]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.ef]
            [skeptic.schematize :as schematize]
            [clojure.pprint :as pprint]))

(defn apply-paths
  [paths expr]
  (reduce
   (fn [acc [path idx]]
     (assoc-in acc (conj path :idx) idx))
   expr
   paths))

(defn idx-expression
  [expr]
  (loop [exprs-with-paths [[[] expr]]
         paths []
         n 0]
    (if (empty? exprs-with-paths)
      (apply-paths paths expr)
      (let [[p e] (first exprs-with-paths)
            r (rest exprs-with-paths)]
        (if (vector? e)
          (recur (concat (map-indexed (fn [i a] [(conj p i) a]) e) r) paths n)
          (recur (concat (map (fn [c] [(conj p c) (get e c)]) (:children e)) r)
                 (conj paths [p n])
                 (inc n)))))))

(defn annotate-expr
  [expr]
  (walk/postwalk (let [n (atom 0)]
                   (fn [f]
                     (let [idx (swap! n inc)]
                       (cond
                         (instance? clojure.lang.IMapEntry f)
                         {idx {:expr f :idx idx}}

                         (map? f)
                         {:expr (vec (vals f)) :idx idx :map? true}

                         :else
                         {:expr f :idx idx}))))
                 expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk (fn [el] (if (and (map? el) (contains? el :expr))
                           (if (:map? el)
                             (try (into {} (:expr el))
                                  ;; TODO: Why do certain maps get split out differently? This
                                  ;; appears to happen almost entirely with compojure routes for the moment.
                                  (catch Exception _e
                                    (apply hash-map (:expr el))))
                             (:expr el))
                           el))
                 expr))
