(ns skeptic.output.porcelain
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.output.serialize :as ser]))

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

(defn- write-line-raw!
  [m]
  (println (json/write-str m)))

(defn- with-debug
  [record opts result]
  (cond-> record
    (:debug opts) (assoc :debug {:raw-result (ser/json-safe result)})))

(defn- location-record
  [{:keys [file line column source]}]
  {:file file
   :line line
   :column column
   :source (name source)})

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
   :finding (fn [ns result summary opts]
              (let [record (if (= :exception (:report-kind summary))
                             (exception-record ns result summary opts)
                             (finding-record ns result summary opts))]
                (write-line-raw! (if (:debug opts) record (drop-empties record)))))
   :form-debug (fn [_ns record _opts]
                 (write-line-raw! (ser/json-safe record)))
   :ns-end (fn [_ns _count _opts])
   :run-end (fn [errored? totals]
              (write-line! (run-summary-record errored? totals)))})
