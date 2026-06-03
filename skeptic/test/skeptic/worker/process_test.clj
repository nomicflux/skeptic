(ns skeptic.worker.process-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.worker.process :as process]))

(deftest spawn-takes-assembled-worker-classpath
  ;; process.clj owns only subprocess launch. Classpath assembly lives in
  ;; skeptic.worker.classpath; spawn! takes the already assembled runtime and
  ;; project cp strings as given.
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'worker-classpath)))
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'self-classpath-entries))))

(deftest spawn-takes-separated-classpath-strings
  (let [arglists (:arglists (meta #'process/spawn!))]
    (is (= '([runtime-cp] [runtime-cp project-cp])
           (map #(mapv (comp symbol name) %) arglists)))))
