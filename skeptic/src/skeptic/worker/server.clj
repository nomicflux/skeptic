(ns skeptic.worker.server
  "Worker-side EDN nREPL server. Runs in the spawned JVM on the project's
   classpath and answers host requests on demand. Plan 2 Phase 1.5 adds the
   handle-table machinery: every Class operand on the wire is an opaque handle
   (integer for bootstrap-interned host-runtime classes; UUID-string for project
   classes). Worker is sole owner of `Class/forName`, `.isAssignableFrom`,
   `instance?`, and class equality."
  (:require [schema.core :as s]
            [nrepl.server :as srv]
            [nrepl.transport :as t]
            [nrepl.middleware :as mw]
            [nrepl.misc :refer [response-for]]))

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

(s/defn wrap-ping :- (s/=> s/Any s/Any)
  [h :- (s/=> s/Any s/Any)]
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

(s/defn wrap-intern-host-classes :- (s/=> s/Any s/Any)
  [h :- (s/=> s/Any s/Any)]
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

(s/defn wrap-class-rel :- (s/=> s/Any s/Any)
  [h :- (s/=> s/Any s/Any)]
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

(s/defn wrap-resolve-class-sym :- (s/=> s/Any s/Any)
  [h :- (s/=> s/Any s/Any)]
  (fn [{:keys [op transport ns sym] :as msg}]
    (if (= op "resolve-class-sym")
      (t/send transport (response-for msg
                                      :handle (resolve-sym-to-handle ns sym)
                                      :status #{:done}))
      (h msg))))

(mw/set-descriptor! #'wrap-resolve-class-sym
                    {:requires #{} :expects #{} :handles {"resolve-class-sym" {}}})

(s/defn start! :- s/Any
  []
  (srv/start-server :port 0
                    :transport-fn t/edn
                    :handler (srv/default-handler #'wrap-ping
                                                  #'wrap-intern-host-classes
                                                  #'wrap-class-rel
                                                  #'wrap-resolve-class-sym)))

(s/defn -main :- s/Any
  [& _args]
  (let [server (start!)]
    (println (str "SKEPTIC-WORKER-PORT " (:port server)))
    (flush)
    @(promise)))
