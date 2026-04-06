(ns skeptic.core
  (:require [skeptic.checking.pipeline :as checking]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.file :as file]
            [skeptic.colours :as colours]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotation :as aa]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.ef]))

(defn format-location
  [{:keys [file line column]}]
  (cond
    (and file line column) (str file ":" line ":" column)
    (and file line) (str file ":" line)
    file file
    line (str line)
    :else nil))

(defn format-focuses
  [focuses]
  (when (seq focuses)
    (str/join ", " focuses)))

(def global-blame-label "scope escape")
(def missing-blame-label "<missing>")

(defn render-context-value-blame
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

(defn format-blame
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

(defn print-report-field
  [label value]
  (let [text (str value)]
    (if (str/includes? text "\n")
      (do
        (println (colours/white label true))
        (println (colours/white text true)))
	      (println (colours/white (str label text) true)))))

(defn report-fields
  ([summary]
   (report-fields summary false))
  ([{:keys [report-kind phase location blame-side blame-polarity rule rule-text
            actual-type actual-type-text expected-type expected-type-text
            source-expression blame focus-sources focuses enclosing-form
            expanded-expression]}
    verbose]
   (if (= :exception report-kind)
     (remove nil?
             [(when-let [location-text (format-location location)]
                ["Location: \t\t" location-text])
              (when verbose
                ["Phase: \t\t\t" (name phase)])
              (when verbose
                ["Expression: \t\t" (or source-expression (pr-str blame))])
              (when (and verbose enclosing-form)
                ["In enclosing form: \t" (pr-str enclosing-form)])])
     (remove nil?
             [(when-let [location-text (format-location location)]
                ["Location: \t\t" location-text])
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

(defn check-project
  [{:keys [verbose show-context namespace analyzer] :as opts} root & paths]
  (let [nss (cond-> (try (->> paths
                              (map (partial file/relative-path (io/file root)))
                              (mapcat file/clojure-files-for-path)
                              (map file/ns-for-clojure-file)
                              (remove (comp nil? first))
                              (into {}))
                         (catch Exception e
                           (println "Couldn't get namespaces: " e)
                           (throw e)))

              namespace
              (select-keys [(symbol namespace)]))]
    (when verbose (println "Namespaces to check: " (pr-str (keys nss))))
    (let [errored (atom false)]
      (doseq [[ns source-file] nss]
        (when verbose (println "*** Checking" ns "***"))
        (doseq [result (checking/check-namespace opts ns source-file)]
          (println "---------")
          (let [{:keys [path context]} result
                summary (inrep/report-summary result)]
            (println (colours/white (str "Namespace: \t\t" ns) true))
            (doseq [[label value] (report-fields summary verbose)]
              (print-report-field label value))
            (when (and verbose path)
              (println (colours/white (str "In macro-expanded path: \t" (pr-str path)) true)))
            (when show-context
              (println "Context:")
              (doseq [[k {:keys [type resolution-path]}] (:local-vars context)]
                (println (str "\t" (colours/blue (pr-str k) true) ": " (colours/green (pr-str type))))
                (doseq [{:keys [expr type]} resolution-path]
                  (println (str "\t\t=> " (colours/blue (pr-str (aa/unannotate-expr expr)) true) ": " (colours/green (pr-str type))))))
              (doseq [{:keys [expr type]} (:refs context)]
                (println (str "\t" (colours/blue (pr-str (aa/unannotate-expr expr)) true) " <- " (colours/green (pr-str type))))))
            (doseq [error (:errors summary)]
              (reset! errored true)
              (println "---")
              (println error "\n"))))
        (when (and verbose analyzer (find-ns ns))
          (pprint/pprint (mapv ana.ef/emit-form (ana.jvm/analyze-ns ns)))))
      (if @errored
        1
        (do (println "No inconsistencies found")
            0)))))
