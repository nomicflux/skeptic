(ns skeptic.worker.process-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.worker.process :as process]))

(deftest spawn-cp-is-the-project-classpath-unchanged
  ;; The worker -cp IS the project's resolved classpath, full stop. Skeptic's own
  ;; entries are NOT unioned on: the project depends on Skeptic, so the worker
  ;; boot code is already on the project cp. process.clj exposes no classpath
  ;; assembly fn anymore; spawn! takes the project cp string as given.
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'worker-classpath)))
  (is (not (contains? (ns-publics 'skeptic.worker.process) 'self-classpath-entries))))

(deftest spawn-takes-a-classpath-string
  (let [arglists (:arglists (meta #'process/spawn!))]
    (is (= '([cp]) (map #(mapv (comp symbol name) %) arglists)))))
