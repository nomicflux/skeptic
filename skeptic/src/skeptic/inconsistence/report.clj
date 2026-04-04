(ns skeptic.inconsistence.report
  (:require [skeptic.analysis.cast :as acast]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.colours :as colours]
            [skeptic.inconsistence.display :as disp]
            [skeptic.inconsistence.mismatch :as mm]
            [skeptic.inconsistence.path :as pth]
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
    (format "%s\n\tin\n\n%s\nhas incompatible schema:"
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
  [{:keys [cast-result actual-type expected-type] :as report}]
  (let [actual-type (or (some-> cast-result :source-type) actual-type)
        expected-type (or (some-> cast-result :target-type) expected-type)]
    (format "%s\n\n%s\n\nbut declared return schema is:\n\n%s"
            (output-summary-headline report "has output schema:")
            (colours/yellow (disp/describe-type-block actual-type))
            (colours/yellow (disp/describe-type-block expected-type)))))

(defn report-cast-leaves
  [{:keys [cast-result cast-results]}]
  (if (seq cast-results)
    (vec cast-results)
    (if cast-result
      (vec (pth/cast-leaf-results cast-result))
      [])))

(defn visible-structural-leaf?
  [cast-result]
  (boolean (some-> (:path cast-result)
                   pth/visible-path
                   seq)))

(defn dynamic-display-type?
  [type]
  (let [type (some-> type ato/normalize-type)]
    (or (nil? type)
        (at/dyn-type? type))))

(defn actionable-output-leaf?
  [cast-result]
  (or (visible-structural-leaf? cast-result)
      (not (dynamic-display-type? (:source-type cast-result)))))

(defn ordered-output-leaves
  [report]
  (->> (report-cast-leaves report)
       (map-indexed (fn [idx leaf]
                      {:idx idx
                       :leaf leaf}))
       (sort-by (fn [{:keys [idx leaf]}]
                  [(if (visible-structural-leaf? leaf) 0 1)
                   (if (and (not (visible-structural-leaf? leaf))
                            (dynamic-display-type? (:source-type leaf)))
                     1
                     0)
                   idx]))
       (mapv :leaf)))

(defn primary-actionable-output-leaf
  [report]
  (first (filter actionable-output-leaf?
                 (ordered-output-leaves report))))

(defn output-leaf-summary-message
  [{:keys [expected-type cast-result] :as report}]
  (let [expected-type (or (some-> (primary-actionable-output-leaf report) :target-type)
                          (some-> cast-result :target-type)
                          expected-type)]
    (format "%s\n\nDeclared return schema:\n\n%s"
            (output-summary-headline report
                                     "has an output mismatch against the declared return schema.")
            (colours/yellow (disp/describe-type-block expected-type)))))

(defn rebuilt-leaf-errors
  [report]
  (->> (report-cast-leaves report)
       (map #(cast-result->message (report-ctx report) %))
       distinct
       vec))

(defn input-leaf-errors
  [{:keys [errors] :as report}]
  (if (seq errors)
    (vec errors)
    (rebuilt-leaf-errors report)))

(defn grouped-input-summary?
  [{:keys [focuses]} leaf-errors]
  (or (seq focuses)
      (not= 1 (count leaf-errors))))

(defn summarize-errors
  [{:keys [report-kind cast-results] :as report}]
  (case report-kind
    :output
    (let [ordered-leaves (ordered-output-leaves report)
          detail-lines (->> ordered-leaves
                            (keep #(pth/detail-line :output %))
                            distinct
                            vec)
          summary (if (primary-actionable-output-leaf report)
                    (output-leaf-summary-message report)
                    (output-summary-message report))]
      [(if (seq detail-lines)
         (combine-summary-lines summary "Problem fields" detail-lines)
         summary)])

    :input
    (let [detail-lines (->> cast-results
                            (keep #(pth/detail-line :input %))
                            distinct
                            vec)
          union-line (pth/union-alternatives-line cast-results)
          detail-lines (cond-> detail-lines
                         union-line (conj union-line))
          leaf-errors (input-leaf-errors report)]
      (cond
        (and (seq detail-lines)
             (grouped-input-summary? report leaf-errors))
        [(combine-summary-lines (input-summary-header report) "Problems" detail-lines)]

        (seq leaf-errors)
        [(first leaf-errors)]

        :else
        []))

    (rebuilt-leaf-errors report)))

(defn display-cast
  [{:keys [rule actual-type expected-type cast-result]}]
  (let [rule (or (:rule cast-result) rule)
        actual-type (or (:source-type cast-result) actual-type)
        expected-type (or (:target-type cast-result) expected-type)]
    {:rule rule
     :rule-text (some-> rule name)
     :actual-type actual-type
     :expected-type expected-type
     :actual-type-text (disp/describe-type-block actual-type)
     :expected-type-text (disp/describe-type-block expected-type)}))

(defn report-summary
  [{:keys [location blame-side blame-polarity source-expression blame
           focus-sources focuses enclosing-form expanded-expression]
    :as report}]
  (merge {:location location
          :blame-side blame-side
          :blame-polarity blame-polarity
          :source-expression source-expression
          :blame blame
          :focus-sources focus-sources
          :focuses focuses
          :enclosing-form enclosing-form
          :expanded-expression expanded-expression
          :errors (summarize-errors report)}
         (display-cast (if (= :output (:report-kind report))
                         (let [selected (or (primary-actionable-output-leaf report)
                                            (:cast-result report))]
                           (assoc report :cast-result selected))
                         report))))

(defn cast-result->message
  [ctx cast-result]
  (let [source-type (:source-type cast-result)
        target-type (:target-type cast-result)
        message (case (:reason cast-result)
                  :is-tamper
                  (format "%s\n\tin\n\n%s\nattempts to inspect a sealed value:\n\n%s"
                          (colours/magenta (disp/ppr-str (:arg ctx)) true)
                          (colours/magenta (disp/ppr-str (:expr ctx)))
                          (colours/yellow (disp/describe-type source-type)))

                  :nu-tamper
                  (format "%s\n\tin\n\n%s\nattempts to move a sealed value out of scope:\n\n%s"
                          (colours/magenta (disp/ppr-str (:arg ctx)) true)
                          (colours/magenta (disp/ppr-str (:expr ctx)))
                          (colours/yellow (disp/describe-type source-type)))

                  :nullable-source
                  (mm/mismatched-nullable-msg ctx source-type target-type)

                  :missing-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (disp/ppr-str (:arg ctx)) true)
                          (colours/magenta (disp/ppr-str (:expr ctx)))
                          (colours/yellow (pth/missing-detail (:path cast-result)
                                                              (:expected-key cast-result))))

                  :nullable-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (disp/ppr-str (:arg ctx)) true)
                          (colours/magenta (disp/ppr-str (:expr ctx)))
                          (colours/yellow (pth/nullable-detail (:path cast-result)
                                                               (:actual-key cast-result)
                                                               (:expected-key cast-result))))

                  :unexpected-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (disp/ppr-str (:arg ctx)) true)
                          (colours/magenta (disp/ppr-str (:expr ctx)))
                          (colours/yellow (pth/unexpected-detail :input
                                                                 (:path cast-result)
                                                                 (:actual-key cast-result))))

                  (if (and (at/ground-type? source-type)
                           (at/ground-type? target-type)
                           (not= source-type target-type))
                    (mm/mismatched-ground-type-msg ctx source-type target-type)
                    (mm/mismatched-schema-msg ctx source-type target-type)))]
    (if (contains? #{:missing-key :nullable-key :unexpected-key} (:reason cast-result))
      message
      (pth/with-path-detail message cast-result))))

(defn cast-report-metadata
  [cast-result]
  (let [primary (pth/primary-cast-failure cast-result)]
    {:cast-result cast-result
     :cast-results (vec (pth/cast-leaf-results cast-result))
     :blame-side (:blame-side primary)
     :blame-polarity (:blame-polarity primary)
     :rule (:rule primary)
     :expected-type (:target-type primary)
     :actual-type (:source-type primary)}))

(defn cast-report
  [ctx expected actual]
  (let [expected-type (ato/normalize-type expected)
        actual-type (ato/normalize-type actual)
        cast-result (acast/check-cast actual-type
                                      expected-type)]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (let [errors (->> (pth/cast-leaf-results cast-result)
                        (map #(cast-result->message ctx %))
                        distinct
                        vec)]
        (merge {:ok? false
                :errors errors}
               (cast-report-metadata cast-result))))))

(defn output-cast-report
  [ctx expected actual]
  (let [expected-type (ato/normalize-type expected)
        actual-type (ato/normalize-type actual)
        cast-result (acast/check-cast actual-type
                                      expected-type)]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (merge {:ok? false
              :errors [(mm/mismatched-output-schema-msg ctx actual-type expected-type)]}
             (cast-report-metadata cast-result)))))
