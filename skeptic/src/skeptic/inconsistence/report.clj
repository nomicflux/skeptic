(ns skeptic.inconsistence.report
  (:require [schema.core :as s]
            [skeptic.analysis.cast :as acast]
            [skeptic.analysis.cast.result :as cast-result]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.colours :as colours]
            [skeptic.inconsistence.display :as disp]
            [skeptic.inconsistence.mismatch :as mm]
            [skeptic.inconsistence.path :as pth]
            [skeptic.provenance :as prov]
            [clojure.string :as str]))

(declare cast-result->message
         primary-actionable-output-leaf)

(s/defn focused-input-expr :- s/Any
  [report :- s/Any]
  (let [{:keys [focuses blame]} report]
    (if (= 1 (count focuses))
      (first focuses)
      blame)))

(s/defn input-summary-header :- s/Str
  [report :- s/Any]
  (let [arg (focused-input-expr report)
        {:keys [blame]} report]
    (format "%s\n\tin\n\n%s\nhas inferred type incompatible with the expected type:"
            (colours/magenta (disp/ppr-str arg) true)
            (colours/magenta (disp/ppr-str blame)))))

(s/defn combine-summary-lines :- s/Str
  [header :- s/Str
   label :- s/Str
   detail-lines :- [s/Str]]
  (str header
       "\n\n"
       label
       ":\n\n\t- "
       (str/join "\n\t- "
                 (map #(colours/yellow (str/replace % "\n" "\n\t  ")) detail-lines))))

(defn- exception-detail-lines
  [{:keys [exception-class declaration-slot rejected-schema]}]
  (remove nil?
          [(when exception-class
             (str "Exception class: " exception-class))
           (when declaration-slot
             (str "Declaration slot: " (pr-str declaration-slot)))
           (when (some? rejected-schema)
             (str "Rejected schema: " (disp/ppr-str rejected-schema)))]))

(s/defn exception-error-summary :- s/Str
  [report :- s/Any]
  (let [{:keys [phase blame exception-message]} report
        subject (case phase
                  :declaration (format "declared schema for %s" (pr-str blame))
                  :read (format "namespace input %s" (pr-str blame))
                  (format "expression %s" (colours/magenta (disp/ppr-str blame) true)))
        detail-lines (exception-detail-lines report)
        detail-block (when (seq detail-lines)
                       (str "\n\n"
                            (str/join "\n"
                                      (map colours/yellow detail-lines))))
        skip-text (case phase
                    :declaration "Skeptic skipped this declaration and continued with the rest of the namespace."
                    :read "Skeptic localized this namespace read failure instead of aborting the namespace at the top level."
                    "Skeptic skipped this expression and continued with the rest of the namespace.")]
    (format "Skeptic hit an exception while checking %s.\n\n%s%s\n\n%s"
            subject
            (colours/yellow exception-message)
            (or detail-block "")
            skip-text)))

(s/defn report-ctx :- s/Any
  [report :- s/Any]
  {:expr (:blame report)
   :arg (focused-input-expr report)})

(s/defn output-focus :- s/Any
  [report :- s/Any]
  (or (some-> (primary-actionable-output-leaf report)
              :path
              pth/render-visible-path)
      (:arg (report-ctx report))))

(s/defn output-focus-text :- s/Str
  [focus :- s/Any]
  (if (string? focus)
    focus
    (disp/ppr-str focus)))

(s/defn output-summary-headline :- s/Str
  [report :- s/Any
   message :- s/Str]
  (let [{:keys [expr]} (report-ctx report)
        focus (output-focus report)]
    (if (= focus expr)
      (format "%s\n%s"
              (colours/magenta (disp/ppr-str expr) true)
              message)
      (format "%s\n\tin\n\n%s\n%s"
              (colours/magenta (output-focus-text focus) true)
              (colours/magenta (disp/ppr-str expr))
              message))))

(s/defn output-summary-message :- s/Str
  ([report :- s/Any]
   (output-summary-message report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [{:keys [cast-summary actual-type expected-type]} report
         actual-type (or (:actual-type cast-summary) actual-type)
         expected-type (or (:expected-type cast-summary) expected-type)]
     (format "%s\n\n%s\n\nbut the declared return type expects:\n\n%s"
             (output-summary-headline report "has inferred output type:")
             (colours/yellow (disp/describe-type-block actual-type opts))
             (colours/yellow (disp/describe-type-block expected-type opts))))))

(s/defn report-cast-leaves :- [s/Any]
  [report :- s/Any]
  (vec (:cast-diagnostics report)))

(s/defn visible-structural-leaf? :- s/Bool
  [cast-result :- s/Any]
  (boolean (some-> (:path cast-result)
                   pth/visible-path
                   seq)))

(s/defn dynamic-display-type? :- s/Bool
  [type :- ats/SemanticType]
  (let [type (some-> type ato/normalize)]
    (or (nil? type)
        (at/dyn-type? type))))

(s/defn actionable-output-leaf? :- s/Bool
  [cast-result :- s/Any]
  (or (visible-structural-leaf? cast-result)
      (not (dynamic-display-type? (:actual-type cast-result)))))

(s/defn ordered-output-leaves :- [s/Any]
  [report :- s/Any]
  (->> (report-cast-leaves report)
       (map-indexed (fn [idx leaf]
                      {:idx idx
                       :leaf leaf}))
       (sort-by (fn [{:keys [idx leaf]}]
                  [(if (visible-structural-leaf? leaf) 0 1)
                   (if (and (not (visible-structural-leaf? leaf))
                            (dynamic-display-type? (:actual-type leaf)))
                     1
                     0)
                   idx]))
       (mapv :leaf)))

(s/defn primary-actionable-output-leaf :- (s/maybe s/Any)
  [report :- s/Any]
  (first (filter actionable-output-leaf?
                 (ordered-output-leaves report))))

(s/defn output-declared-expected-type :- ats/SemanticType
  [report :- s/Any]
  (let [{:keys [cast-summary expected-type]} report]
    (or (:expected-type cast-summary)
        (some-> (primary-actionable-output-leaf report) :expected-type)
        expected-type)))

(s/defn output-leaf-summary-message :- s/Str
  ([report :- s/Any]
   (output-leaf-summary-message report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [expected-type (output-declared-expected-type report)]
     (format "%s\n\nDeclared return type expects:\n\n%s"
             (output-summary-headline report
                                      "has an output mismatch against the declared return type.")
             (colours/yellow (disp/describe-type-block expected-type opts))))))

(defn- augment-detail-lines-with-union-alternatives
  ([leaves detail-lines]
   (augment-detail-lines-with-union-alternatives leaves detail-lines {}))
  ([leaves detail-lines opts]
   (if-let [u (pth/union-alternatives-line leaves opts)]
     (conj detail-lines u)
     detail-lines)))

(s/defn rebuilt-leaf-errors :- [s/Str]
  ([report :- s/Any]
   (rebuilt-leaf-errors report {}))
  ([report :- s/Any
    opts :- s/Any]
   (->> (report-cast-leaves report)
        (map #(cast-result->message (report-ctx report) % opts))
        distinct
        vec)))

(s/defn input-leaf-errors :- [s/Str]
  ([report :- s/Any]
   (input-leaf-errors report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [errors (:errors report)]
     (if (seq errors)
       (vec errors)
       (rebuilt-leaf-errors report opts)))))

(s/defn grouped-input-summary? :- s/Any
  [report :- s/Any
   leaf-errors :- [s/Str]]
  (or (seq (:focuses report))
      (not= 1 (count leaf-errors))))

(s/defn summarize-errors :- [s/Str]
  ([report :- s/Any]
   (summarize-errors report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [report-kind (:report-kind report)
         cast-diagnostics (:cast-diagnostics report)]
     (case report-kind
       :exception
       [(exception-error-summary report)]

       :output
       (let [ordered-leaves (ordered-output-leaves report)
             detail-lines (->> ordered-leaves
                               (keep #(pth/detail-line :output % opts))
                               distinct
                               vec)
             detail-lines (augment-detail-lines-with-union-alternatives ordered-leaves
                                                                         detail-lines
                                                                         opts)
             summary (if (primary-actionable-output-leaf report)
                       (output-leaf-summary-message report opts)
                       (output-summary-message report opts))]
         [(if (seq detail-lines)
            (combine-summary-lines summary "Problem fields" detail-lines)
            summary)])

       :input
       (let [detail-lines (->> cast-diagnostics
                               (keep #(pth/detail-line :input % opts))
                               distinct
                               vec)
             detail-lines (augment-detail-lines-with-union-alternatives cast-diagnostics
                                                                        detail-lines
                                                                        opts)
             leaf-errors (input-leaf-errors report opts)]
         (cond
           (and (seq detail-lines)
                (grouped-input-summary? report leaf-errors))
           [(combine-summary-lines (input-summary-header report) "Problems" detail-lines)]

           (seq leaf-errors)
           [(first leaf-errors)]

           :else
           []))

       (rebuilt-leaf-errors report opts)))))

(s/defn display-cast :- (s/maybe s/Any)
  ([report :- s/Any]
   (display-cast report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [{:keys [report-kind rule actual-type expected-type cast-summary cast-diagnostics]} report]
     (when-not (= :exception report-kind)
       (let [primary (first cast-diagnostics)
             rule (or (:rule cast-summary) rule (:rule primary))
             actual-type (or (:actual-type cast-summary) actual-type (:actual-type primary))
             expected-type (or (:expected-type cast-summary) expected-type (:expected-type primary))]
         (when (or actual-type expected-type)
           (when (nil? actual-type)
             (throw (ex-info "Report cast display missing actual type"
                             {:missing-field :actual-type
                              :rule rule
                              :cast-summary cast-summary
                              :cast-diagnostics cast-diagnostics})))
           (when (nil? expected-type)
             (throw (ex-info "Report cast display missing expected type"
                             {:missing-field :expected-type
                              :rule rule
                              :cast-summary cast-summary
                              :cast-diagnostics cast-diagnostics})))
           {:rule rule
            :rule-text (some-> rule name)
            :actual-type actual-type
            :expected-type expected-type
            :actual-type-text (disp/describe-type-block actual-type opts)
            :expected-type-text (disp/describe-type-block expected-type opts)}))))))

(s/defn report-summary :- s/Any
  ([report :- s/Any]
   (report-summary report {}))
  ([report :- s/Any
    opts :- s/Any]
   (let [{:keys [location blame-side blame-polarity source-expression blame
                 focus-sources focuses enclosing-form expanded-expression
                 phase exception-class declaration-slot rejected-schema]} report]
     (merge {:location location
             :report-kind (:report-kind report)
             :phase phase
             :exception-class exception-class
             :declaration-slot declaration-slot
             :rejected-schema rejected-schema
             :blame-side blame-side
             :blame-polarity blame-polarity
             :source-expression source-expression
             :blame blame
             :focus-sources focus-sources
             :focuses focuses
             :enclosing-form enclosing-form
             :expanded-expression expanded-expression
             :errors (summarize-errors report opts)}
            (or (if (= :output (:report-kind report))
                  (let [root-sum (:cast-summary report)
                        selected (or (primary-actionable-output-leaf report) root-sum)
                        display-source (merge root-sum selected)
                        base (display-cast (assoc report :cast-summary display-source) opts)]
                    (if (and base root-sum)
                      (let [et (:expected-type root-sum)]
                        (merge base
                               {:expected-type et
                                :expected-type-text (disp/describe-type-block et opts)}))
                      base))
                  (display-cast report opts))
                {})))))

(s/defn cast-result->message :- s/Str
  ([ctx :- s/Any
    diagnostic :- s/Any]
   (cast-result->message ctx diagnostic {}))
  ([ctx :- s/Any
    diagnostic :- s/Any
    opts :- s/Any]
   (let [actual-type   (:actual-type diagnostic)
         expected-type (:expected-type diagnostic)
         message (case (:reason diagnostic)
                   :is-tamper
                   (format "%s\n\tin\n\n%s\nattempts to inspect a sealed value:\n\n%s"
                           (colours/magenta (disp/ppr-str (:arg ctx)) true)
                           (colours/magenta (disp/ppr-str (:expr ctx)))
                           (colours/yellow (disp/describe-type actual-type opts)))

                   :nu-tamper
                   (format "%s\n\tin\n\n%s\nattempts to move a sealed value out of scope:\n\n%s"
                           (colours/magenta (disp/ppr-str (:arg ctx)) true)
                           (colours/magenta (disp/ppr-str (:expr ctx)))
                           (colours/yellow (disp/describe-type actual-type opts)))

                   :nullable-source
                   (mm/mismatched-nullable-msg ctx actual-type expected-type)

                   :missing-key
                   (format "%s\n\tin\n\n%s\n%s"
                           (colours/magenta (disp/ppr-str (:arg ctx)) true)
                           (colours/magenta (disp/ppr-str (:expr ctx)))
                           (colours/yellow (pth/missing-detail (:path diagnostic)
                                                               (:expected-key diagnostic)
                                                               opts)))

                   :nullable-key
                   (format "%s\n\tin\n\n%s\n%s"
                           (colours/magenta (disp/ppr-str (:arg ctx)) true)
                           (colours/magenta (disp/ppr-str (:expr ctx)))
                           (colours/yellow (pth/nullable-detail (:path diagnostic)
                                                                (:actual-key diagnostic)
                                                                (:expected-key diagnostic)
                                                                opts)))

                   :unexpected-key
                   (format "%s\n\tin\n\n%s\n%s"
                           (colours/magenta (disp/ppr-str (:arg ctx)) true)
                           (colours/magenta (disp/ppr-str (:expr ctx)))
                           (colours/yellow (pth/unexpected-detail :input
                                                                  (:path diagnostic)
                                                                  (:actual-key diagnostic)
                                                                  opts)))

                   (if (and (at/ground-type? actual-type)
                            (at/ground-type? expected-type)
                            (not= actual-type expected-type))
                     (mm/mismatched-ground-type-msg ctx actual-type expected-type)
                     (mm/mismatched-schema-msg ctx actual-type expected-type)))]
     (if (contains? #{:missing-key :nullable-key :unexpected-key} (:reason diagnostic))
       message
       (pth/with-path-detail message diagnostic)))))

(s/defn cast-report-metadata :- s/Any
  [raw-cast-result :- csch/CastResult]
  (let [summary  (cast-result/root-summary raw-cast-result)
        leaves   (cast-result/leaf-diagnostics raw-cast-result)
        primary  (cast-result/primary-diagnostic raw-cast-result)]
    {:cast-summary     summary
     :cast-diagnostics leaves
     :blame-side       (:blame-side primary)
     :blame-polarity   (:blame-polarity primary)
     :rule             (:rule primary)
     :expected-type    (:expected-type primary)
     :actual-type      (:actual-type primary)}))

(s/defn cast-report :- s/Any
  ([ctx :- s/Any
    expected :- ats/SemanticType
    actual :- ats/SemanticType]
   (cast-report ctx expected actual {}))
  ([ctx :- s/Any
    expected :- ats/SemanticType
    actual :- ats/SemanticType
    opts :- s/Any]
   (let [expected-type (ato/normalize expected)
         actual-type (ato/normalize actual)
         source (prov/source (prov/of actual-type))
         raw (acast/check-cast actual-type expected-type)]
     (if (:ok? raw)
       (let [summary (cast-result/root-summary raw)]
         {:ok? true
          :errors []
          :source source
          :cast-summary     summary
          :cast-diagnostics []
          :blame-side :none
          :blame-polarity :none
          :rule (:rule summary)
          :expected-type (:expected-type summary)
          :actual-type (:actual-type summary)})
       (let [metadata (cast-report-metadata raw)
             errors (->> (:cast-diagnostics metadata)
                         (map #(cast-result->message ctx % opts))
                         distinct
                         vec)]
         (merge {:ok? false :source source :errors errors} metadata))))))

(s/defn output-cast-report :- s/Any
  ([ctx :- s/Any
    expected :- ats/SemanticType
    actual :- ats/SemanticType]
   (output-cast-report ctx expected actual {}))
  ([ctx :- s/Any
    expected :- ats/SemanticType
    actual :- ats/SemanticType
    opts :- s/Any]
   (let [expected-type (ato/normalize expected)
         actual-type (ato/normalize actual)
         source (prov/source (prov/of actual-type))
         raw (acast/check-cast actual-type expected-type)]
     (if (:ok? raw)
       (let [summary (cast-result/root-summary raw)]
         {:ok? true
          :errors []
          :source source
          :cast-summary     summary
          :cast-diagnostics []
          :blame-side :none
          :blame-polarity :none
          :rule (:rule summary)
          :expected-type (:expected-type summary)
          :actual-type (:actual-type summary)})
       (merge {:ok? false
               :source source
               :errors [(mm/mismatched-output-schema-msg ctx actual-type expected-type opts)]}
              (cast-report-metadata raw))))))
