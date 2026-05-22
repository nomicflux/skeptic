(ns skeptic.cli.main-project-classpath-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.cli.main :as main])
  (:import [java.io File]))

(def ^:private fixture-root
  (.getAbsoluteFile (File. "dev-resources/cljs-fixtures/p12-shadow-runtime")))

(deftest cli-trampoline-loads-project-with-shadow-deps-alias-through-public-entrypoint
  (let [original-user-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" (.getAbsolutePath fixture-root))
      (let [out  (with-out-str
                   (is (= 1 (main/run-cli []))))
            skip-line "Skeptic skipped namespace p12-shadow-runtime.core"]
        (is (not (str/includes? out skip-line)))
        (is (str/includes? out "p12-shadow-runtime.core: 1")))
      (finally
        (System/setProperty "user.dir" original-user-dir)))))
