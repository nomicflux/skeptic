(ns skeptic.core-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.checking.pipeline :as checking]
            [skeptic.file :as file]
            [skeptic.analysis.types :as at]
            [skeptic.core :as sut]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.output.text :as text]
            [skeptic.provenance :as prov])
  (:import [java.io File]
           [java.nio.file Files]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(def ui-internal-markers
  [":skeptic.analysis.types/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(defn strip-ansi
  [text]
  (str/replace (str text) #"\u001B\[[0-9;]*m" ""))

(def report-summary
  {:location {:file "src/example.clj"
              :line 12
              :column 3
              :source :inferred}
   :blame-side :term
   :blame-polarity :positive
   :rule :function
   :actual-type (ab/schema->type tp s/Int)
   :expected-type (ab/schema->type tp s/Str)
   :source-expression "(takes-str x)"
   :focus-sources ["x"]
   :enclosing-form 'example/takes-str
   :expanded-expression '(takes-str x)})

(deftest report-fields-hide-detail-fields-when-not-verbose
  (let [fields (text/report-fields report-summary)]
    (is (some #{["Location: \t\t" "src/example.clj:12:3 [source: inferred]"]} fields))
    (is (= "context( value )"
           (some->> fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (not (some #{["Cast rule: \t\t" "function"]} fields)))
    (is (not (some #{["Actual type: \t\t" "Int"]} fields)))
    (is (not (some #{["Expected type: \t" "Str"]} fields)))
    (is (not (some #{["Expression: \t\t" "(takes-str x)"]} fields)))
    (is (not (some #{["Affected input: \t" "x"]} fields)))
    (is (not (some #{["In enclosing form: \t" "example/takes-str"]} fields)))
    (is (not (some #{["Analyzed expression: \t" "(takes-str x)"]} fields)))))

(deftest report-fields-include-detail-fields-when-verbose
  (let [fields (text/report-fields report-summary true)]
    (is (some #{["Location: \t\t" "src/example.clj:12:3 [source: inferred]"]} fields))
    (is (= "context( value )"
           (some->> fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (some #{["Cast rule: \t\t" "function"]} fields))
    (is (some #{["Actual type: \t\t" "Int"]} fields))
    (is (some #{["Expected type: \t" "Str"]} fields))
    (is (some #{["Expression: \t\t" "(takes-str x)"]} fields))
    (is (some #{["Affected input: \t" "x"]} fields))
    (is (some #{["In enclosing form: \t" "example/takes-str"]} fields))
    (is (some #{["Analyzed expression: \t" "(takes-str x)"]} fields))))

(deftest report-fields-render-source-suffix-for-every-source-kind
  (doseq [src [:inferred :schema :malli-spec :native :type-override]]
    (let [summary (assoc report-summary
                         :location {:file "f.clj" :line 1 :column 2 :source src})
          fields (text/report-fields summary)
          location-value (some (fn [[label value]]
                                 (when (= "Location: \t\t" label) value))
                               fields)]
      (is (str/includes? (or location-value "")
                         (str "[source: " (name src) "]"))
          (str "source kind " src " must render unconditionally")))))

(deftest report-fields-render-user-friendly-blame-for-context-and-global-cases
  (let [context-fields (text/report-fields {:blame-side :context
                                           :blame-polarity :negative})
        missing-fields (text/report-fields {:blame-side :none
                                           :blame-polarity :none})
        global-fields (text/report-fields {:blame-side :global
                                          :blame-polarity :global})]
    (is (= "context( value )"
           (some->> context-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (= "<missing>"
           (some->> missing-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (= "scope escape"
           (some->> global-fields
                    (some (fn [[label value]]
                            (when (= "Blame: \t\t\t" label)
                              (strip-ansi value)))))))
    (is (some->> context-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (and (str/includes? value "\u001B[37;2mvalue")
                                (str/includes? value "\u001B[37;1mcontext")
                                (str/includes? value "\u001B[37;1m( ")
                                (str/includes? value "\u001B[37;1m )")))))))
    (is (some->> (text/report-fields report-summary)
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (and (str/includes? value "\u001B[37;1mvalue")
                                (str/includes? value "\u001B[37;2mcontext")
                                (str/includes? value "\u001B[37;2m( ")
                                (str/includes? value "\u001B[37;2m )")))))))
    (is (some->> global-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (str/includes? value "\u001B[37;1mscope escape"))))))
    (is (some->> missing-fields
                 (some (fn [[label value]]
                         (when (= "Blame: \t\t\t" label)
                           (str/includes? value "\u001B[37m<missing>"))))))))

(deftest report-fields-render-semantic-polymorphic-types-in-verbose-mode
  (let [type-var (at/->TypeVarT tp 'X)
        fields (text/report-fields
                {:rule :generalize
                 :actual-type (at/->SealedDynT tp type-var)
                 :expected-type (at/->ForallT tp 'X type-var)
                 :source-expression "(poly x)"}
                true)]
    (is (some #{["Cast rule: \t\t" "generalize"]} fields))
    (is (some #{["Actual type: \t\t" "(sealed X)"]} fields))
    (is (some #{["Expected type: \t" "(forall X X)"]} fields))))

(deftest report-summary-collapses-output-into-single-entry
  (let [summary (inrep/report-summary
                 {:report-kind :output
                  :cast-summary {:actual-type (ab/schema->type tp {:name s/Keyword})
                                :expected-type (ab/schema->type tp {:name s/Str})}
                  :cast-diagnostics [{:reason :leaf-mismatch
                                  :rule :leaf-overlap
                                  :path [{:kind :map-key :key :name}]
                                  :actual-type (ab/schema->type tp s/Keyword)
                                  :expected-type (ab/schema->type tp s/Str)}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "declared return type")))
    (is (some-> summary :errors first (.contains "Problem fields:")))
    (is (some-> summary :errors first (.contains "[:name] has Keyword but expected Str")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-hides-internal-cast-branches
  (let [summary (inrep/report-summary
                 {:report-kind :output
                  :cast-summary {:actual-type (ab/schema->type tp {:b s/Int})
                                :expected-type (ab/schema->type tp {:b s/Int})}
                  :cast-diagnostics [{:reason :missing-key
                                  :path [{:kind :source-union-branch :index 1}
                                         {:kind :map-key :key :b}]}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "[:b] is missing")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-collapses-input-union-branches
  (let [summary (inrep/report-summary
                 {:report-kind :input
                  :blame '(takes-either-branch :bad)
                  :focuses [:bad]
                  :errors ["err-1" "err-2"]
                  :cast-diagnostics [{:reason :leaf-mismatch
                                  :actual-type (ab/schema->type tp s/Keyword)
                                  :expected-type (ab/schema->type tp s/Int)
                                  :path [{:kind :target-union-branch :index 0}]}
                                 {:reason :leaf-mismatch
                                  :actual-type (ab/schema->type tp s/Keyword)
                                  :expected-type (ab/schema->type tp s/Str)
                                  :path [{:kind :target-union-branch :index 1}]}]})]
    (is (= 1 (count (:errors summary))))
    (is (some-> summary :errors first (.contains "expected type")))
    (is (some-> summary :errors first (.contains "Keyword does not match any of: Int, Str")))
    (assert-no-ui-internals (first (:errors summary)))))

(deftest report-summary-and-fields-sanitize-placeholder-heavy-types
  (let [placeholder (at/->PlaceholderT tp 'clj-threals.threals/Threal)
        summary (inrep/report-summary
                 {:report-kind :input
                  :blame '(simplify gt_fn [g r b])
                  :focuses ['[g r b]]
                  :cast-summary {:rule :vector
                                :actual-type (ab/schema->type tp [s/Any])
                                :expected-type (at/->VectorT tp [(at/->SetT tp #{(at/->VectorT tp [placeholder placeholder placeholder]
                                                                             false)}
                                                                       false)]
                                                           true)}
                  :cast-diagnostics [{:reason :leaf-mismatch
                                  :actual-type (ab/schema->type tp s/Any)
                                  :expected-type placeholder
                                  :path [{:kind :vector-index :index 0}]}]})
        fields (text/report-fields summary)
        printed (str/join "\n"
                          (concat (map (fn [[label value]]
                                         (str label value))
                                       fields)
                                  (:errors summary)))]
    (is (not-any? (fn [[label _]]
                    (#{"Actual type: \t\t" "Expected type: \t"} label))
                  fields))
    (let [verbose-fields (text/report-fields summary true)]
      (is (some #{["Actual type: \t\t" "[Any]"]} verbose-fields))
      (is (some (fn [[label value]]
                  (and (= "Expected type: \t" label)
                       (str/includes? value "Threal")))
                verbose-fields)))
    (assert-no-ui-internals printed)))

(deftest report-fields-prefer-top-level-cast-metadata-in-verbose-mode
  (let [fields (text/report-fields
                (inrep/report-summary
                 {:rule :leaf-overlap
                  :actual-type (ab/schema->type tp s/Keyword)
                  :expected-type (ab/schema->type tp s/Int)
                  :cast-summary {:rule :target-union
                                :actual-type (ab/schema->type tp s/Keyword)
                                :expected-type (ab/schema->type tp (s/either s/Int s/Str))}
                  :source-expression "(takes-either-branch :bad)"
                  :errors ["err"]
                  :cast-diagnostics []})
                true)]
    (is (some #{["Cast rule: \t\t" "target-union"]} fields))
    (is (some #{["Actual type: \t\t" "Keyword"]} fields))
    (is (some (fn [[label value]]
                (and (= "Expected type: \t" label)
                     (.contains value "Int")
                     (.contains value "Str")))
              fields))))

(deftest output-report-fields-prefer-actionable-leaf-metadata-in-verbose-mode
  (let [actual-result (at/->ConditionalT tp [[integer? (ab/schema->type tp s/Int)]
                                          [string? (ab/schema->type tp s/Str)]])
        summary (inrep/report-summary
                 {:report-kind :output
                  :rule :source-union
                  :actual-type actual-result
                  :expected-type (ab/schema->type tp s/Keyword)
                  :cast-summary {:rule :source-union
                                :actual-type actual-result
                                :expected-type (ab/schema->type tp s/Keyword)}
                  :cast-diagnostics [{:reason :source-branch-failed
                                  :rule :source-union
                                  :actual-type actual-result
                                  :expected-type (ab/schema->type tp s/Keyword)
                                  :path []}]})
        fields (text/report-fields summary true)]
    (is (some #{["Cast rule: \t\t" "source-union"]} fields))
    (is (some (fn [[label value]]
                (and (= "Actual type: \t\t" label)
                     (str/includes? value "(conditional Int Str)")))
              fields))
    (is (some (fn [[label value]]
                (and (= "Expected type: \t" label)
                     (= "Keyword" value)))
              fields))))

(deftest exception-report-fields-skip-blame-and-show-phase
  (let [fields (text/report-fields
                {:report-kind :exception
                 :phase :declaration
                 :location {:file "test.clj" :line 7 :source :schema}
                 :exception-class 'clojure.lang.ExceptionInfo
                 :declaration-slot :output
                 :rejected-schema #"^[a-z]+$"
                 :blame 'example/bad
                 :source-expression "(bad)"
                 :errors ["oops"]}
                true)]
    (is (some #{["Location: \t\t" "test.clj:7 [source: schema]"]} fields))
    (is (some #{["Phase: \t\t\t" "declaration"]} fields))
    (is (some #{["Exception class: \t" "clojure.lang.ExceptionInfo"]} fields))
    (is (some #{["Schema slot: \t\t" ":output"]} fields))
    (is (some #{["Rejected schema: \t" "#\"^[a-z]+$\""]} fields))
    (is (some #{["Expression: \t\t" "(bad)"]} fields))
    (is (not-any? #(= "Blame: \t\t\t" (first %)) fields))))

(deftest check-project-localizes-lazy-form-exceptions
  (require 'skeptic.check-project-best-effort-examples)
  (let [real-check-resolved-form checking/check-resolved-form
        source-file (java.io.File. "test/skeptic/check_project_best_effort_examples.clj")]
    (with-redefs [checking/check-resolved-form
                  (fn [dict ignore-body ns-sym source-file source-form analyzed opts]
                    (if (= 'exploding-form (second source-form))
                      (map (fn [_]
                             (throw (ex-info "boom during realization" {})))
                           [::explode])
                      (real-check-resolved-form dict ignore-body ns-sym source-file source-form analyzed opts)))]
      (let [{:keys [results]} (checking/check-namespace {:remove-context true}
                                                        'skeptic.check-project-best-effort-examples
                                                        source-file)
            exception-result (some #(when (= :expression (:phase %)) %) results)
            mismatch-result (some #(when (= 'skeptic.check-project-best-effort-examples/later-mismatch
                                            (:enclosing-form %))
                                     %)
                                  results)]
        (is (some? exception-result)
            "The exploding form should produce a localized expression exception")
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (= 'skeptic.check-project-best-effort-examples/exploding-form
               (:enclosing-form exception-result)))
        (is (some? mismatch-result)
            "The later mismatch should still be found after the exception")
        (is (seq (:errors mismatch-result))
            "The later mismatch should have actual errors")))))

(deftest check-project-ignores-unrelated-discovery-failures-for-requested-namespace
  (let [source-file (java.io.File. "test/example.clj")
        checked (atom [])]
    (with-redefs [file/discover-clojure-files
                  (fn [_]
                    {:files [source-file]
                     :failures [{:path "missing"
                                 :exception (ex-info "unreadable" {})}]})
                  file/ns-for-clojure-file
                  (fn [file]
                    ['example.ns file])
                  checking/check-namespace
                  (fn [_opts ns _source-file]
                    (swap! checked conj ns)
                    {:results [] :provenance {}})]
      (is (= 0 (sut/check-project {:namespace "example.ns"} "." ".")))
      (is (= ['example.ns] @checked)))))

(deftest check-project-blocks-when-discovery-failure-prevents-requested-coverage
  (let [checked? (atom false)]
    (with-redefs [file/discover-clojure-files
                  (fn [_]
                    {:files []
                     :failures [{:path "missing"
                                 :exception (ex-info "unreadable" {})}]})
                  checking/check-namespace
                  (fn [& _]
                    (reset! checked? true)
                    {:results [] :provenance {}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Couldn't get namespaces"
                            (sut/check-project {:namespace "example.ns"} "." ".")))
      (is (false? @checked?)))))

(deftest check-project-blocks-on-discovery-failure-for-full-coverage-run
  (let [source-file (java.io.File. "test/example.clj")
        checked? (atom false)]
    (with-redefs [file/discover-clojure-files
                  (fn [_]
                    {:files [source-file]
                     :failures [{:path "missing"
                                 :exception (ex-info "unreadable" {})}]})
                  file/ns-for-clojure-file
                  (fn [file]
                    ['example.ns file])
                  checking/check-namespace
                  (fn [& _]
                    (reset! checked? true)
                    {:results [] :provenance {}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Couldn't get namespaces"
                            (sut/check-project {} "." ".")))
      (is (false? @checked?)))))

(defn- parse-jsonl [s]
  (->> (str/split-lines s)
       (remove str/blank?)
       (mapv #(json/read-str % :key-fn keyword))))

(deftest check-project-porcelain-clean-run-emits-run-summary
  (let [source-file (java.io.File. "test/example.clj")]
    (with-redefs [file/discover-clojure-files
                  (fn [_] {:files [source-file] :failures []})
                  file/ns-for-clojure-file
                  (fn [file] ['example.ns file])
                  checking/check-namespace (fn [& _] {:results [] :provenance {}})]
      (let [out (with-out-str
                  (is (= 0 (sut/check-project {:porcelain true} "." "."))))
            lines (parse-jsonl out)]
        (is (= 1 (count lines)) "exactly one run-summary line on clean run")
        (let [summary (first lines)]
          (is (= "run-summary" (:kind summary)))
          (is (false? (:errored summary)))
          (is (= 1 (:namespace_count summary))))))))

(deftest check-project-porcelain-emits-finding-per-result
  (let [source-file (java.io.File. "test/example.clj")
        fake-result {:report-kind :input
                     :location {:file "test/example.clj" :line 10 :column 1 :source :inferred}
                     :blame-side :term
                     :blame-polarity :positive
                     :blame '(+ 1 :x)
                     :errors ["Keyword is not compatible with Int"]
                     :cast-diagnostics []
                     :actual-type (at/->GroundT tp :keyword 'Keyword)
                     :expected-type (at/->GroundT tp :int 'Int)}]
    (with-redefs [file/discover-clojure-files
                  (fn [_] {:files [source-file] :failures []})
                  file/ns-for-clojure-file
                  (fn [file] ['example.ns file])
                  checking/check-namespace (fn [& _] {:results [fake-result] :provenance {}})
                  inrep/report-summary (fn [r] r)]
      (let [out (with-out-str
                  (is (= 1 (sut/check-project {:porcelain true} "." "."))))
            lines (parse-jsonl out)]
        (is (= 2 (count lines)) "one finding, one run-summary")
        (testing "finding record"
          (let [finding (first lines)]
            (is (= "finding" (:kind finding)))
            (is (= "example.ns" (:ns finding)))
            (is (= "input" (:report_kind finding)))
            (is (= "test/example.clj" (get-in finding [:location :file])))
            (is (= 10 (get-in finding [:location :line])))
            (is (= "inferred" (get-in finding [:location :source])))
            (is (= "Int" (get-in finding [:expected_type :name])))
            (is (= "Keyword" (get-in finding [:actual_type :name])))))
        (testing "run-summary has errored=true"
          (let [summary (last lines)]
            (is (true? (:errored summary)))
            (is (= 1 (:finding_count summary)))))))))

(deftest check-project-porcelain-emits-ns-discovery-warning
  (let [source-file (java.io.File. "test/example.clj")]
    (with-redefs [file/discover-clojure-files
                  (fn [_]
                    {:files [source-file]
                     :failures [{:path "missing"
                                 :exception (ex-info "unreadable" {})}]})
                  file/ns-for-clojure-file
                  (fn [file] ['example.ns file])
                  checking/check-namespace (fn [& _] {:results [] :provenance {}})]
      (let [out (with-out-str
                  (sut/check-project {:porcelain true :namespace "example.ns"}
                                     "." "."))
            lines (parse-jsonl out)]
        (is (= 2 (count lines)))
        (is (= "ns-discovery-warning" (:kind (first lines))))
        (is (= "missing" (:path (first lines))))
        (is (= "run-summary" (:kind (last lines))))))))

(deftest check-project-excludes-files-matching-config-patterns
  (let [tmp (.toFile (Files/createTempDirectory "skeptic-core-test"
                                                (into-array java.nio.file.attribute.FileAttribute [])))
        config-dir (File. tmp ".skeptic")
        kept-file (File. tmp "src/kept.clj")
        excluded-file (File. tmp "src/excluded.clj")
        checked (atom [])]
    (try
      (.mkdirs config-dir)
      (.mkdirs (File. tmp "src"))
      (.createNewFile kept-file)
      (.createNewFile excluded-file)
      (spit (File. config-dir "config.edn") "{:exclude-files [\"src/excluded.clj\"]}")
      (with-redefs [file/discover-clojure-files
                    (fn [_] {:files [kept-file excluded-file] :failures []})
                    file/ns-for-clojure-file
                    (fn [f] [(symbol (str "ns." (.getName f))) f])
                    checking/check-namespace
                    (fn [_opts ns _f]
                      (swap! checked conj ns)
                      {:results [] :provenance {}})]
        (sut/check-project {} (.getCanonicalPath tmp) "."))
      (is (= 1 (count @checked)))
      (is (= 'ns.kept.clj (first @checked)))
      (finally
        (doseq [f (reverse (file-seq tmp))]
          (.delete f))))))
