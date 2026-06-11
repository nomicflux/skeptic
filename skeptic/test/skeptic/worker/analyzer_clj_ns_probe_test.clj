(ns skeptic.worker.analyzer-clj-ns-probe-test
  "Probe: isolate where the :clojure.core/map-key-query mis-resolution and the
   #clojure.reflect.Method wire-projection failure each originate, by calling the
   worker's real read and analyze steps directly on a real source file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [skeptic.worker.analyzer-clj :as wac]
            [skeptic.worker.client :as wc]
            [skeptic.worker.process :as proc]
            [skeptic.worker.server :as server]))

(defn- read-forms-unbound
  [source-file]
  (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
    (doall (take-while #(not= ::eof %)
                       (repeatedly #(tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader))))))

(defn- read-forms-bound
  [ns-sym source-file]
  (binding [*ns* (the-ns ns-sym)]
    (with-open [reader (rt/source-logging-push-back-reader (io/reader source-file) 1 (str source-file))]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(tr/read {:read-cond :allow :features #{:clj} :eof ::eof} reader)))))))

(defn- exact-key-query-key
  "The first map key inside exact-key-query's body, as read."
  [forms]
  (->> forms
       (filter #(and (seq? %) (= 'exact-key-query (second %))))
       (mapcat (fn [form] (filter map? (tree-seq coll? seq form))))
       (some (fn [m] (some #(when (= "map-key-query" (name %)) %) (keys m))))))

(deftest read-keyword-resolution-probe
  (require 'skeptic.analysis.map-ops)
  (let [file (io/file "src/skeptic/analysis/map_ops.clj")
        unbound-key (exact-key-query-key (read-forms-unbound file))
        bound-key (exact-key-query-key (read-forms-bound 'skeptic.analysis.map-ops file))]
    (is (not= :skeptic.analysis.map-ops/map-key-query unbound-key)
        "unbound read mis-resolves :: against ambient *ns* (reproduces Root C)")
    (is (= :skeptic.analysis.map-ops/map-key-query bound-key)
        "bound read resolves :: to the source ns")))

(defn- ast-node? [n] (and (map? n) (contains? n :op)))

(def ^:private cyclic-slots #{:env :atom :info})

(defn- raw-form-reflect?
  "Deep-walk a raw source form (finite tree) for a clojure.reflect.Method in
   any value OR metadata position."
  [form]
  (boolean
   (some (fn [n] (or (instance? clojure.reflect.Method n)
                     (instance? clojure.reflect.Method (meta n))
                     (when-let [m (meta n)]
                       (and (coll? m) (some #(instance? clojure.reflect.Method %) (vals m))))))
         (tree-seq (some-fn coll? meta)
                   (fn [n] (concat (when (coll? n) (seq n))
                                   (when (and (meta n) (coll? (meta n))) (vals (meta n)))))
                   form))))

(defn- shallow-reflect?
  "True if `v` is, or one-level contains, a clojure.reflect.Method. For the
   shippable raw-forms slots, deep-walks the finite form tree incl. metadata."
  [k v]
  (if (#{:raw-forms :form} k)
    (raw-form-reflect? v)
    (or (instance? clojure.reflect.Method v)
        (and (coll? v)
             (some #(instance? clojure.reflect.Method %) (seq v))))))

(defn- reflect-slots
  "Walks the AST strictly via :children (the DAG the wire projection traverses),
   never into cyclic slots (:env/:atom/...). Returns [op slot-key] pairs whose
   value is, or shallowly contains, a clojure.reflect.Method."
  [node]
  (when (ast-node? node)
    (let [child-ks (set (:children node))
          here (for [[k v] node
                     :when (not (child-ks k))
                     :when (not (cyclic-slots k))
                     :when (shallow-reflect? k v)]
                 [(:op node) k])
          kids (mapcat (fn [k]
                         (let [v (get node k)]
                           (mapcat reflect-slots (if (sequential? v) v [v]))))
                       (:children node))]
      (concat here kids))))

(deftest analyze-source-file-reflect-probe
  (require 'skeptic.provenance)
  (let [file (io/file "src/skeptic/provenance.clj")
        result (wac/analyze-source-file 'skeptic.provenance file)
        asts (map :ast (:entries result))
        slots (distinct (mapcat reflect-slots asts))
        src-forms (map :source-form (:entries result))
        src-reflect (filterv raw-form-reflect? src-forms)]
    (is (map? result))
    (when (seq slots)
      (println "reflect.Method AST [op slot] pairs:" (pr-str slots)))
    (when (seq src-reflect)
      (println "reflect.Method in" (count src-reflect) ":source-form(s)"))
    (is (empty? slots)
        "analyzer AST must not carry clojure.reflect.Method in a projected slot")
    (is (empty? src-reflect)
        ":source-form must not carry clojure.reflect.Method")))

(deftest method-value-node-wire-roundtrip-regression
  ;; Regression for the worker->host projection shipping analyzer-internal slots
  ;; raw. skeptic.test-examples.resolution/sample-bigdecimal-method-value-fn uses
  ;; the Clojure 1.12 qualified method-value syntax `(BigDecimal/.equals a b)`,
  ;; which tools.analyzer.jvm lowers to an :instance-call node carrying a
  ;; :methods slot of clojure.reflect.Method records. That slot is not a child,
  ;; so it must be projected away before the host sees it.
  ;; Run the REAL worker subprocess: wc/ask reads the reply through the production
  ;; transport, so a raw reflect.Method would throw here if projection leaked it.
  (let [cp (System/getProperty "java.class.path")
        worker (proc/spawn! cp (System/getProperty "user.dir") false)
        conn (wc/connect false (:port worker))]
    (try
      (let [{:keys [entries]} (wc/ask conn
                                      {:op "analyze-namespace"
                                       :ns "skeptic.test-examples.resolution"
                                       :source-file "test/skeptic/test_examples/resolution.clj"})]
        (is (vector? entries))
        (is (every? #(= % (edn/read-string (pr-str %))) entries)
            "every projected entry must remain readable as plain data"))
      (finally (wc/disconnect! conn) (proc/stop! worker)))))

(deftest projected-entry-wire-roundtrip-probe
  ;; The production transport is binary, but the projection contract still keeps
  ;; entries printable/readable as plain data. Check that invariant on REAL
  ;; projection output.
  (require 'skeptic.provenance)
  (let [file (io/file "src/skeptic/provenance.clj")
        {:keys [entries]} (wac/analyze-source-file 'skeptic.provenance file)
        project-entry @#'server/project-entry
        projected (mapv #(project-entry 'skeptic.provenance %) entries)
        printed (binding [*print-readably* true *print-length* nil *print-level* nil]
                  (pr-str projected))
        outcome (try (edn/read-string printed) :ok
                     (catch Exception e (.getMessage e)))]
    (when (not= :ok outcome)
      (println "WIRE ROUNDTRIP FAILED:" outcome)
      (when-let [m (re-find #"#[\w.]+" printed)]
        (println "first reader-tag in payload:" m)))
    (is (= :ok outcome)
        "projected entries must survive pr-str -> edn/read as plain data")))

(deftest structural-cases-sdefn-root-op-probe
  ;; Class A root: an `s/defn` form macroexpands to a `:let`/`:do` wrapper whose
  ;; root node is NOT `:def` — the real `:def` is nested inside. Def-discovery
  ;; (analyzed-def-entry / ast-by-name) only inspects the per-entry ROOT node
  ;; (unwrap-with-meta, no :do/:let descent), so every s/defn is invisible and
  ;; def-init-node receives nil. A plain `defn` analyzes to a root `:def`, so it
  ;; alone is discovered. This probe pins the root-op divergence.
  (require 'skeptic.test-examples.structural-cases)
  (let [file (io/file "test/skeptic/test_examples/structural_cases.clj")
        {:keys [entries]} (wac/analyze-source-file 'skeptic.test-examples.structural-cases file)
        op+name (map (fn [{:keys [ast]}] [(:op ast) (:name ast)]) entries)
        sdefn-root (some (fn [[op name]] (when (= 'sc-let-if-shape name) op)) op+name)]
    (println "structural-cases entries [op name]:" (pr-str op+name))
    (is (= :def sdefn-root)
        "the s/defn-declared sc-let-if-shape must analyze to a root :def node")))
