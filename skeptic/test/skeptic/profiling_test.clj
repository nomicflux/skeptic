(ns skeptic.profiling-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            skeptic.profiling))

(def ^:private demangle-class #'skeptic.profiling/demangle-class)
(def ^:private aggregate-frame-data #'skeptic.profiling/aggregate-frame-data)
(def ^:private aggregate-alloc-data #'skeptic.profiling/aggregate-alloc-data)
(def ^:private format-bytes #'skeptic.profiling/format-bytes)
(def ^:private print-summary #'skeptic.profiling/print-summary)

(deftest test-demangle-class
  (is (= "skeptic.core/check-project" (@demangle-class "skeptic.core$check_project")))
  (is (= "skeptic.analysis.cast/cast-type" (@demangle-class "skeptic.analysis.cast$cast_type")))
  (is (= "clojure.core/assoc" (@demangle-class "clojure.core$assoc")))
  (is (= "skeptic.core/<fn>" (@demangle-class "skeptic.core$fn__12345")))
  (is (= "skeptic.core/<eval>" (@demangle-class "skeptic.core$eval12345")))
  (is (= "skeptic.core/check-project/<inner>" (@demangle-class "skeptic.core$check_project$inner__456")))
  (is (= "java.lang.String" (@demangle-class "java.lang.String")))
  (is (= "my.ns/my-long-fn-name" (@demangle-class "my.ns$my_long_fn_name"))))

(deftest test-aggregate-frame-data
  (is (= {:total-samples 0 :per-fn {}} (@aggregate-frame-data [])))
  (is (= {:total-samples 1
          :per-fn {"a" {:self 1 :total 1}
                   "b" {:total 1}
                   "c" {:total 1}}}
         (@aggregate-frame-data [["a" "b" "c"]])))
  (is (= {:total-samples 2
          :per-fn {"a" {:self 1 :total 1}
                   "b" {:self 1 :total 2}
                   "c" {:total 1}}}
         (@aggregate-frame-data [["a" "b"] ["b" "c"]])))
  (is (= {:total-samples 1
          :per-fn {"a" {:self 1 :total 1}
                   "b" {:total 1}}}
         (@aggregate-frame-data [["a" "a" "b"]])))
  (is (= {:total-samples 1
          :per-fn {"a" {:self 1 :total 1}}}
         (@aggregate-frame-data [[] ["a"]]))))

(deftest test-aggregate-alloc-data
  (is (= {:total-bytes 0 :per-fn {}} (@aggregate-alloc-data [])))
  (is (= {:total-bytes 300
          :per-fn {"a" {:self-alloc-bytes 300
                        :total-alloc-bytes 300}}}
         (@aggregate-alloc-data [{:frames ["a"] :weight 100}
                                {:frames ["a"] :weight 200}])))
  (is (= {:total-bytes 150
          :per-fn {"a" {:self-alloc-bytes 100
                        :total-alloc-bytes 100}
                   "b" {:self-alloc-bytes 50
                        :total-alloc-bytes 50}}}
         (@aggregate-alloc-data [{:frames ["a"] :weight 100}
                                {:frames ["b"] :weight 50}])))
  (is (= {:total-bytes 100
          :per-fn {"java" {:self-alloc-bytes 100
                           :total-alloc-bytes 100}
                   "skeptic.core/check-project" {:total-alloc-bytes 100}
                   "skeptic.analysis/run" {:total-alloc-bytes 100}}}
         (@aggregate-alloc-data [{:frames ["java"
                                           "skeptic.core/check-project"
                                           "skeptic.analysis/run"]
                                  :weight 100}])))
  (is (= {:total-bytes 100
          :per-fn {"skeptic.core/recur" {:self-alloc-bytes 100
                                         :total-alloc-bytes 100}
                   "skeptic.analysis/parent" {:total-alloc-bytes 100}}}
         (@aggregate-alloc-data [{:frames ["skeptic.core/recur"
                                           "skeptic.core/recur"
                                           "skeptic.analysis/parent"]
                                  :weight 100}]))))

(deftest test-format-bytes
  (is (= "0 B" (@format-bytes 0)))
  (is (= "512 B" (@format-bytes 512)))
  (is (= "1.0 KB" (@format-bytes 1024)))
  (is (= "1.5 KB" (@format-bytes 1536)))
  (is (= "1.0 MB" (@format-bytes 1048576)))
  (is (= "1.0 GB" (@format-bytes 1073741824))))

(deftest test-print-summary-handles-missing-self-counts
  (let [output (with-out-str
                 (@print-summary {:execution {:total-samples 2
                                             :per-fn {"skeptic.core/a" {:self 1
                                                                        :total 1
                                                                        :self-pct 50.0
                                                                        :total-pct 50.0}
                                                      "skeptic.analysis/b" {:total 1
                                                                            :self-pct 0.0
                                                                            :total-pct 50.0}
                                                      "clojure.core/map" {:self 10
                                                                          :total 10
                                                                          :self-pct 500.0
                                                                          :total-pct 500.0}}}
                                  :allocation {:total-bytes 0
                                               :per-fn {}}
                                  :cpu nil
                                  :duration-ms 1000}
                                 "target/skeptic-profile.jfr"))]
    (is (str/includes? output "skeptic.core/a"))
    (is (str/includes? output "skeptic.analysis/b"))
    (is (not (str/includes? output "clojure.core/map")))
    (is (< (str/index-of output "skeptic.core/a")
           (str/index-of output "skeptic.analysis/b")))))

(deftest test-print-summary-only-shows-skeptic-allocation-entries
  (let [output (with-out-str
                 (@print-summary {:execution {:total-samples 0
                                             :per-fn {}}
                                  :allocation {:total-bytes 1536
                                               :per-fn {"skeptic.core/check-project" {:self-alloc-bytes 0
                                                                                      :self-alloc-pct 0.0
                                                                                      :total-alloc-bytes 1024
                                                                                      :total-alloc-pct 66.7}
                                                        "java.lang.String/valueOf" {:self-alloc-bytes 512
                                                                                    :self-alloc-pct 33.3
                                                                                    :total-alloc-bytes 512
                                                                                    :total-alloc-pct 33.3}}}
                                  :cpu nil
                                  :duration-ms 1000}
                                 "target/skeptic-profile.jfr"))]
    (is (str/includes? output "--- Top Skeptic Functions by Allocation ---"))
    (is (str/includes? output "skeptic.core/check-project"))
    (is (str/includes? output "66.7%"))
    (is (not (str/includes? output "java.lang.String/valueOf")))
    (is (str/includes? output "Raw profile data: target/skeptic-profile.jfr"))))

(deftest test-print-summary-shows-skeptic-empty-state-when-only-non-skeptic-data-exists
  (let [output (with-out-str
                 (@print-summary {:execution {:total-samples 3
                                             :per-fn {"clojure.core/map" {:self 2
                                                                          :total 3
                                                                          :self-pct 66.7
                                                                          :total-pct 100.0}}}
                                  :allocation {:total-bytes 512
                                               :per-fn {"java.lang.String/valueOf" {:self-alloc-bytes 512
                                                                                    :self-alloc-pct 100.0
                                                                                    :total-alloc-bytes 512
                                                                                    :total-alloc-pct 100.0}}}
                                  :cpu nil
                                  :duration-ms 1000}
                                 "target/skeptic-profile.jfr"))]
    (is (str/includes? output "(no Skeptic execution samples captured)"))
    (is (str/includes? output "(no Skeptic allocation samples captured)"))))

(deftest test-print-summary-ranks-skeptic-allocation-by-inclusive-bytes
  (let [output (with-out-str
                 (@print-summary {:execution {:total-samples 0
                                             :per-fn {}}
                                  :allocation {:total-bytes 3072
                                               :per-fn {"skeptic.core/check-project" {:self-alloc-bytes 0
                                                                                      :self-alloc-pct 0.0
                                                                                      :total-alloc-bytes 2048
                                                                                      :total-alloc-pct 66.7}
                                                        "skeptic.analysis/phase" {:self-alloc-bytes 1024
                                                                                  :self-alloc-pct 33.3
                                                                                  :total-alloc-bytes 1024
                                                                                  :total-alloc-pct 33.3}}}
                                  :cpu nil
                                  :duration-ms 1000}
                                 "target/skeptic-profile.jfr"))]
    (is (< (str/index-of output "skeptic.core/check-project")
           (str/index-of output "skeptic.analysis/phase")))
    (is (str/includes? output "2.0 KB"))
    (is (str/includes? output "1.0 KB"))))
