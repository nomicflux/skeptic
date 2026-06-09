(ns skeptic.worker.process-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.worker.process :as process]))

(deftest spawn-takes-assembled-worker-classpath
  ;; process.clj owns only subprocess launch. Classpath assembly lives in
  ;; skeptic.worker.classpath; spawn! takes the already assembled combined
  ;; launch classpath string.
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'worker-classpath)))
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'self-classpath-entries))))

(deftest spawn-takes-single-combined-classpath
  (let [arglists (:arglists (meta #'process/spawn!))]
    (is (= '([combined-cp verbose?])
           (map #(mapv (comp symbol name) %) arglists)))))
