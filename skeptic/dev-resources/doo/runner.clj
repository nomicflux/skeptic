(ns doo.runner
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.test]))

(defn count-tests
  [namespaces]
  (->> namespaces
       (map (fn [ns]
              (count (filter (fn [[_ v]] (:test v))
                             (ana-api/ns-interns ns)))))
       (reduce +)))

(defmacro doo-tests
  [& namespaces]
  `(doo.runner/set-entry-point!
    (if (doo.runner/karma?)
      (fn [tc#]
        (jx.reporter.karma/start tc# ~(count-tests (map second namespaces)))
        (cljs.test/run-tests
         (cljs.test/empty-env :jx.reporter.karma/karma)
         ~@namespaces))
      (fn []
        (cljs.test/run-tests ~@namespaces)))))
