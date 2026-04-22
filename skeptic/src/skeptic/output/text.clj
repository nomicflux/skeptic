(ns skeptic.output.text
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.ef]
            [skeptic.analysis.annotation :as aa]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.colours :as colours]))

(def ^:private global-blame-label "scope escape")
(def ^:private missing-blame-label "<missing>")

(defn- format-location
  [{:keys [file line column]}]
  (cond
    (and file line column) (str file ":" line ":" column)
    (and file line) (str file ":" line)
    file file
    line (str line)
    :else nil))

(defn- format-focuses
  [focuses]
  (when (seq focuses)
    (str/join ", " focuses)))

(defn- render-context-value-blame
  [active-side]
  (let [context-bright? (= active-side :context)
        value-bright? (= active-side :term)
        context-style (if context-bright?
                        #(colours/white % true)
                        colours/white-dim)
        value-style (if value-bright?
                      #(colours/white % true)
                      colours/white-dim)]
    (str (context-style "context")
         (context-style "( ")
         (value-style "value")
         (context-style " )"))))

(defn- format-blame
  [blame-side blame-polarity]
  (case [blame-side blame-polarity]
    [:term :positive] (render-context-value-blame :term)
    [:context :negative] (render-context-value-blame :context)
    [:global :global] (colours/white global-blame-label true)
    [:none :none] (colours/white missing-blame-label)
    (cond
      (or (= blame-side :term)
          (= blame-polarity :positive))
      (render-context-value-blame :term)

      (or (= blame-side :context)
          (= blame-polarity :negative))
      (render-context-value-blame :context)

      (or (= blame-side :global)
          (= blame-polarity :global))
      (colours/white global-blame-label true)

      :else
      (colours/white missing-blame-label))))

(defn- render-source-suffix
  [src]
  (str " [source: " (name src) "]"))

(defn- print-report-field
  [label value]
  (let [text (str value)]
    (if (str/includes? text "\n")
      (do
        (println (colours/white label true))
        (println (colours/white text true)))
      (println (colours/white (str label text) true)))))

(defn report-fields
  "Build an ordered [[label value] ...] list from a report summary for the
  human-readable text printer. Public for testing."
  ([summary] (report-fields summary false))
  ([{:keys [report-kind phase location blame-side blame-polarity rule rule-text
           actual-type actual-type-text expected-type expected-type-text
           source-expression blame focus-sources focuses enclosing-form
           expanded-expression exception-class declaration-slot
           rejected-schema source]}
   verbose]
  (if (= :exception report-kind)
    (remove nil?
            [(when-let [location-text (format-location location)]
               ["Location: \t\t" (str location-text (render-source-suffix source))])
             (when verbose
               ["Phase: \t\t\t" (name phase)])
             (when (and verbose exception-class)
               ["Exception class: \t" (str exception-class)])
             (when (and verbose declaration-slot)
               ["Schema slot: \t\t" (pr-str declaration-slot)])
             (when (and verbose (some? rejected-schema))
               ["Rejected schema: \t" (pr-str rejected-schema)])
             (when verbose
               ["Expression: \t\t" (or source-expression (pr-str blame))])
             (when (and verbose enclosing-form)
               ["In enclosing form: \t" (pr-str enclosing-form)])])
    (remove nil?
            [(when-let [location-text (format-location location)]
               ["Location: \t\t" (str location-text (render-source-suffix source))])
             ["Blame: \t\t\t" (format-blame blame-side blame-polarity)]
             (when (and verbose
                        (or rule-text rule))
               ["Cast rule: \t\t" (or rule-text (some-> rule name))])
             (when verbose
               (when-let [actual-text (or actual-type-text
                                          (some-> actual-type abr/render-type))]
                 ["Actual type: \t\t" actual-text]))
             (when verbose
               (when-let [expected-text (or expected-type-text
                                            (some-> expected-type abr/render-type))]
                 ["Expected type: \t" expected-text]))
             (when verbose
               ["Expression: \t\t" (or source-expression (pr-str blame))])
             (when verbose
               (when-let [focus-text (format-focuses (or focus-sources (map pr-str focuses)))]
                 ["Affected input: \t" focus-text]))
             (when (and verbose enclosing-form)
               ["In enclosing form: \t" (pr-str enclosing-form)])
             (when (and verbose expanded-expression)
               ["Analyzed expression: \t" (pr-str expanded-expression)])]))))

(defn- print-context
  [{:keys [local-vars refs]}]
  (println "Context:")
  (doseq [[k {:keys [type resolution-path]}] local-vars]
    (println (str "\t" (colours/blue (pr-str k) true) ": " (colours/green (pr-str type))))
    (doseq [{:keys [expr type]} resolution-path]
      (println (str "\t\t=> " (colours/blue (pr-str (aa/unannotate-expr expr)) true)
                    ": " (colours/green (pr-str type))))))
  (doseq [{:keys [expr type]} refs]
    (println (str "\t" (colours/blue (pr-str (aa/unannotate-expr expr)) true)
                  " <- " (colours/green (pr-str type))))))

(defn- print-finding!
  [ns result summary {:keys [verbose show-context debug] :as _opts}]
  (let [{:keys [path context]} result]
    (println "---------")
    (println (colours/white (str "Namespace: \t\t" ns) true))
    (doseq [[label value] (report-fields summary verbose)]
      (print-report-field label value))
    (when (and verbose path)
      (println (colours/white (str "In macro-expanded path: \t" (pr-str path)) true)))
    (when show-context
      (print-context context))
    (doseq [error (:errors summary)]
      (println "---")
      (println error "\n"))
    (when debug
      (println "--- DEBUG (finding) ---")
      (pprint/pprint result))))

(defn- print-form-debug!
  [_ns record _opts]
  (println "--- DEBUG (form) ---")
  (pprint/pprint record)
  (println "--- END DEBUG ---"))

(defn- print-analyzer-dump!
  [ns {:keys [verbose analyzer]}]
  (when (and verbose analyzer (find-ns ns))
    (pprint/pprint (mapv ana.ef/emit-form (ana.jvm/analyze-ns ns)))))

(def printer
  {:run-start (fn [{:keys [verbose]} nss]
                (when verbose
                  (println "Namespaces to check: " (pr-str (keys nss)))))
   :discovery-warn (fn [{:keys [path message]}]
                     (println "Couldn't get namespaces:" (format "%s (%s)" path message)))
   :ns-start (fn [ns _source-file {:keys [verbose]}]
               (when verbose (println "*** Checking" ns "***")))
   :finding print-finding!
   :form-debug print-form-debug!
   :ns-end (fn [ns _count opts]
             (print-analyzer-dump! ns opts))
   :run-end (fn [errored? _totals]
              (when-not errored?
                (println "No inconsistencies found")))})
