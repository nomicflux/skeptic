(ns skeptic.core
  (:require [skeptic.schematize :as schematize]
            [skeptic.checking :as checking]
            [schema.core :as s]
            [clojure.string :as str]))

(defn get-project-schemas
  [group-name]
  (let [nss (->> (all-ns)
                 (map ns-name)
                 (filter #(str/starts-with? % group-name))
                 sort)]
    (println (pr-str nss))
    (doseq [ns nss]
              (require ns)
              (println "Checking" ns)
              (println (checking/check-ns ns)))))

(s/defn int-add :- s/Int
  ([x :- s/Int]
   x)
  ([x :- s/Int
    y :- s/Int]
   (+ x y))
  ([x :- s/Int
    y :- s/Int
    & zs :- [s/Int]]
   (reduce + (+ x y) zs)))

(defn bad-fn
  [x]
  (int-add x nil))
