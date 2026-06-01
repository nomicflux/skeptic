(ns skeptic.worker.process-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.worker.process :as process]))

(deftest spawn-cp-is-the-project-classpath-unchanged
  ;; process.clj owns only subprocess launch. Classpath assembly lives at the
  ;; CLI boundary; spawn! takes the already assembled worker cp string as given.
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'worker-classpath)))
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'self-classpath-entries))))

(deftest spawn-takes-a-classpath-string
  (let [arglists (:arglists (meta #'process/spawn!))]
    (is (= '([cp]) (map #(mapv (comp symbol name) %) arglists)))))
