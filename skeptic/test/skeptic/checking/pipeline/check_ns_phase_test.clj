(ns skeptic.checking.pipeline.check-ns-phase-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest check-ns-allows-empty-namespaces
  (require 'skeptic.core-fns)
  (let [file (java.io.File. "src/skeptic/core_fns.clj")]
    (is (= []
           (:results (sut/check-ns (ps/project-state-for 'skeptic.core-fns file)
                                   'skeptic.core-fns
                                   file
                                   {}))))))

;; check-ns-shares-analyzer-namespace-map-across-forms DELETED (Plan 2 §0 cutover):
;; it asserted the host called clojure.tools.analyzer.jvm/build-ns-map exactly
;; once across a namespace's forms — a host-side ana.jvm analyzer-sharing
;; optimization the cutover removed entirely. Host no longer runs ana.jvm/analyze
;; (the worker analyzes the namespace in one bulk RPC), so the guarded mechanism
;; no longer exists and the test has no behavioral analogue. See plan2-test-port-baseline.txt.

(deftest check-namespace-localizes-read-failures
  (let [temp-file (doto (java.io.File/createTempFile "skeptic-read-failure" ".clj")
                    (.deleteOnExit))
        _ (spit temp-file "(ns skeptic.test-examples.basics)\n(def ok 1)\n(def broken [)\n")
        {:keys [results]} (sut/check-namespace (ps/project-state-for 'skeptic.test-examples.basics temp-file)
                                               'skeptic.test-examples.basics
                                               temp-file
                                               {:remove-context true})]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :read (:phase (first results))))))

(deftest symbol-output-annotation-regression
  (let [results (filterv #(= 'skeptic.schema.collect/fully-qualify-str
                             (:enclosing-form %))
                         (:results (sut/check-ns (ps/project-state-for 'skeptic.schema.collect ps/schema-collect-file)
                                                 'skeptic.schema.collect
                                                 ps/schema-collect-file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest collect-annotations-output-annotation-regression
  (let [results (filterv #(= 'skeptic.schema.collect/collect-schemas
                             (:enclosing-form %))
                         (:results (sut/check-ns (ps/project-state-for 'skeptic.schema.collect ps/schema-collect-file)
                                                 'skeptic.schema.collect
                                                 ps/schema-collect-file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest static-call-examples-check-ns
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        nested-call-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                            (:enclosing-form %))
                                    %)
                                 results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        rebuilt-nested-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                                                    (:enclosing-form %))
                                            %)
                                         results)]
    (is (= 1 (count (:errors count-result))))
    (is (re-find #"(?s)\(get counts :count \"zero\"\).*in.*bad-count-default.*has inferred output type:.*\(union (Int Str|Str Int)\).*but the declared return type expects:.*Int"
                 (first (:errors count-result))))
    (is (= [(incm/mismatched-ground-type-msg
             {:expr '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
              :arg '(. clojure.lang.RT (clojure.core/get (nested-multi-step-g) :value))}
             (ps/T s/Int)
             (ps/T s/Str))]
           (:errors nested-call-result)))
    (is (some #(str/includes? % "{:name Keyword, :nickname (maybe Str)}")
              (:errors rebuilt-user-result)))
    (is (= 1 (count (:errors rebuilt-user-result))))
    (is (not-any? #(str/includes? % "Problem fields:") (:errors rebuilt-user-result)))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors rebuilt-nested-user-result)))
    (is (not-any? #(contains? #{'skeptic.static-call-examples/required-name
                                'skeptic.static-call-examples/optional-nickname
                                'skeptic.static-call-examples/nickname-with-default
                                'skeptic.static-call-examples/rebuilt-user
                                'skeptic.static-call-examples/rebuilt-nested-user
                                'skeptic.static-call-examples/merge-fields
                                'skeptic.static-call-examples/nested-multi-step-success}
                              (:enclosing-form %))
                   results))))

(deftest examples-maybe-multi-step-check-ns
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.examples ps/examples-file)
                                        'skeptic.examples
                                        ps/examples-file
                                        {:remove-context true}))]
    (is (some #(when (= 'skeptic.examples/flat-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/flat-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (nil? (some #(when (= 'skeptic.examples/flat-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))
    (is (nil? (some #(when (= 'skeptic.examples/nested-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))))

(deftest defrecord-factory-return-type-regression
  ;; `make-provenance`'s body is `(->Provenance ...)`, a call to the defrecord
  ;; factory var. The declared return is `Provenance`. The factory call must not
  ;; be inferred as a `java.lang.Object` ground (which would falsely flag every
  ;; record-returning fn in the project). Run the real checker on the real
  ;; source file and assert no finding lands on `make-provenance`.
  (require 'skeptic.provenance 'skeptic.provenance.schema)
  (let [file (java.io.File. "src/skeptic/provenance.clj")
        schema-file (java.io.File. "src/skeptic/provenance/schema.clj")
        project-state (ps/project-state-for-nses
                       {'skeptic.provenance file
                        'skeptic.provenance.schema schema-file})
        results (filterv #(= 'skeptic.provenance/make-provenance
                             (:enclosing-form %))
                         (:results (sut/check-ns project-state
                                                 'skeptic.provenance
                                                 file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest project-class-ground-resolves-via-worker-regression
  ;; A declared schema naming a non-bootstrap project class (here BufferedReader)
  ;; must mint a worker-resolved :class handle, not {:class nil} from host-handle.
  ;; Otherwise the call (read-port reader) leaf-overlaps its own declared
  ;; BufferedReader param because a nil class handle cannot be compared.
  (require 'skeptic.worker.process)
  (let [file (java.io.File. "src/skeptic/worker/process.clj")
        results (filterv #(= 'skeptic.worker.process/spawn!
                             (:enclosing-form %))
                         (:results (sut/check-ns (ps/project-state-for 'skeptic.worker.process file)
                                                 'skeptic.worker.process
                                                 file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest autoresolved-keyword-ns-regression
  ;; exact-key-query builds a map keyed by ::map-key-query, which auto-resolves
  ;; to :skeptic.analysis.map-ops/map-key-query in that ns. The worker must read
  ;; forms with *ns* bound to the source namespace, or ::keywords resolve to the
  ;; wrong ns (clojure.core) and the map fails its declared ExactKeyQuery schema.
  (require 'skeptic.analysis.map-ops 'skeptic.analysis.map-ops.schema 'skeptic.provenance.schema)
  (let [file (java.io.File. "src/skeptic/analysis/map_ops.clj")
        schema-file (java.io.File. "src/skeptic/analysis/map_ops/schema.clj")
        prov-schema-file (java.io.File. "src/skeptic/provenance/schema.clj")
        project-state (ps/project-state-for-nses
                       {'skeptic.analysis.map-ops file
                        'skeptic.analysis.map-ops.schema schema-file
                        'skeptic.provenance.schema prov-schema-file})
        results (filterv #(= 'skeptic.analysis.map-ops/exact-key-query
                             (:enclosing-form %))
                         (:results (sut/check-ns project-state
                                                 'skeptic.analysis.map-ops
                                                 file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest check-namespace-localizes-load-failure
  (let [valid-ns 'skeptic.test-examples.basics
        valid-file (ps/fixture-file-for-ns valid-ns)
        {:keys [results]} (sut/check-namespace (ps/project-state-for valid-ns valid-file)
                                               'skeptic.nonexistent.namespace.that.does.not.exist
                                               (java.io.File. "nonexistent.clj")
                                               {})]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :load (:phase (first results))))
    (is (= 'skeptic.nonexistent.namespace.that.does.not.exist
           (:namespace (first results))))))

(defn- exploding-def?
  "The cached entry / source-form for the def whose analysis or realization the
   lazy-exception tests force to throw."
  [source-form]
  (= 'sample-direct-nil-arg-fn (second source-form)))

(deftest check-ns-localizes-expression-exceptions-and-continues
  ;; Analysis-time exception localization. The worker-backed read pass annotates
  ;; each cached entry via check-cached-entry; redef it to throw for the
  ;; exploding def, and assert the exception is localized to its def while later
  ;; forms still check.
  (let [real-cached @#'sut/check-cached-entry]
    (with-redefs [sut/check-cached-entry
                  (fn [dict ignore-body ns source-file lang entry accessor-summaries form-opts]
                    (if (exploding-def? (:source-form entry))
                      (throw (ex-info "boom during analysis" {}))
                      (real-cached dict ignore-body ns source-file lang entry accessor-summaries form-opts)))]
      (let [results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                         {:remove-context true})
            exception-result (some #(when (= :expression (:phase %)) %) results)
            later-mismatch (some #(when (= 'skeptic.test-examples.basics/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (some? exception-result))
        (is (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
               (:enclosing-form exception-result)))
        (is (= "boom during analysis" (:exception-message exception-result)))
        (is (some? later-mismatch))))))

(deftest check-ns-localizes-lazy-expression-exceptions-and-continues
  ;; Realization-time (lazy) exception localization. check-resolved-form returns
  ;; a lazy seq; redef it to throw on realization for the exploding def, and
  ;; assert the exception localizes to exactly that def while later forms check.
  (let [real-check-resolved-form sut/check-resolved-form]
    (with-redefs [sut/check-resolved-form (fn [dict ignore-body ns-sym source-file source-form analyzed opts]
                                            (if (exploding-def? source-form)
                                              (map (fn [_]
                                                     (throw (ex-info "boom during realization" {})))
                                                   [::explode])
                                              (real-check-resolved-form dict ignore-body ns-sym source-file source-form analyzed opts)))]
      (let [results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                         {:remove-context true})
            exception-results (filterv #(and (= :expression (:phase %))
                                             (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
                                                (:enclosing-form %)))
                                       results)
            exception-result (first exception-results)
            later-mismatch (some #(when (= 'skeptic.test-examples.basics/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (= 1 (count exception-results)))
        (is (= :expression (:phase exception-result)))
        (is (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
               (:enclosing-form exception-result)))
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (some? later-mismatch))))))
