(ns skeptic.worker.harness-test
  "Committed two-JVM round-trips: spawn a real worker JVM, connect over the Nippy
   transport, exercise each handle-shaped op, tear the worker down. Plan 2
   Phase 1.5 extends the original ping round-trip with the handle-table API."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skeptic.worker.process :as proc]
            [skeptic.worker.client :as wc]
            [skeptic.analysis.class-oracle :as oracle])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- cp-string
  [entries]
  (str/join java.io.File/pathSeparator entries))

(defn with-worker
  "Spawns a worker, runs `f` with a connected client, tears down. Combined
   launch cp is project-cp first (so project versions win on shared libs),
   then the host's classpath."
  ([f]
   (with-worker nil f))
  ([project-cp f]
   (let [host-cp (System/getProperty "java.class.path")
         combined (if project-cp
                    (cp-string (concat project-cp [host-cp]))
                    host-cp)
         worker (proc/spawn! combined false)
         conn (wc/connect (:port worker))]
     (try
       (f conn)
       (finally
         (wc/disconnect! conn)
         (proc/stop! worker))))))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-worker-harness-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

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

(deftest persistent-client-reuse-round-trip
  (with-worker
    (fn [conn]
      (let [m (oracle/intern-host-classes! conn)]
        (binding [oracle/*worker-conn* conn
                  oracle/*host-class-handles* m]
          (let [long-h (oracle/resolve-class-sym 'clojure.core 'Long)]
            (testing "50 interleaved ops over one persistent client each match their request"
              (doseq [i (range 50)]
                (if (even? i)
                  (is (= "ok" (:pong (wc/ask conn {:op "ping"}))))
                  (is (true? (:result (wc/ask conn {:op "class-rel"
                                                    :rel :equals
                                                    :a long-h
                                                    :b long-h})))))))))))))

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
        (testing "projection remains readable as plain data"
          (is (every? #(= % (edn/read-string (pr-str %))) entries)))
        (testing "every class-identity :class slot is a handle, never a Class"
          (let [classes (mapcat leaf-class-slots asts)]
            (is (seq classes))
            (is (every? oracle/handle? classes))))))))

(deftest analyze-namespace-resolves-referred-vars-and-project-macros-in-one-context
  (let [dir (temp-dir!)
        src (io/file dir "src")]
    (try
      (.mkdirs (io/file src "demo"))
      (spit (io/file src "demo" "context_marker.txt") "project")
      (spit (io/file src "demo" "helper.clj")
            "(ns demo.helper
               (:require [clojure.java.io :as io]))

             (defmacro require-project-context! []
               (when-not (io/resource \"demo/context_marker.txt\")
                 (throw (ex-info \"project context resource was not visible\" {})))
               nil)")
      (spit (io/file src "demo" "core.clj")
            "(ns demo.core
               (:require [clojure.test :refer [use-fixtures]]
                         [demo.helper :refer [require-project-context!]]))

             (defn fixture [f] (f))
             (use-fixtures :once fixture)
             (require-project-context!)
             (defn value [] 1)")
      (with-worker [(.getPath src)]
        (fn [conn]
          (let [{:keys [entries read-failure]} (wc/ask conn
                                                       {:op "analyze-namespace"
                                                        :ns "demo.core"
                                                        :source-file (.getPath (io/file src "demo" "core.clj"))})
                source-forms (mapv :source-form entries)]
            (is (nil? read-failure))
            (is (= '[ns defn use-fixtures require-project-context! defn]
                   (mapv first source-forms)))
            (is (every? #(contains? % :ast) entries)))))
      (finally
        (delete-recursively! dir)))))

(deftest analyze-namespace-uses-project-runtime-data-readers
  (let [dir (temp-dir!)
        src (io/file dir "src")
        timestamp "2026-06-09T12:34:56Z"]
    (try
      (.mkdirs (io/file src "demo"))
      (spit (io/file src "demo" "tag_reader.clj")
            "(ns demo.tag-reader)

             (defn read-date-time [value]
               {:date-time value})

             (alter-var-root #'clojure.core/*data-readers*
                             assoc
                             'date-time
                             #'read-date-time)")
      (spit (io/file src "demo" "tagged.clj")
            (str "(ns demo.tagged\n"
                 "  (:require [demo.tag-reader]))\n\n"
                 "(def value #date-time \"" timestamp "\")\n"))
      (with-worker [(.getPath src)]
        (fn [conn]
          (let [{:keys [entries read-failure]} (wc/ask conn
                                                       {:op "analyze-namespace"
                                                        :ns "demo.tagged"
                                                        :source-file (.getPath (io/file src "demo" "tagged.clj"))})
                value-form (some #(when (= 'value (second %)) %) (map :source-form entries))]
            (is (nil? read-failure))
            (is (= {:date-time timestamp} (nth value-form 2))))))
      (finally
        (delete-recursively! dir)))))

(deftest analyze-namespace-uses-project-runtime-default-data-reader
  (let [dir (temp-dir!)
        src (io/file dir "src")
        timestamp "2026-06-09T12:34:56Z"]
    (try
      (.mkdirs (io/file src "demo"))
      (spit (io/file src "demo" "tag_reader.clj")
            "(ns demo.tag-reader)

             (defn read-tag [tag value]
               (if (= 'date-time tag)
                 {:date-time value}
                 (throw (ex-info \"Unknown reader tag\" {:tag tag}))))

             (alter-var-root #'clojure.core/*default-data-reader-fn*
                             (constantly read-tag))")
      (spit (io/file src "demo" "tagged.clj")
            (str "(ns demo.tagged\n"
                 "  (:require [demo.tag-reader]))\n\n"
                 "(def value #date-time \"" timestamp "\")\n"))
      (with-worker [(.getPath src)]
        (fn [conn]
          (let [{:keys [entries read-failure]} (wc/ask conn
                                                       {:op "analyze-namespace"
                                                        :ns "demo.tagged"
                                                        :source-file (.getPath (io/file src "demo" "tagged.clj"))})
                value-form (some #(when (= 'value (second %)) %) (map :source-form entries))]
            (is (nil? read-failure))
            (is (= {:date-time timestamp} (nth value-form 2))))))
      (finally
        (delete-recursively! dir)))))

(deftest ask-streaming-loopback
  (let [conn (wc/loopback-conn (fn [msg] {:result (:input msg)}))
        replies (atom [])]
    (wc/ask-streaming conn {:op "echo" :input 42}
                      (fn [reply] (swap! replies conj reply)))
    (testing "loopback calls on-reply once with the handler result"
      (is (= [{:result 42}] @replies)))))

(deftest ask-streaming-real-transport
  (with-worker
    (fn [conn]
      (let [replies (atom [])]
        (wc/ask-streaming conn {:op "ping"}
                          (fn [reply] (swap! replies conj reply)))
        (testing "ping is a done-only op; on-reply is never called"
          (is (= [] @replies)))))))

(deftest analyze-namespaces-stream-round-trip
  (with-worker
    (fn [conn]
      (oracle/intern-host-classes! conn)
      (let [nss [["skeptic.research.projection-fixture"
                  "test/skeptic/research/projection_fixture.clj"]
                 ["skeptic.colours"
                  "src/skeptic/colours.clj"]]
            replies (atom [])
            _ (wc/ask-streaming conn
                               {:op "analyze-namespaces-stream"
                                :namespaces nss}
                               (fn [reply] (swap! replies conj reply)))
            starting (filterv :starting? @replies)
            data-replies (filterv #(not (:starting? %)) @replies)]
        (testing "each namespace produces a :starting? marker then a data reply"
          (is (= 2 (count starting)))
          (is (= 2 (count data-replies))))
        (testing "each data reply carries :ns-sym and :entries"
          (is (every? #(contains? % :ns-sym) data-replies))
          (is (every? #(contains? % :entries) data-replies)))
        (testing "entries match individual analyze-namespace results"
          (doseq [[ns-str source-file] nss]
            (let [stream-reply (some #(when (= ns-str (:ns-sym %)) %) data-replies)
                  single-reply (wc/ask conn {:op "analyze-namespace"
                                             :ns ns-str
                                             :source-file source-file})]
              (is (= (count (:entries single-reply))
                     (count (:entries stream-reply)))))))))))

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
