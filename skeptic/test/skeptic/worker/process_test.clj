(ns skeptic.worker.process-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [skeptic.worker.process :as process]))

(deftest self-classpath-entries-carries-skeptic
  (let [entries (process/self-classpath-entries)]
    (is (seq entries))
    (is (some #(str/includes? % "skeptic") entries))))

(deftest worker-classpath-unions-project-and-self
  (let [cp (process/worker-classpath "/x")]
    (is (str/includes? cp "/x"))
    (is (str/includes? cp "skeptic"))))
