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
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [skeptic.worker.analyzer-clj :as wac]
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

(defn- ast-node? [v] (and (map? v) (contains? v :op)))

(defn- strip-host-unread
  "Remove non-EDN host slots. Strips :atom :env :o-tag :return-tag.
   Strips :info but retains [:info :name]. Strips :meta only when it is
   raw var-metadata (no :op key)."
  [n]
  (let [info-name (get-in n [:info :name])
        n' (dissoc n :atom :env :o-tag :return-tag :info)
        n' (if info-name (assoc-in n' [:info :name] info-name) n')
        meta-val (:meta n')]
    (if (and (some? meta-val) (not (ast-node? meta-val)))
      (dissoc n' :meta)
      n')))

(defn- edn-safe?
  [v]
  (cond
    (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) true
    (map? v) (every? (fn [[k mv]] (and (edn-safe? k) (edn-safe? mv))) v)
    (coll? v) (every? edn-safe? v)
    :else (try (= (pr-str v) (pr-str (edn/read-string (pr-str v)))) (catch Exception _ false))))

(defn- nonedn-sentinel [v] {::nonedn true ::class (intern-class! (class v) :uuid)})

(defn- project-val-form
  "For :val/:form slots: Class→handle path already handled by project-class-slots.
   Non-EDN non-Class value → sentinel (preserving form metadata)."
  [v]
  (if (edn-safe? v)
    v
    (let [s (nonedn-sentinel v)
          m (meta v)]
      (if m (with-meta s m) s))))

(defn- project-raw-forms
  "Deep-walk a :raw-forms structure (raw pre-macroexpansion source forms),
   sentinelling non-EDN leaves (e.g. regex Patterns) while preserving the
   surrounding structure the host's unanalyze/raw-form-value walk over."
  [raw-forms]
  (walk/postwalk (fn [x] (if (coll? x) x (project-val-form x))) raw-forms))

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
   populate the sibling display-name field. For :val/:form, apply sentinel
   to non-EDN non-Class values."
  [n]
  (let [n' (reduce-kv (fn [acc k disp-k]
                        (if (contains? acc k)
                          (let [[h disp] (project-class-slot (get acc k))]
                            (cond-> (assoc acc k h)
                              disp (assoc disp-k disp)))
                          acc))
                      n
                      class-slot-keys)]
    (cond-> n'
      (contains? n' :form) (update :form project-val-form)
      (and (contains? n' :val) (not (class? (:val n)))) (update :val project-val-form)
      (contains? n' :raw-forms) (update :raw-forms project-raw-forms))))

(defn- project-var-slot
  "Replace a clojure.lang.Var in :var with the qualified symbol ns/name."
  [n]
  (let [v (:var n)]
    (if (instance? clojure.lang.Var v)
      (assoc n :var (symbol (str (ns-name (.ns ^clojure.lang.Var v)))
                            (name (.sym ^clojure.lang.Var v))))
      n)))

(defn- project-child
  "Apply the runner `f` to a child slot: a single AST node, a vector of AST
   nodes, or pass the value through unchanged."
  [f v]
  (cond
    (ast-node? v) (f v)
    (and (vector? v) (seq v) (every? ast-node? v)) (mapv f v)
    :else v))

(defn- handle-project-node
  "Full projection runner. Strips host-unread slots, projects Class handles,
   sentinels non-EDN :val/:form, replaces :var with a qualified symbol, then
   recurses into all child AST nodes (the :children keys plus :meta when it is
   an AST child). Sole locus of recursion; project-child is non-recursive."
  [n]
  (if-not (ast-node? n)
    n
    (let [n' (-> n strip-host-unread project-class-slots project-var-slot)
          child-keys (into (vec (:children n'))
                           (when (ast-node? (:meta n')) [:meta]))
          descend (fn [a k] (assoc a k (project-child handle-project-node (get a k))))]
      (reduce descend n' child-keys))))

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

(defn wrap-analyze-namespace
  [h]
  (fn [{:keys [op transport ns source-file] :as msg}]
    (if (= op "analyze-namespace")
      (let [{:keys [asts]} (wac/analyze-source-file (symbol ns) (io/file source-file))
            projected (mapv handle-project-node asts)]
        (t/send transport (response-for msg :asts projected :status #{:done})))
      (h msg))))

(mw/set-descriptor! #'wrap-analyze-namespace
                    {:requires #{} :expects #{} :handles {"analyze-namespace" {}}})

(defn start!
  []
  (srv/start-server :port 0
                    :transport-fn t/edn
                    :handler (srv/default-handler #'wrap-ping
                                                  #'wrap-intern-host-classes
                                                  #'wrap-class-rel
                                                  #'wrap-resolve-class-sym
                                                  #'wrap-discover-ns
                                                  #'wrap-analyze-namespace)))

(defn -main
  [& _args]
  (let [server (start!)]
    (println (str "SKEPTIC-WORKER-PORT " (:port server)))
    (flush)
    @(promise)))
