(ns skeptic.malli-spec.collect.cljs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.malli-spec.collect.cljs :as sut]
            [skeptic.worker.analyzer-cljs :as wac]))

(def ^:private fixture-path "dev-resources/cljs-fixtures/p5.cljs")

(def ^:private ^:dynamic *result* nil)

(defn- source-form-malli-schema
  "Read `:malli/schema` off a raw `defn` source-form (name-sym reader-meta or
  attr-map at position 2), mirroring `skeptic.worker.server/project-cljs-entry`
  which attaches it to each shipped cljs entry as `:malli-schema`."
  [source-form]
  (when (seq? source-form)
    (or (:malli/schema (meta (second source-form)))
        (let [attr-map (nth source-form 2 nil)]
          (when (map? attr-map) (:malli/schema attr-map))))))

(defn- collect-once
  [f]
  (require 'malli.core)
  (let [{:keys [entries]} (wac/analyze-source-file fixture-path)
        ;; project-cljs-entry attaches :malli-schema off the raw source-form;
        ;; the local analyzer does not, so attach it here to feed the collector
        ;; the entry shape production ships.
        entries (mapv (fn [{:keys [source-form] :as e}]
                        (cond-> e
                          (source-form-malli-schema source-form)
                          (assoc :malli-schema (source-form-malli-schema source-form))))
                      entries)
        result (sut/ns-malli-spec-results-cljs fixture-path 'p5 entries)]
    (binding [*result* result] (f))))

(use-fixtures :once collect-once)

(deftest p5-cljs-no-errors
  (is (empty? (:errors *result*))))

(deftest p5-cljs-admits-both-channels
  ;; `g` via the `(m/=> g ...)` registration channel (worker AST
  ;; `-register-function-schema!` invoke); `h` via the `:malli/schema`
  ;; var-meta channel (entry `:malli-schema` field).
  (is (= #{'p5/g 'p5/h}
         (set (keys (:entries *result*))))))

(deftest p5-cljs-spec-matches-jvm
  (let [expected (amb/admit-malli-spec [:=> [:cat :int] :int])]
    (is (= expected (-> *result* :entries (get 'p5/g) :malli-spec)))
    (is (= expected (-> *result* :entries (get 'p5/h) :malli-spec)))))
