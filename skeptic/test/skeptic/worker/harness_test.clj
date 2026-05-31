(ns skeptic.worker.harness-test
  "Committed two-JVM round-trips: spawn a real worker JVM, connect over the EDN
   transport, exercise each handle-shaped op, tear the worker down. Plan 2
   Phase 1.5 extends the original ping round-trip with the handle-table API."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [skeptic.worker.process :as proc]
            [skeptic.worker.client :as wc]
            [skeptic.analysis.class-oracle :as oracle]))

(defn with-worker
  "Spawns a worker, runs `f` with a connected client, tears down."
  [f]
  (let [cp (proc/worker-classpath (System/getProperty "java.class.path"))
        worker (proc/spawn! cp)]
    (try
      (f (wc/connect (:port worker)))
      (finally
        (proc/stop! worker)))))

(deftest worker-ping-round-trip
  (with-worker
    (fn [conn]
      (is (= "ok" (:pong (wc/ask conn {:op "ping"})))))))

(deftest intern-host-classes-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (testing "Number has a handle"
          (is (some? (get m Number))))
        (testing "every handle is an integer"
          (is (every? integer? (vals m))))
        (testing "every key is a Class"
          (is (every? class? (keys m))))
        (testing "host-handle returns the same handle as the map"
          (binding [oracle/*host-class-handles* m]
            (is (= (get m Number) (oracle/host-handle Number)))))))))

(deftest class-rel-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)
            number-h (get m Number)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [long-h (oracle/resolve-class-sym 'clojure.core 'Long)]
            (testing "Long resolves to a handle (integer: Long is bootstrap-interned)"
              (is (oracle/handle? long-h)))
            (testing ":assignable-from Number<-Long is true"
              (is (true? (oracle/class-rel :assignable-from number-h long-h))))
            (testing ":assignable-from Long<-Number is false"
              (is (false? (oracle/class-rel :assignable-from long-h number-h))))
            (testing ":equals Long Long is true"
              (is (true? (oracle/class-rel :equals long-h long-h))))))))))

(deftest class-rel-batch-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)
            number-h (get m Number)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [long-h (oracle/resolve-class-sym 'clojure.core 'Long)
                triples [{:rel :assignable-from :a number-h :b long-h}
                         {:rel :assignable-from :a long-h :b number-h}
                         {:rel :equals :a long-h :b long-h}]
                {:keys [results]} (wc/ask conn {:op "class-rel-batch" :triples triples})]
            (testing "batched results match the per-call class-rel answers, positionally"
              (is (= [true false true] results))
              (is (= (mapv #(oracle/class-rel (:rel %) (:a %) (:b %)) triples)
                     results)))))))))

(deftest resolve-class-sym-round-trip
  (with-worker
    (fn [conn]
      (binding [oracle/*worker-conn* conn]
        (testing "String resolves to a non-nil UUID handle"
          (let [h (oracle/resolve-class-sym 'clojure.core 'String)]
            (is (some? h))
            (is (oracle/handle? h))))
        (testing "NoSuchClassZZZ resolves to nil"
          (is (nil? (oracle/resolve-class-sym 'clojure.core 'NoSuchClassZZZ))))))))

(defn- leaf-class-slots
  "Leaf :class slot values reachable in a projected AST. A :class slot that
   holds a child AST node (e.g. a :catch node's :class) is a map, not a class
   identity, and is excluded; class identities are the leaf (non-collection)
   :class values."
  [ast]
  (->> (tree-seq coll? seq ast)
       (filter #(and (map? %) (contains? % :class)))
       (map :class)
       (remove coll?)))

(def ^:private ast-class-slots
  {:class :class-display-name
   :tag :tag-display-name
   :val :val-display-name})

(defn- ast-class-handles
  [ast]
  (->> (tree-seq coll? seq ast)
       (filter map?)
       (mapcat (fn [node]
                 (keep (fn [[slot display-slot]]
                         (when (and (contains? node display-slot)
                                    (oracle/handle? (get node slot)))
                           (get node slot)))
                       ast-class-slots)))))

(defn- encoded-schema-class-handles
  [encoded]
  (->> (tree-seq coll? seq encoded)
       (filter #(and (map? %) (= :class (:tag %))))
       (map :handle)
       (filter oracle/handle?)))

(defn- entry-class-handles
  [entry]
  (concat (ast-class-handles (:ast entry))
          (encoded-schema-class-handles (get-in entry [:plumatic-schema :schema]))))

(defn- handles-by-worker-class-name
  [conn handles]
  (reduce (fn [acc handle]
            (if-let [class-name (:name (wc/ask conn {:op "class-name" :a handle}))]
              (update acc class-name (fnil conj #{}) handle)
              acc))
          {}
          (distinct handles)))

(deftest analyze-namespace-round-trip
  (with-worker
    (fn [conn]
      (oracle/intern-host-classes! conn)
      (let [{:keys [entries]} (wc/ask conn
                                      {:op "analyze-namespace"
                                       :ns "skeptic.research.projection-fixture"
                                       :source-file "test/skeptic/research/projection_fixture.clj"})
            asts (mapv :ast entries)]
        (testing "worker ships a {:source-form :ast} entry per top-level form"
          (is (vector? entries))
          (is (= 6 (count entries)))
          (is (every? #(contains? % :source-form) entries))
          (is (every? #(contains? % :ast) entries)))
        (testing "every projected entry round-trips through the EDN reader"
          (is (every? #(= % (edn/read-string (pr-str %))) entries)))
        (testing "every class-identity :class slot is a handle, never a Class"
          (let [classes (mapcat leaf-class-slots asts)]
            (is (seq classes))
            (is (every? oracle/handle? classes))))))))

(deftest analyze-namespace-does-not-duplicate-project-record-class-handles
  (with-worker
    (fn [conn]
      (oracle/intern-host-classes! conn)
      (let [{:keys [entries]} (wc/ask conn
                                      {:op "analyze-namespace"
                                       :ns "skeptic.analysis.types"
                                       :source-file "src/skeptic/analysis/types.clj"})
            dyn-entry (some #(when (= 'DynTRec (second (:source-form %))) %) entries)
            name->handles (handles-by-worker-class-name conn (mapcat entry-class-handles entries))
            duplicate-record-handles (into {}
                                           (filter (fn [[class-name handles]]
                                                     (and (str/starts-with? class-name "skeptic.analysis.types.")
                                                          (str/ends-with? class-name "Rec")
                                                          (< 1 (count handles)))))
                                           name->handles)]
        (testing "top-level defrecord declarations stay available as source forms but are not analyzed"
          (is (= 'defrecord (first (:source-form dyn-entry))))
          (is (:analysis-skipped? dyn-entry))
          (is (nil? (:ast dyn-entry))))
        (testing "worker does not emit distinct handles for the same project record class name"
          (is (= {} duplicate-record-handles)))))))
