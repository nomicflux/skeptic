(ns skeptic.output.porcelain
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [schema.core :as s]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.checking.form :as cf]
            [skeptic.checking.opts :as copts]
            [skeptic.output.serialize :as ser]))

(def ^:private ansi-pattern #"\u001b\[[0-9;]*m")

(defn- strip-ansi [s]
  (when s (str/replace s ansi-pattern "")))

(defn- ->str [x]
  (when (some? x) (cf/safe-pr-str x)))

(defn- empty-value? [v]
  (or (nil? v)
      (and (coll? v) (empty? v))))

(defn- drop-empties [m]
  (into {} (remove (fn [[_ v]] (empty-value? v))) m))

(defn- write-line!
  [m]
  (println (json/write-str (drop-empties m))))

(defn- write-line-raw!
  [m]
  (println (json/write-str m)))

(defn- with-debug
  [record opts result]
  (cond-> record
    (:debug opts) (assoc :debug {:raw-result (ser/json-safe result)})))

(defn- location-lang
  [lang]
  (cond
    (nil? lang) nil
    (set? lang) (mapv name (sort lang))
    :else (name lang)))

(defn- location-record
  [{:keys [file line column source lang]}]
  {:file file
   :line line
   :column column
   :source (name source)
   :lang (location-lang lang)})

(defn- exception-record
  [ns result {:keys [phase location blame errors]} opts]
  (with-debug
    {:kind "exception"
     :ns (str ns)
     :phase (some-> phase name)
     :location (location-record location)
     :blame (->str blame)
     :exception_class (some-> (:exception-class result) str)
     :exception_message (:exception-message result)
     :messages (mapv strip-ansi errors)}
    opts result))

(defn- analysis-skipped-record
  [ns result {:keys [phase location blame errors]} opts]
  (with-debug
    {:kind "analysis-skipped"
     :ns (str ns)
     :phase (some-> phase name)
     :location (location-record location)
     :blame (->str blame)
     :exception_class (some-> (:exception-class result) str)
     :exception_message (:exception-message result)
     :messages (mapv strip-ansi errors)}
    opts result))

(defn- finding-record
  [ns result summary opts]
  (let [{:keys [report-kind location blame-side blame-polarity rule
                actual-type expected-type
                source-expression blame focuses focus-sources enclosing-form
                expanded-expression errors]} summary]
    (with-debug
      {:kind "finding"
       :ns (str ns)
       :report_kind (some-> report-kind name)
       :location (location-record location)
       :blame (or source-expression (->str blame))
       :blame_side (some-> blame-side name)
       :blame_polarity (some-> blame-polarity name)
       :rule (some-> rule name)
       :actual_type (abr/type->json-data* actual-type opts)
       :expected_type (abr/type->json-data* expected-type opts)
       :actual_type_str (abr/render-type* actual-type opts)
       :expected_type_str (abr/render-type* expected-type opts)
       :focuses (vec (or focus-sources (map ->str focuses)))
       :enclosing_form (some-> enclosing-form ->str)
       :expanded_expression (some-> expanded-expression ->str)
       :messages (mapv strip-ansi errors)}
      opts result)))

(defn- run-summary-record
  [errored? {:keys [finding-count exception-count analysis-skipped-count
                    namespace-count namespaces-with-findings]}]
  {:kind "run-summary"
   :errored (boolean errored?)
   :finding_count finding-count
   :exception_count exception-count
   :analysis_skipped_count (or analysis-skipped-count 0)
   :namespace_count namespace-count
   :namespaces_with_findings namespaces-with-findings})

(defn- namespace-error-summary-record
  [{:keys [per-namespace-counts]} verbose?]
  {:kind "namespace-error-summary"
   :counts (into (sorted-map)
                 (cond->> per-namespace-counts
                   (not verbose?) (filter (comp pos? val))
                   true (map (fn [[k v]] [(str k) v]))))})

(def printer
  {:run-start (s/fn [_opts :- copts/PrinterOpts _nss])
   :discovery-warn (s/fn [_opts :- copts/PrinterOpts {:keys [path message unresolvable-deps]}]
                     (write-line! (cond-> {:kind "ns-discovery-warning"
                                           :path path
                                           :message message}
                                    (seq unresolvable-deps)
                                    (assoc :unresolvable_deps (vec unresolvable-deps)))))
   :ns-start (s/fn [_ns _source-file _opts :- copts/PrinterOpts])
   :finding (fn [ns result summary opts]
              (let [record (case (:report-kind summary)
                             :exception        (exception-record ns result summary opts)
                             :analysis-skipped (analysis-skipped-record ns result summary opts)
                             (finding-record ns result summary opts))]
                (write-line-raw! (if (:debug opts) record (drop-empties record)))))
   :form-debug (s/fn [_ns record _opts :- copts/PrinterOpts]
                 (write-line-raw! (ser/json-safe record)))
   :ns-end (s/fn [_ns _count _opts :- copts/PrinterOpts])
   :run-end (s/fn [errored? totals opts :- copts/PrinterOpts]
              (write-line-raw! (namespace-error-summary-record totals (:verbose opts)))
              (write-line! (run-summary-record errored? totals)))})
