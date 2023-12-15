(ns skeptic.checking
  (:require [skeptic.inconsistence :as inconsistence]
            [skeptic.analysis :as analysis]
            [skeptic.file :as file]
            [schema.core :as s]
            [skeptic.schematize :as schematize]
            [plumbing.core :as p]
            [skeptic.analysis.annotation :as aa]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.ef])
  (:import [schema.core Schema]
           [java.io File]))

(def spy-on false)
(def spy-only #{})

(defn spy*
  [msg x]
  (when (and spy-on (or (nil? spy-only)
                        (contains? spy-only msg)))
    (try (println msg (pr-str x))
         (catch Exception e
           (println msg e))))
  x)

(defmacro spy
  [msg x]
  #_
  `(spy* ~msg ~x)
  x)

(defn valid-schema?
  [s]
  (or (instance? Schema s)
      (class? s)
      (and (coll? s) (every? valid-schema? s))))

(defmacro assert-schema
  [s]
  #_
  `(do (assert (valid-schema? ~s) (format "Must be valid schema: %s" ~s))
       ~s)
  s)

(defmacro assert-has-schema
  [x]
  #_
  `(do (assert (valid-schema? (:schema ~x)) (format "Must be valid schema: %s (%s)" (:schema ~x) (pr-str ~x)))
       ~x)
  x)

;; TODO: what can we assert here? We already either:
;; 1. Found a matching arglist, in which case we know the counts match; if expected is short, the last arg is
;;    a vararg and repeats (can we fix this representation? Is there a better one?). Not sure how actual could
;;    be short.
;; 2. We didn't find a matching arglist, in which case we assume that we have no valid data to match up; what
;;    then? (Can this still happen, or will we always get the dynamic fn type `(=> Any [Any])`?)
(s/defn match-up-arglists
  [expr expected actual]
  (spy :match-up-actual-list actual)
  (spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        args (vec (drop 1 expr))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get args n)
       (spy :match-up-expected (get expected n expected-vararg))
       (spy :match-up-actual (get actual n))])))

(s/defn lookup-resolutions
  [refs]
  (fn [els]
    (loop [[{:keys [idx resolution-path] :as el} & rest] els
           acc []]
      (cond
        (nil? el) acc
        :else (if-let [lookup (get refs idx)]
                (recur (concat rest
                               resolution-path
                               (:resolution-path lookup))
                       (conj acc (select-keys lookup [:idx :expr :schema])))
                (recur (concat rest resolution-path)
                       acc))))))

(s/defn match-up-resolution-paths
  [refs
   context]
  (p/map-vals
   #(update %
            :resolution-path
            (lookup-resolutions refs))
   context))

(s/defn match-s-exprs
  [refs
   {:keys [expected-arglist actual-arglist expr local-vars path resolution-path] :as to-match}]
  (when (seq expected-arglist)
    (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
            (format "Arglists must not be nil: %s %s\n%s"
                    expected-arglist actual-arglist to-match))
    (assert (>= (count actual-arglist) (count expected-arglist))
            (format "Actual should have at least as many elements as expected: %s %s\n%s"
                    expected-arglist actual-arglist to-match))
    (let [cleaned (aa/unannotate-expr expr)
          matched (spy :matched-arglists (match-up-arglists cleaned
                                                            (spy :expected-arglist (vec expected-arglist))
                                                            (spy :actual-arglist (vec actual-arglist))))
          errors (vec (mapcat (partial apply inconsistence/inconsistent? cleaned) matched))]
      {:blame cleaned
       :path path
       :context {:local-vars (match-up-resolution-paths refs local-vars)
                 :refs ((lookup-resolutions refs) resolution-path)}
       :errors errors})))

(defn check-s-expr
  [dict s-expr {:keys [keep-empty remove-context]}]
  (try (let [analysed (analysis/attach-schema-info-loop dict s-expr)]
         (cond->> (->> analysed
                       vals
                       (keep (partial match-s-exprs analysed)))

           (not keep-empty)
           (remove (comp empty? :errors))

           remove-context
           (map #(dissoc % :context))))
       (catch Exception e
         (println "Error parsing expression")
         (println (pr-str (ana.ef/emit-form s-expr)))
         (println e)
         (throw e))))

(defmacro block-in-ns
  [ns ^File file & body]
  `(let [contents# (slurp ~file)
         ns-dec# (read-string contents#)
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

(defn ns-exprs
  [ns]
  (ana.jvm/analyze-ns ns))

;; TODO: if unparseable, throws error
;; Should either pass that on, or (ideally) localize it to a single s-expr and flag that
(defmacro check-ns
  ([ns]
   `(check-ns ~ns {}))
  ([ns opts]
   `(check-ns (schematize/ns-schemas ~opts ~ns) ~ns ~opts))
  ([dict ns opts]
   `(do (assert ~ns "Can't have null namespace for check-ns")
        (let [dict# ~dict]
          (mapcat #(check-s-expr dict# % ~opts)
                  (ns-exprs ~ns))))))
