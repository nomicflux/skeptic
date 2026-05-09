(ns skeptic.cli.cljs.shadow-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.cli.cljs.shadow :as sut])
  (:import [java.io File]))

(def fixture-root "dev-resources/cljs-fixtures/p2-shadow")

(defn- file-names [files]
  (set (map (fn [^File f] (.getName f)) files)))

(deftest discover-sources-reads-shadow-cljs-edn-and-walks-source-paths
  (let [{:keys [source-paths cljs-files cljc-files]}
        (sut/discover-sources fixture-root)]
    (testing "source-paths is exactly the one configured src"
      (is (= 1 (count source-paths))))
    (testing "cljs-files = foo.cljs"
      (is (= #{"foo.cljs"} (file-names cljs-files))))
    (testing "cljc-files = bar.cljc"
      (is (= #{"bar.cljc"} (file-names cljc-files))))
    (testing "jvm.clj is not in either bucket"
      (is (not (contains? (file-names cljs-files) "jvm.clj")))
      (is (not (contains? (file-names cljc-files) "jvm.clj"))))))

(deftest discover-sources-throws-when-shadow-cljs-edn-missing
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No shadow-cljs.edn found"
                        (sut/discover-sources "dev-resources/cljs-fixtures/p2-deps"))))
