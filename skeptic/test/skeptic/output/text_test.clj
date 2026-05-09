(ns skeptic.output.text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.output.text :as sut]))

(defn- run-end-out
  [errored? totals opts]
  (with-out-str ((:run-end sut/printer) errored? totals opts)))

(deftest run-end-clean-run-prints-no-block
  (let [out (run-end-out
             false
             {:per-namespace-counts {'foo.a 0 'foo.b 0}}
             {})]
    (is (str/includes? out "No inconsistencies found"))
    (is (not (str/includes? out "Per-namespace inconsistencies")))))

(deftest run-end-errored-default-shows-only-nonzero-sorted-by-count-desc
  (let [out (run-end-out
             true
             {:per-namespace-counts {'foo.a 2 'foo.b 5 'foo.c 0}}
             {})
        body-lines (->> (str/split-lines out)
                        (drop-while #(not (str/starts-with? % "Per-namespace"))))]
    (is (= "Per-namespace inconsistencies:" (first body-lines)))
    (is (= ["  foo.b: 5" "  foo.a: 2"] (vec (rest body-lines))))
    (is (not (str/includes? out "foo.c")))))

(deftest run-end-verbose-includes-zeros
  (let [out (run-end-out
             true
             {:per-namespace-counts {'foo.a 2 'foo.b 0 'foo.c 0}}
             {:verbose true})
        body-lines (->> (str/split-lines out)
                        (drop-while #(not (str/starts-with? % "Per-namespace"))))]
    (is (= "Per-namespace inconsistencies:" (first body-lines)))
    (is (= #{"  foo.a: 2" "  foo.b: 0" "  foo.c: 0"} (set (rest body-lines))))
    (testing "non-zero entry leads"
      (is (= "  foo.a: 2" (second body-lines))))))

(deftest run-end-clean-verbose-still-shows-block
  (let [out (run-end-out
             false
             {:per-namespace-counts {'foo.a 0 'foo.b 0}}
             {:verbose true})]
    (is (str/includes? out "No inconsistencies found"))
    (is (str/includes? out "Per-namespace inconsistencies:"))
    (is (str/includes? out "  foo.a: 0"))
    (is (str/includes? out "  foo.b: 0"))))

(defn- location-text
  [location]
  (->> (sut/report-fields {:report-kind :input :location location :blame-side :term :blame-polarity :positive})
       (some (fn [[label value]]
               (when (str/starts-with? label "Location:") value)))))

(deftest format-location-renders-keyword-lang
  (is (= "src/foo.clj:42:3 [source: schema] [lang: clj]"
         (location-text {:file "src/foo.clj" :line 42 :column 3 :source :schema :lang :clj}))))

(deftest format-location-renders-set-lang-as-slash-joined
  (is (= "src/foo.clj:42:3 [source: schema] [lang: clj/cljs]"
         (location-text {:file "src/foo.clj" :line 42 :column 3 :source :schema :lang #{:cljs :clj}}))))

(deftest format-location-omits-lang-when-absent
  (is (= "src/foo.clj:42:3 [source: schema]"
         (location-text {:file "src/foo.clj" :line 42 :column 3 :source :schema}))))
