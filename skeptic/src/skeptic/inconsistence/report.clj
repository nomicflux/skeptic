(ns skeptic.inconsistence.report
  (:require [skeptic.analysis.cast :as acast]
            [skeptic.analysis.cast.result :as cast-result]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.colours :as colours]
            [skeptic.inconsistence.display :as disp]
            [skeptic.inconsistence.mismatch :as mm]
            [skeptic.inconsistence.path :as pth]
            [skeptic.provenance :as prov]
            [clojure.string :as str]))

(declare cast-result->message
         primary-actionable-output-leaf)

(defn focused-input-expr
  [{:keys [focuses blame]}]
  (if (= 1 (count focuses))
    (first focuses)
    blame))

(defn input-summary-header
  [{:keys [blame] :as report}]
  (let [arg (focused-input-expr report)]
    (format "%s\n\tin\n\n%s\nhas inferred type incompatible with the expected type:"
            (colours/magenta (disp/ppr-str arg) true)
            (colours/magenta (disp/ppr-str blame)))))

(defn combine-summary-lines
  [header label detail-lines]
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

(defn exception-error-summary
  [{:keys [phase blame exception-message] :as report}]
  (let [subject (case phase
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

(defn report-ctx
  [{:keys [blame] :as report}]
  {:expr blame
   :arg (focused-input-expr report)})

(defn output-focus
  [report]
  (or (some-> (primary-actionable-output-leaf report)
              :path
              pth/render-visible-path)
      (:arg (report-ctx report))))

(defn output-focus-text
  [focus]
  (if (string? focus)
    focus
    (disp/ppr-str focus)))

(defn output-summary-headline
  [report message]
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

(defn output-summary-message
  ([report]
   (output-summary-message report {}))
  ([{:keys [cast-summary actual-type expected-type] :as report} opts]
   (let [actual-type (or (:actual-type cast-summary) actual-type)
         expected-type (or (:expected-type cast-summary) expected-type)]
     (format "%s\n\n%s\n\nbut the declared return type expects:\n\n%s"
             (output-summary-headline report "has inferred output type:")
             (colours/yellow (disp/describe-type-block actual-type opts))
             (colours/yellow (disp/describe-type-block expected-type opts))))))

(defn report-cast-leaves
  [{:keys [cast-diagnostics]}]
  (vec cast-diagnostics))

(defn visible-structural-leaf?
  [cast-result]
  (boolean (some-> (:path cast-result)
                   pth/visible-path
                   seq)))

(defn dynamic-display-type?
  [type]
  (let [type (some-> type ato/normalize)]
    (or (nil? type)
        (at/dyn-type? type))))

(defn actionable-output-leaf?
  [cast-result]
  (or (visible-structural-leaf? cast-result)
      (not (dynamic-display-type? (:actual-type cast-result)))))

(defn ordered-output-leaves
  [report]
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

(defn primary-actionable-output-leaf
  [report]
  (first (filter actionable-output-leaf?
                 (ordered-output-leaves report))))

(defn output-declared-expected-type
  [{:keys [cast-summary expected-type] :as report}]
  (or (:expected-type cast-summary)
      (some-> (primary-actionable-output-leaf report) :expected-type)
      expected-type))

(defn output-leaf-summary-message
  ([report]
   (output-leaf-summary-message report {}))
  ([report opts]
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

(defn rebuilt-leaf-errors
  ([report]
   (rebuilt-leaf-errors report {}))
  ([report opts]
   (->> (report-cast-leaves report)
        (map #(cast-result->message (report-ctx report) % opts))
        distinct
        vec)))

(defn input-leaf-errors
  ([report]
   (input-leaf-errors report {}))
  ([{:keys [errors] :as report} opts]
   (if (seq errors)
     (vec errors)
     (rebuilt-leaf-errors report opts))))

(defn grouped-input-summary?
  [{:keys [focuses]} leaf-errors]
  (or (seq focuses)
      (not= 1 (count leaf-errors))))

(defn summarize-errors
  ([report]
   (summarize-errors report {}))
  ([{:keys [report-kind cast-diagnostics] :as report} opts]
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

     (rebuilt-leaf-errors report opts))))

(defn display-cast
  ([report]
   (display-cast report {}))
  ([{:keys [rule actual-type expected-type cast-summary]} opts]
   (when-not (= :exception (:report-kind cast-summary))
     (let [rule (or (:rule cast-summary) rule)
           actual-type (or (:actual-type cast-summary) actual-type)
           expected-type (or (:expected-type cast-summary) expected-type)]
       {:rule rule
        :rule-text (some-> rule name)
        :actual-type actual-type
        :expected-type expected-type
        :actual-type-text (disp/describe-type-block actual-type opts)
        :expected-type-text (disp/describe-type-block expected-type opts)}))))

(defn report-summary
  ([report]
   (report-summary report {}))
  ([{:keys [location blame-side blame-polarity source-expression blame
            focus-sources focuses enclosing-form expanded-expression
            phase exception-class declaration-slot rejected-schema]
     :as report}
    opts]
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
                      base (display-cast (assoc report :cast-summary selected) opts)]
                  (if (and base root-sum)
                    (let [et (:expected-type root-sum)]
                      (merge base
                             {:expected-type et
                              :expected-type-text (disp/describe-type-block et opts)}))
                    base))
                (display-cast report opts))
              {}))))

(defn cast-result->message
  ([ctx diagnostic]
   (cast-result->message ctx diagnostic {}))
  ([ctx diagnostic opts]
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

(defn cast-report-metadata
  [raw-cast-result]
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

(defn cast-report
  ([ctx expected actual]
   (cast-report ctx expected actual {}))
  ([ctx expected actual opts]
   (let [expected-type (ato/normalize-for-declared-type expected)
         actual-type (ato/normalize-for-declared-type actual)
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

(defn output-cast-report
  ([ctx expected actual]
   (output-cast-report ctx expected actual {}))
  ([ctx expected actual opts]
   (let [expected-type (ato/normalize-for-declared-type expected)
         actual-type (ato/normalize-for-declared-type actual)
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
