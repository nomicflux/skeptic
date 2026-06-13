(ns skeptic.worker.discover-source-namespaces-test
  "Worker round-trip for the `discover-source-namespaces` op. The host sends a
   list of paths; the worker enumerates each path with bultitude scoped to
   that path as a single classpath root and replies with (ns-sym, source-file)
   pairs. The op is the seam Phase 3 swaps for the previous host-side
   filesystem walk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [skeptic.worker.client :as wc]
            [skeptic.worker.harness-test :as harness])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-discover-sources-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(defn- spit-ns
  [^java.io.File root rel-path body]
  (let [target (io/file root rel-path)]
    (.mkdirs (.getParentFile target))
    (spit target body)
    target))

(deftest discover-source-namespaces-finds-clj-and-cljc-under-given-paths
  (let [src (temp-dir!)
        tst (temp-dir!)]
    (try
      (spit-ns src "alpha/one.clj" "(ns alpha.one)")
      (spit-ns src "alpha/sub/two.cljc" "(ns alpha.sub.two)")
      (spit-ns tst "beta/three_test.clj" "(ns beta.three-test)")
      (harness/with-worker
        (fn [conn]
          (let [reply (wc/ask conn {:op "discover-source-namespaces"
                                    :paths [(.getAbsolutePath src)
                                            (.getAbsolutePath tst)]})
                pairs (:pairs reply)
                index (into {} (map (juxt :ns-sym :source-file) pairs))]
            (is (= #{"alpha.one" "alpha.sub.two" "beta.three-test"}
                   (set (keys index))))
            (is (= (.getAbsolutePath (io/file src "alpha/one.clj"))
                   (get index "alpha.one")))
            (is (= (.getAbsolutePath (io/file src "alpha/sub/two.cljc"))
                   (get index "alpha.sub.two")))
            (is (= (.getAbsolutePath (io/file tst "beta/three_test.clj"))
                   (get index "beta.three-test"))))))
      (finally
        (delete-recursively! src)
        (delete-recursively! tst)))))

(deftest discover-source-namespaces-skips-missing-paths
  (testing "non-existent paths are silently skipped, valid paths still produce pairs"
    (let [src (temp-dir!)]
      (try
        (spit-ns src "only/here.clj" "(ns only.here)")
        (harness/with-worker
          (fn [conn]
            (let [reply (wc/ask conn {:op "discover-source-namespaces"
                                      :paths [(.getAbsolutePath src)
                                              "/nonexistent/path/should/be/skipped"]})]
              (is (= [{:ns-sym "only.here"
                       :source-file (.getAbsolutePath (io/file src "only/here.clj"))}]
                     (:pairs reply))))))
        (finally
          (delete-recursively! src))))))
