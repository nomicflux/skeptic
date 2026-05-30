(ns skeptic.checking.pipeline.reporting-phase-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.provenance :as prov]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest output-mismatch-renders-canonical-map-types
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                              (:enclosing-form %))
                        %)
                     results)
        error (first (:errors result))]
    (is (some? result))
    (is (.contains error "{:name Keyword, :nickname (maybe Str)}"))
    (is (.contains error "skeptic.static-call-examples/UserDesc"))
    (is (not (.contains error "\":name : Keyword\"")))))

(deftest output-summary-highlights-path-or-drops-redundant-self-context
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        count-error (-> count-result inrep/report-summary :errors first ps/strip-ansi)
        rebuilt-error (-> rebuilt-user-result inrep/report-summary :errors first ps/strip-ansi)]
    (is (re-find #"(?s)^\(get counts :count \"zero\"\)\s+has an output mismatch against the declared return type\." count-error))
    (is (not (re-find #"(?s)^\(get counts :count \"zero\"\)\s+\tin\s+\(get counts :count \"zero\"\)" count-error)))
    (is (re-find #"\(union (Int Str|Str Int)\) but expected Int" count-error))
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \(get user :nickname\)\}" rebuilt-error))
    (is (str/includes? rebuilt-error "[:name] has Keyword but expected Str"))))

(deftest nested-output-mismatch-renders-field-paths
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                              (:enclosing-form %))
                        %)
                     results)]
    (is (some? result))
    (is (some #(str/includes? % "declared return type") (:errors result)))
    (is (= 1 (count (:errors result))))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors result)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> result :cast-diagnostics first :path)))))

(deftest namespace-dict-surfaces-schema-source-for-schema-declared-syms
  (let [{:keys [provenance]} (sut/check-namespace (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                                  'skeptic.static-call-examples
                                                  ps/static-call-examples-file
                                                  {:remove-context true})]
    (is (= :schema (prov/source (get provenance 'skeptic.static-call-examples/bad-rebuilt-user))))
    (is (= :inferred (prov/source (get provenance 'skeptic.static-call-examples/nested-multi-step-failure))))))

(defn- raw-ast-seq
  "Walk a raw (un-annotated) projected AST node, yielding every nested node map
   reachable through :children slots. Avoids the annotate-only accessors so it
   can inspect a freshly-received :clj-state AST."
  [node]
  (when (and (map? node) (:op node))
    (cons node
          (mapcat (fn [k]
                    (let [v (get node k)]
                      (cond
                        (and (map? v) (:op v)) (raw-ast-seq v)
                        (and (vector? v) (every? map? v)) (mapcat raw-ast-seq v)
                        :else nil)))
                  (:children node)))))

(deftest ^:probe worker-projected-form-metadata-survives-the-wire
  ;; PROBE for #4: prove whether :form metadata (:line/:source) attached by the
  ;; worker's source-logging reader survives the worker->host nREPL edn wire.
  ;; Inspects the RAW received AST in :clj-state (real worker, production path).
  (let [ps (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
        entries (some-> (:clj-state ps) (get ps/static-call-examples-file) :entries)
        ;; The raw top-level def form the worker read with the source-logging
        ;; reader — on the worker side this carries :source/:line. Check the
        ;; received :source-form AND a real user :invoke node inside its :ast.
        nested-entry (some #(when (and (seq? (:source-form %))
                                       (= 'nested-multi-step-failure
                                          (second (:source-form %))))
                              %)
                           entries)
        user-invoke (some #(when (and (= :invoke (:op %))
                                      (seq? (:form %))
                                      (not= 'clojure.core/in-ns (first (:form %))))
                             %)
                          (raw-ast-seq (:ast nested-entry)))]
    (is (some? nested-entry) "expected the nested-multi-step-failure entry")
    (is (some? user-invoke) "expected a real user :invoke node in its :ast")
    (is (seq (meta (:source-form nested-entry)))
        (str ":source-form lost its worker-attached meta on the wire. meta="
             (pr-str (meta (:source-form nested-entry)))))
    (is (seq (meta (:form user-invoke)))
        (str "received user :invoke :form carries NO metadata — wire stripped it. form="
             (pr-str (:form user-invoke)) " meta=" (pr-str (meta (:form user-invoke)))))))

(deftest check-results-carry-cast-metadata
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        nested-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                      (:enclosing-form %))
                               %)
                            results)
        output-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                      (:enclosing-form %))
                               %)
                            results)]
    (doseq [result [nested-result output-result]]
      (is (some? result))
      (is (= :term (:blame-side result)))
      (is (= :positive (:blame-polarity result)))
      (is (keyword? (:rule result)))
      (is (some? (:expected-type result)))
      (is (some? (:actual-type result)))
      (is (map? (:cast-summary result)))
      (is (seq (:cast-diagnostics result))))
    (is (= "(nested-multi-step-takes-str (get (nested-multi-step-g) :value))"
           (:source-expression nested-result)))
    (is (= {:file "src/skeptic/static_call_examples.clj"
            :line 88
            :column 3}
           (select-keys (:location nested-result) [:file :line :column])))
    (is (= ["(get (nested-multi-step-g) :value)"]
           (:focus-sources nested-result)))))
