(ns skeptic.analysis.annotation
  (:require [clojure.walk :as walk]))

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
                             (into {} (:expr el))
                             (:expr el))
                           el))
                 expr))
