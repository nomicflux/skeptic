(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.analysis.schema :as as]
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

(defn print-report-field
  [label value]
  (let [text (str value)]
    (if (str/includes? text "\n")
      (do
        (println (colours/white label true))
        (println (colours/white text true)))
	      (println (colours/white (str label text) true)))))

(defn describe-type
  [type]
  (some-> type
          as/type->schema
          pr-str))

(defn report-fields
  [{:keys [location blame-side blame-polarity rule actual-type expected-type
           source-expression blame focus-sources focuses enclosing-form
           expanded-expression]}]
  (remove nil?
          [(when-let [location-text (format-location location)]
             ["Location: \t\t" location-text])
           (when (and blame-side blame-polarity
                      (not= blame-side :none)
                      (not= blame-polarity :none))
             ["Blame: \t\t" (str (name blame-side) " / " (name blame-polarity))])
           (when rule
             ["Cast rule: \t\t" (name rule)])
           (when-let [actual-text (describe-type actual-type)]
             ["Actual type: \t\t" actual-text])
           (when-let [expected-text (describe-type expected-type)]
             ["Expected type: \t" expected-text])
           ["Expression: \t\t" (or source-expression (pr-str blame))]
           (when-let [focus-text (format-focuses (or focus-sources (map pr-str focuses)))]
             ["Affected input: \t" focus-text])
           (when enclosing-form
             ["In enclosing form: \t" (pr-str enclosing-form)])
           (when expanded-expression
             ["Analyzed expression: \t" (pr-str expanded-expression)])]))

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
	         (doseq [{:keys [blame source-expression expanded-expression location enclosing-form focuses focus-sources path errors context blame-side blame-polarity rule expected-type actual-type]} (checking/check-ns dict ns source-file opts)]
	           (println "---------")
	           (println (colours/white (str "Namespace: \t\t" ns) true))
	           (doseq [[label value] (report-fields {:location location
	                                                :blame-side blame-side
	                                                :blame-polarity blame-polarity
	                                                :rule rule
	                                                :actual-type actual-type
	                                                :expected-type expected-type
	                                                :source-expression source-expression
	                                                :blame blame
	                                                :focus-sources focus-sources
	                                                :focuses focuses
	                                                :enclosing-form enclosing-form
	                                                :expanded-expression expanded-expression})]
	             (print-report-field label value))
	           (when (and verbose path)
	             (println (colours/white (str "In macro-expanded path: \t" (pr-str path)) true)))
           (when show-context
             (println "Context:")
             (doseq [[k {:keys [schema resolution-path]}] (:local-vars context)]
               (println (str "\t" (colours/blue (pr-str k) true) ": " (colours/green (pr-str schema))))
               (doseq [{:keys [expr schema]} resolution-path]
                 (println (str "\t\t=> " (colours/blue (pr-str (aa/unannotate-expr expr)) true) ": " (colours/green (pr-str schema))))))
             (doseq [{:keys [expr schema]} (:refs context)]
               (println (str "\t" (colours/blue (pr-str (aa/unannotate-expr expr)) true) " <- " (colours/green (pr-str schema))))))
           (doseq [error errors]
             (reset! errored true)
             (println "---")
             (println error "\n"))))
         (catch Exception e
           (println (colours/white (str "Namespace: \t\t" ns) true))
           (println (colours/red (str "Error parsing namespace " ns ": " e) true))
           (when verbose
             (println (stacktrace/print-stack-trace e))))))
      (if @errored
        (System/exit 1)
        (do (println "No inconsistencies found")
            (System/exit 0))))))
