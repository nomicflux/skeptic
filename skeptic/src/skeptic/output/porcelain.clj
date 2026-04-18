(ns skeptic.output.porcelain
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skeptic.analysis.bridge.render :as abr]))

(def ^:private ansi-pattern #"\u001b\[[0-9;]*m")

(defn- strip-ansi [s]
  (when s (str/replace s ansi-pattern "")))

(defn- ->str [x]
  (when (some? x) (pr-str x)))

(defn- empty-value? [v]
  (or (nil? v)
      (and (coll? v) (empty? v))))

(defn- drop-empties [m]
  (into {} (remove (fn [[_ v]] (empty-value? v))) m))

(defn- write-line!
  [m]
  (println (json/write-str (drop-empties m))))

(defn- exception-record
  [ns result {:keys [phase location blame errors]}]
  {:kind "exception"
   :ns (str ns)
   :phase (some-> phase name)
   :file (:file location)
   :line (:line location)
   :column (:column location)
   :blame (->str blame)
   :exception_class (some-> (:exception-class result) str)
   :exception_message (:exception-message result)
   :messages (mapv strip-ansi errors)})

(defn- finding-record
  [ns _result summary]
  (let [{:keys [report-kind location blame-side blame-polarity rule
                actual-type expected-type
                source-expression blame focuses focus-sources enclosing-form
                expanded-expression errors]} summary]
    {:kind "finding"
     :ns (str ns)
     :report_kind (some-> report-kind name)
     :file (:file location)
     :line (:line location)
     :column (:column location)
     :blame (or source-expression (->str blame))
     :blame_side (some-> blame-side name)
     :blame_polarity (some-> blame-polarity name)
     :rule (some-> rule name)
     :actual_type (abr/type->json-data actual-type)
     :expected_type (abr/type->json-data expected-type)
     :actual_type_str (abr/render-type actual-type)
     :expected_type_str (abr/render-type expected-type)
     :focuses (vec (or focus-sources (map ->str focuses)))
     :enclosing_form (some-> enclosing-form ->str)
     :expanded_expression (some-> expanded-expression ->str)
     :messages (mapv strip-ansi errors)}))

(defn- run-summary-record
  [errored? {:keys [finding-count exception-count namespace-count
                    namespaces-with-findings]}]
  {:kind "run-summary"
   :errored (boolean errored?)
   :finding_count finding-count
   :exception_count exception-count
   :namespace_count namespace-count
   :namespaces_with_findings namespaces-with-findings})

(def printer
  {:run-start (fn [_opts _nss])
   :discovery-warn (fn [{:keys [path message]}]
                     (write-line! {:kind "ns-discovery-warning"
                                   :path path
                                   :message message}))
   :ns-start (fn [_ns _source-file _opts])
   :finding (fn [ns result summary _opts]
              (write-line!
               (if (= :exception (:report-kind summary))
                 (exception-record ns result summary)
                 (finding-record ns result summary))))
   :ns-end (fn [_ns _count _opts])
   :run-end (fn [errored? totals]
              (write-line! (run-summary-record errored? totals)))})
