(ns skeptic.checking.local-resolution-test
  "Shadowing / provenance: assert only on text printed by the public check entrypoint."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.core :as core]))

(defn- strip-ansi [s]
  (str/replace (str s) #"\u001B\[[0-9;]*m" ""))

(defn- printed-output-for-fixture-file
  "Same lines a user sees from `lein skeptic`: verbose report + Context (when shown)."
  [fixture-relative-path namespace-sym]
  (strip-ansi
   (with-out-str
     (core/check-project
      {:verbose true
       :show-context true
       :namespace namespace-sym}
      (io/file ".")
      fixture-relative-path))))

(deftest shadowing-shows-correct-binding-in-printed-output
  (require 'skeptic.local-resolution-fixtures)
  (let [out (printed-output-for-fixture-file
             "test/skeptic/local_resolution_fixtures.clj"
             'skeptic.local-resolution-fixtures)]
    (is (not (str/includes? out "(int-add 9 9)"))
        "outer param x after inner let must not be explained via inner (int-add 9 9) in printed output")))

(deftest local-named-import-not-shown-as-core-import
  (require 'skeptic.local-resolution-fixtures)
  (let [out (printed-output-for-fixture-file
             "test/skeptic/local_resolution_fixtures.clj"
             'skeptic.local-resolution-fixtures)]
    (is (not (str/includes? out "clojure.core/import"))
        "local name import must not be described as clojure.core/import in printed output")))
