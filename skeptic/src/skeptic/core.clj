(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.file :as file]
            [skeptic.colours :as colours]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotation :as aa]
            [skeptic.schematize :as schematize]
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

(defn format-blame
  [blame-side blame-polarity]
  (case [blame-side blame-polarity]
    [:term :positive]
    "this expression or returned value does not match what the surrounding code expects"

    [:context :negative]
    "the surrounding code is using this value in a way its schema does not allow"

    [:global :global]
    "an abstract value was inspected or escaped the scope where it is valid"

    (when (and blame-side blame-polarity
               (not= blame-side :none)
               (not= blame-polarity :none))
      (str (name blame-side) " / " (name blame-polarity)))))

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
  ([{:keys [location blame-side blame-polarity rule rule-text
            actual-type actual-type-text expected-type expected-type-text
            source-expression blame focus-sources focuses enclosing-form
            expanded-expression]}
    verbose]
   (remove nil?
           [(when-let [location-text (format-location location)]
              ["Location: \t\t" location-text])
            (when-let [blame-text (format-blame blame-side blame-polarity)]
              ["Blame: \t\t\t" blame-text])
            (when (and verbose
                       (or rule-text rule))
              ["Cast rule: \t\t" (or rule-text (some-> rule name))])
            (when verbose
              (when-let [actual-text (or actual-type-text
                                         (some-> actual-type abr/display))]
                ["Actual type: \t\t" actual-text]))
            (when verbose
              (when-let [expected-text (or expected-type-text
                                           (some-> expected-type abr/display))]
                ["Expected type: \t" expected-text]))
            (when verbose
              ["Expression: \t\t" (or source-expression (pr-str blame))])
            (when verbose
              (when-let [focus-text (format-focuses (or focus-sources (map pr-str focuses)))]
                ["Affected input: \t" focus-text]))
            (when (and verbose enclosing-form)
              ["In enclosing form: \t" (pr-str enclosing-form)])
            (when (and verbose expanded-expression)
              ["Analyzed expression: \t" (pr-str expanded-expression)])])))

(defn get-project-schemas
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
       (require ns)
       (when verbose (println "*** Checking" ns "***"))
       ;; (pprint/pprint (checking/annotate-ns ns))
       (try
         (let [dict (schematize/ns-schemas opts ns)]
           ;(when verbose
           ;  (println "Schema dictionary:")
           ;  (pprint/pprint dict))
	         (when analyzer
	           (pprint/pprint (mapv ana.ef/emit-form (ana.jvm/analyze-ns ns))))
	         (doseq [result (checking/check-ns dict ns source-file opts)]
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
	               (println error "\n")))))
         (catch Exception e
           (println (colours/white (str "Namespace: \t\t" ns) true))
           (println (colours/red (str "Error parsing namespace " ns ": " e) true))
           (when verbose
             (println (stacktrace/print-stack-trace e))))))
      (if @errored
        (System/exit 1)
        (do (println "No inconsistencies found")
            (System/exit 0))))))
