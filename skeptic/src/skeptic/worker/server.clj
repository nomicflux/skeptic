(ns skeptic.worker.server
  "Worker-side EDN nREPL server. Runs in the spawned JVM on the project's
   classpath and answers host requests on demand. Plan 2 Phase 1.5 adds the
   handle-table machinery: every Class operand on the wire is an opaque handle
   (integer for bootstrap-interned host-runtime classes; UUID-string for project
   classes). Worker is sole owner of `Class/forName`, `.isAssignableFrom`,
   `instance?`, and class equality.

   Phase 5 adds the analyzer + discover ops. The analyzer-execution glue lives
   in `skeptic.worker.analyzer-clj` / `skeptic.worker.analyzer-cljs`; the
   discovery walker lives in `skeptic.worker.discovery`. No other `skeptic.*`
   namespace is required from this server: Skeptic's own analysis code and
   Plumatic Schema / Malli stay on the host (B3/B4)."
  (:require [nrepl.server :as srv]
            [nrepl.transport :as t]
            [nrepl.middleware :as mw]
            [nrepl.misc :refer [response-for]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [skeptic.worker.analyzer-clj :as wac]
            [skeptic.worker.analyzer-cljs :as wacljs]
            [skeptic.worker.discovery :as wdisc]))

(defonce ^:private handle-state
  (atom {:next-id 0 :id->class {} :class->id {}}))

(defn- intern-class!
  "Returns the existing handle for `c` if interned, else mints a new one. When
   `id-kind` is `:integer` (bootstrap path) the new id is an integer counter;
   when `:uuid` (resolve/project path) it is a UUID string. Bootstrap classes
   get integer ids because their identity is guaranteed unique by the JVM
   bootloader rules (D12); project classes get UUIDs."
  [^Class c id-kind]
  (let [{:keys [class->id]} @handle-state]
    (or (get class->id c)
        (let [new-id (case id-kind
                       :integer (-> (swap! handle-state update :next-id inc)
                                    :next-id)
                       :uuid    (str (java.util.UUID/randomUUID)))]
          (swap! handle-state
                 (fn [{:keys [id->class class->id] :as s}]
                   (assoc s
                          :id->class (assoc id->class new-id c)
                          :class->id (assoc class->id c new-id))))
          new-id))))

(defn- handle->class
  "Returns the ^Class for handle `id`, or nil if not interned."
  [id]
  (get (:id->class @handle-state) id))

(defn wrap-ping
  [h]
  (fn [{:keys [op transport] :as msg}]
    (if (= op "ping")
      (t/send transport (response-for msg :pong "ok" :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-ping {:requires #{} :expects #{} :handles {"ping" {}}})

(defn- intern-host-classes-reply
  "Per-name: try `Class/forName`; on success intern as integer; on CNFE skip.
   Returns the `{name handle-id}` map for names that resolved."
  [class-names]
  (reduce (fn [acc nm]
            (try (let [c (Class/forName nm)]
                   (assoc acc nm (intern-class! c :integer)))
                 (catch ClassNotFoundException _ acc)))
          {} class-names))

(defn wrap-intern-host-classes
  [h]
  (fn [{:keys [op transport class-names] :as msg}]
    (if (= op "intern-host-classes")
      (t/send transport (response-for msg
                                      :handles (intern-host-classes-reply class-names)
                                      :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-intern-host-classes
                    {:requires #{} :expects #{} :handles {"intern-host-classes" {}}})

(defn- run-class-rel
  "Dispatches a class relation against handle operands. `:a` is always a handle.
   For `:instance?` `:b` is a runtime value; otherwise `:b` is a handle."
  [rel a b]
  (let [ca (handle->class a)]
    (case rel
      :assignable-from (boolean (and ca (when-let [cb (handle->class b)]
                                          (.isAssignableFrom ^Class ca ^Class cb))))
      :equals          (boolean (and ca (= ca (handle->class b))))
      :instance?       (boolean (and ca (instance? ^Class ca b))))))

(defn wrap-class-rel
  [h]
  (fn [{:keys [op transport rel a b] :as msg}]
    (if (= op "class-rel")
      (t/send transport (response-for msg :result (run-class-rel rel a b) :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-class-rel
                    {:requires #{} :expects #{} :handles {"class-rel" {}}})

(defn- resolve-sym-to-handle
  "In the namespace `ns-name`, resolve `sym` to a Class and return its handle
   (minting via the UUID branch). nil if `sym` doesn't resolve to a Class."
  [ns-name sym]
  (when-let [n (find-ns (symbol ns-name))]
    (binding [*ns* n]
      (let [v (resolve (symbol sym))]
        (when (class? v)
          (intern-class! ^Class v :uuid))))))

(defn wrap-resolve-class-sym
  [h]
  (fn [{:keys [op transport ns sym] :as msg}]
    (if (= op "resolve-class-sym")
      (t/send transport (response-for msg
                                      :handle (resolve-sym-to-handle ns sym)
                                      :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-resolve-class-sym
                    {:requires #{} :expects #{} :handles {"resolve-class-sym" {}}})

(defn- project-class-slot
  "If `v` is a ^Class, replace it with a UUID handle (bootstrap classes
   short-circuit through the integer cache) and return [handle display-name].
   Otherwise return [v nil]."
  [v]
  (if (class? v)
    [(intern-class! ^Class v :uuid) (.getName ^Class v)]
    [v nil]))

(def ^:private class-slot-keys
  {:class :class-display-name
   :tag :tag-display-name
   :val :val-display-name})

(defn- project-class-slots
  "Replace any ^Class in `:class`/`:tag`/`:val` of `n` with a handle and
   populate the sibling display-name field."
  [n]
  (reduce-kv (fn [acc k disp-k]
               (if (contains? acc k)
                 (let [[h disp] (project-class-slot (get acc k))]
                   (cond-> (assoc acc k h)
                     disp (assoc disp-k disp)))
                 acc))
             n
             class-slot-keys))

(defn- handle-project-node
  "Recursively projects every Class in `n` to a handle. Descends only via
   `:children` (mirrors `walk-ast` / `find-by-op*` pruning) so non-AST slots
   such as `:env`, `:info`, `:meta` are never followed."
  [n]
  (if-not (and (map? n) (contains? n :op))
    n
    (let [n' (project-class-slots n)]
      (reduce (fn [a k]
                (let [v (get a k)]
                  (cond
                    (and (map? v) (contains? v :op))
                    (assoc a k (handle-project-node v))
                    (and (vector? v) (seq v) (every? #(and (map? %) (contains? % :op)) v))
                    (assoc a k (mapv handle-project-node v))
                    :else a)))
              n'
              (:children n')))))

(defn wrap-discover-ns
  [h]
  (fn [{:keys [op transport ns source-file] :as msg}]
    (if (= op "discover-ns")
      (let [result (wdisc/discover (symbol ns) (io/file source-file))]
        (t/send transport (response-for msg
                                        :result (dissoc result :source-forms)
                                        :status #{:done})))
      (h msg))))

(mw/set-descriptor! #'wrap-discover-ns
                    {:requires #{} :expects #{} :handles {"discover-ns" {}}})

(defn wrap-analyze-form-clj
  [h]
  (fn [{:keys [op transport ns source-file form locals] :as msg}]
    (if (= op "analyze-form-clj")
      (let [form-val (edn/read-string form)
            opts {:locals (edn/read-string locals)
                  :ns (symbol ns)
                  :source-file source-file}
            ast (handle-project-node (wac/analyze form-val opts))]
        (t/send transport (response-for msg :ast ast :status #{:done})))
      (h msg))))

(mw/set-descriptor! #'wrap-analyze-form-clj
                    {:requires #{} :expects #{} :handles {"analyze-form-clj" {}}})

(defn wrap-parse-cljs-ns
  [h]
  (fn [{:keys [op transport source-file] :as msg}]
    (if (= op "parse-cljs-ns")
      (let [ns-key (wacljs/parse-ns (io/file source-file))]
        (t/send transport (response-for msg :ns-key ns-key :status #{:done})))
      (h msg))))

(mw/set-descriptor! #'wrap-parse-cljs-ns
                    {:requires #{} :expects #{} :handles {"parse-cljs-ns" {}}})

(defn wrap-analyze-form-cljs
  [h]
  (fn [{:keys [op transport ns-key form] :as msg}]
    (if (= op "analyze-form-cljs")
      (let [form-val (edn/read-string form)
            ast (handle-project-node (wacljs/analyze-form-by-ns-key ns-key form-val))]
        (t/send transport (response-for msg :ast ast :status #{:done})))
      (h msg))))

(mw/set-descriptor! #'wrap-analyze-form-cljs
                    {:requires #{} :expects #{} :handles {"analyze-form-cljs" {}}})

(defn start!
  []
  (srv/start-server :port 0
                    :transport-fn t/edn
                    :handler (srv/default-handler #'wrap-ping
                                                  #'wrap-intern-host-classes
                                                  #'wrap-class-rel
                                                  #'wrap-resolve-class-sym
                                                  #'wrap-discover-ns
                                                  #'wrap-analyze-form-clj
                                                  #'wrap-parse-cljs-ns
                                                  #'wrap-analyze-form-cljs)))

(defn -main
  [& _args]
  (let [server (start!)]
    (println (str "SKEPTIC-WORKER-PORT " (:port server)))
    (flush)
    @(promise)))
