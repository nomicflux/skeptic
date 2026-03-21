(ns skeptic.checking
  (:require [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis :as analysis]
            [skeptic.file :as file]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schematize :as schematize])
  (:import [java.io File]
           [schema.core Schema]))

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
  [schema]
  (or (instance? Schema schema)
      (class? schema)
      (and (coll? schema) (every? valid-schema? schema))))

(defmacro assert-schema
  [schema]
  #_
  `(do (assert (valid-schema? ~schema) (format "Must be valid schema: %s" ~schema))
       ~schema)
  schema)

(defmacro assert-has-schema
  [x]
  #_
  `(do (assert (valid-schema? (:schema ~x)) (format "Must be valid schema: %s (%s)" (:schema ~x) (pr-str ~x)))
       ~x)
  x)

(def invoke-ops
  #{:instance-call
    :invoke
    :keyword-invoke
    :prim-invoke
    :protocol-invoke
    :static-call})

(defn child-nodes
  [node]
  (mapcat (fn [child]
            (let [value (get node child)]
              (cond
                (vector? value) value
                (map? value) [value]
                :else [])))
          (:children node)))

(defn ast-nodes-preorder
  [ast]
  (tree-seq map? child-nodes ast))

(defn node-ref
  [node]
  (when node
    (select-keys node [:form :schema])))

(defn callee-ref
  [node]
  (when node
    (case (:op node)
      :invoke (node-ref (:fn node))
      :with-meta (recur (:expr node))
      nil)))

(defn match-up-arglists
  [arg-nodes expected actual]
  (spy :match-up-actual-list actual)
  (spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(:form (get arg-nodes n))
       (spy :match-up-expected (get expected n expected-vararg))
       (spy :match-up-actual (get actual n))])))

(defn binding-index
  [ast]
  (reduce (fn [acc node]
            (if (= :binding (:op node))
              (assoc acc (:form node) node)
              acc))
          {}
          (ana.ast/nodes ast)))

(declare local-resolution-path)

(defn local-resolution-path
  [bindings local-node]
  (if-let [binding (get bindings (:form local-node))]
    (if-let [init (:init binding)]
      (cond-> [(node-ref init)]
        (callee-ref init)
        (conj (callee-ref init)))
      [])
    []))

(defn local-vars-context
  [bindings node]
  (->> (:args node)
       (mapcat ana.ast/nodes)
       (filter #(= :local (:op %)))
       (reduce (fn [acc local-node]
                 (if (contains? acc (:form local-node))
                   acc
                   (assoc acc
                          (:form local-node)
                          {:form (:form local-node)
                           :schema (:schema local-node)
                           :resolution-path (local-resolution-path bindings local-node)})))
               {})))

(defn call-refs
  [bindings node]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (= :local (:op fn-node))
      (into [(node-ref fn-node)]
            (local-resolution-path bindings fn-node))
      :else
      (cond-> []
        (node-ref fn-node)
        (conj (node-ref fn-node))))))

(defn call-node?
  [node]
  (and (contains? invoke-ops (:op node))
       (vector? (:args node))
       (seq (:expected-arglist node))
       (seq (:actual-arglist node))))

(defn match-s-exprs
  [bindings node]
  (when (call-node? node)
    (let [expected-arglist (vec (:expected-arglist node))
          actual-arglist (vec (:actual-arglist node))]
      (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
              (format "Arglists must not be nil: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (assert (>= (count actual-arglist) (count expected-arglist))
              (format "Actual should have at least as many elements as expected: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (let [matched (spy :matched-arglists (match-up-arglists (:args node)
                                                              (spy :expected-arglist expected-arglist)
                                                              (spy :actual-arglist actual-arglist)))
            errors (vec (mapcat (partial apply inconsistence/inconsistent? (:form node)) matched))]
        {:blame (:form node)
         :path nil
         :context {:local-vars (local-vars-context bindings node)
                   :refs (call-refs bindings node)}
         :errors errors}))))

(defn check-s-expr
  [dict s-expr {:keys [keep-empty remove-context] :as opts}]
  (try
    (let [analysed (analysis/attach-schema-info-loop dict s-expr opts)
          bindings (binding-index analysed)]
      (cond->> (->> (ast-nodes-preorder analysed)
                    (filter call-node?)
                    (keep #(match-s-exprs bindings %)))
        (not keep-empty)
        (remove (comp empty? :errors))

        remove-context
        (map #(dissoc % :context))))
    (catch Exception e
      (println "Error parsing expression")
      (println (pr-str s-expr))
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
  (let [source-file (file/source-clj ns)]
    (assert source-file (format "Can't find source file for namespace %s" ns))
    (with-open [reader (file/pushback-reader source-file)]
      (->> (repeatedly #(file/try-read reader))
           (take-while some?)
           (remove file/is-ns-block?)
           doall))))

;; TODO: if unparseable, throws error
;; Should either pass that on, or (ideally) localize it to a single s-expr and flag that
(defmacro check-ns
  ([ns]
   `(check-ns ~ns {}))
  ([ns opts]
   `(check-ns (schematize/ns-schemas ~opts ~ns) ~ns ~opts))
  ([dict ns opts]
   `(do (assert ~ns "Can't have null namespace for check-ns")
        (let [dict# ~dict
              opts# (assoc ~opts :ns ~ns)]
          (mapcat #(check-s-expr dict# % opts#)
                  (ns-exprs ~ns))))))
