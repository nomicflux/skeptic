(ns skeptic.checking
  (:require [skeptic.inconsistence :as inconsistence]
            [skeptic.analysis :as analysis]
            [schema.core :as s]
            [skeptic.schematize :as schematize]
            [taoensso.tufte :as tufte])
  (:import [schema.core Schema]))

(def spy-on false)
(def spy-only #{:match-s-exprs-expected-arglist :match-s-exprs-actual-arglist
                :expected-arglist :actual-arglist :matched-arglists})

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
  `(spy* ~msg ~x)
  #_
  (if (seq? x)
    `(tufte/p ~msg ~x)
    x)
  #_
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

(s/defn match-s-exprs
  ([to-match]
   (match-s-exprs [] to-match))
  ([parent-name
    {:keys [expected-arglist actual-arglist expr local-vars name] :as to-match}]
   (spy :match-s-exprs-full to-match)
   (spy :match-s-exprs-expected-arglist expected-arglist)
   (spy :match-s-exprs-actual-arglist actual-arglist)
   (let [path (remove nil? (conj parent-name name))]
     (if (seq expected-arglist)
       (do
         (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
                 (format "Arglists must not be nil: %s %s\n%s"
                         expected-arglist actual-arglist to-match))
         (assert (>= (count actual-arglist) (count expected-arglist))
                 (format "Actual should have at least as many elements as expected: %s %s\n%s"
                         expected-arglist actual-arglist to-match))
         (let [cleaned (analysis/unannotate-expr expr)
               matched (spy :matched-arglists (match-up-arglists cleaned
                                                                 (spy :expected-arglist (vec expected-arglist))
                                                                 (spy :actual-arglist (vec actual-arglist))))
               errors (vec (keep (partial apply inconsistence/inconsistent? cleaned) matched))]
           {:blame cleaned
            :path path
            :context local-vars
            :errors errors}))
       nil))))

(s/defn check-s-expr
  [dict s-expr {:keys [keep-empty clean-context]}]
  (cond->> (->> (spy :check-s-expr-expr s-expr)
                (analysis/attach-schema-info-loop dict)
                vals
                (filter :expected-arglist)
                (map match-s-exprs))

    (not keep-empty)
    (remove (comp empty? :errors))

    clean-context
    (map #(dissoc % :context))))

(s/defn normalize-fn-code
  [ns-refs f]
  (->> f
       schematize/get-fn-code
       (schematize/resolve-code-references ns-refs)))

(s/defn check-fn
  ([ns-refs dict f]
   (check-fn ns-refs dict f {}))
  ([ns-refs dict f opts]
   (check-s-expr dict (normalize-fn-code ns-refs f) opts)))

(s/defn annotate-fn
  [ns-refs dict f]
  (->> f (normalize-fn-code ns-refs) (analysis/attach-schema-info-loop dict)))

(defmacro block-in-ns
  [ns & body]
  `(let [ns-dec# (read-string (schematize/source-clj ~ns))
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

(defn ns-exprs
  [ns]
  (let [code (read-string (str "'(" (schematize/source-clj ns) ")"))]
    (->> code (mapv (partial schematize/resolve-all (ns-map ns))))))
;; TODO: dropping initial `ns` block as it isn't relevant to type-checking and complicates matters,
;; but we should add it back in for checking

(defmacro annotate-ns
  ([ns]
   `(annotate-ns (schematize/ns-schemas ~ns) ~ns))
  ([dict ns]
   `(block-in-ns ~ns (mapcat #(attach-schema-info ~dict %) (ns-exprs ~ns)))))

;; TODO: if unparseable, throws error
;; Should either pass that on, or (ideally) localize it to a single s-expr and flag that
(defmacro check-ns
  ([ns]
   `(check-ns ~ns {}))
  ([ns opts]
   `(check-ns (schematize/ns-schemas ~ns) ~ns ~opts))
  ([dict ns opts]
   `(block-in-ns ~ns
                 (mapcat #(check-s-expr ~dict {} % ~opts)
                         (ns-exprs ~ns)))))
