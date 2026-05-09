(ns skeptic.cli.cljs.deps-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.cli.cljs.deps :as sut])
  (:import [java.io File]))

(def fixture-root "dev-resources/cljs-fixtures/p2-deps")

(defn- file-names [files]
  (set (map (fn [^File f] (.getName f)) files)))

(deftest discover-sources-walks-deps-paths-for-cljs-and-cljc
  (let [{:keys [source-paths cljs-files cljc-files]}
        (sut/discover-sources fixture-root [])]
    (testing "source-paths is non-empty (resolved by tools.deps)"
      (is (seq source-paths)))
    (testing "cljs-files = exactly the fixture's foo.cljs"
      (is (= #{"foo.cljs"} (file-names cljs-files))))
    (testing "cljc-files = exactly the fixture's bar.cljc"
      (is (= #{"bar.cljc"} (file-names cljc-files))))
    (testing "no .clj files leak into either bucket"
      (is (not (contains? (file-names cljs-files) "jvm.clj")))
      (is (not (contains? (file-names cljc-files) "jvm.clj"))))))
