(ns skeptic.schematize
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skeptic.schema :as dschema]
            [schema.core :as s])
  (:import [org.apache.commons.io IOUtils]
           [clojure.lang Named RT]))

(defn get-fn-schemas*
  [f]
  (->> f
       resolve
       meta
       :schema
       (into {})))

(defmacro get-fn-schemas
  [f]
  `(get-fn-schemas* '~f))

(defn try-resolve
  [x]
  (if (symbol? x)
    (try (let [resolved (resolve x)]
           (if (nil? resolved)
             x
             (symbol resolved)))
         (catch Exception _e
           x))
    x))

(defn into-coll
  [f x]
  (cond
    (nil? x) []
    (coll? x) (mapcat #(into-coll f %) x)
    :else [(f x)]))

(defn get-meta
  [x]
  (into-coll (fn [y] (try {:meta (meta (resolve y))
                          :var y}
                         (catch Exception _e
                           {:no-meta y})))
             x))

(s/defn get-fn-code :- s/Str
  [func-name :- s/Symbol]
  (-> func-name
       repl/source-fn))

(s/defn macroexpand-all
  [f]
  (walk/postwalk macroexpand f))

(s/defn deref-vars
  [f]
  (walk/postwalk (fn [x] (cond-> x
                          (and (var? x) (deref x))
                          (comp symbol deref)))
                 f))

(s/defn resolve-once
  [f]
  (->> f
       macroexpand-all
       (walk/postwalk try-resolve)))

(s/defn resolve-all
  ([f]
   ;; 8 is completely arbitrary. The goal is just to cut off any sort of infinite regression,
   ;; and if we have to resolve more than 8 times, we should look into what is going wrong.
   (resolve-all f 8))
  ([f n]
   (loop [f f
          n n]
     (let [resolved (resolve-once f)]
       (if (or (= resolved f) (zero? n))
         f
         (recur resolved (dec n)))))))

(s/defn resolve-code-references
  [fn-code :- s/Str]
  (->> fn-code
       read-string
       resolve-all))

(s/defn get-own-schema
  [fn-name :- s/Symbol]
  (-> fn-name
      try-resolve
      get-meta))

(s/defn get-schema-lookup
  [fn-name :- s/Symbol]
  (remove :no-meta
          (concat
           (->> fn-name
                get-fn-code
                resolve-code-references
                get-meta)
           (get-own-schema fn-name))))

(s/defn count-map
  [x :- [s/Any]]
  (->> x
       (map (juxt count identity))
       (into {})))

(s/defn arg-list
  [args]
  (reduce
   (fn [{:keys [count args varargs with-varargs]}
       next]
     (cond
       with-varargs
       {:count count
        :args args
        :with-varargs with-varargs
        :varargs (conj varargs next)}

       (str/starts-with? next "&")
       {:count (inc count)
        :args args
        :with-varargs true
        :varargs varargs}

       :else
       {:count (inc count)
        :args (conj args next)
        :with-varargs with-varargs
        :varargs varargs}))
   {:count 0
    :args []
    :with-varargs false
    :varargs []}
   args))

(s/defn arg-map
  [xs :- [s/Any]]
  (reduce
   (fn [acc next]
     (let [{:keys [count args varargs]} (arg-list next)]
       (if (seq varargs)
         (assoc acc :varargs {:args (conj args varargs) :count count})
         (assoc acc count args))))
   {}
   xs))

(s/defn collect-schemas :- [dschema/SchemaDesc]
  [{:keys [schema ns name arglists]}]
  (let [{:keys [input-schemas output-schema]} (into {} schema)
        inputs (count-map input-schemas)
        args (arg-map arglists)
        args-with-schemas (reduce
                           (fn [acc next]
                             (let [input (get inputs next)
                                   arg (get args next)]
                               (assoc acc
                                      next
                                      (cond-> {:arglist arg}
                                        (= next :varargs)
                                        (assoc :count (:count arg)
                                               :arglist (:args arg)
                                               :schema (get inputs (:count arg)))

                                        (not (nil? input))
                                        (assoc :schema input)))))
                           {}
                           (keys args))]
    {:name (str ns "/" name)
     :schema schema
     :output (or output-schema schema)
     :arglists args-with-schemas}))

(s/defn fully-qualify-str :- s/Symbol
  [f :- s/Str]
  (-> f
      symbol
      resolve
      symbol))

(s/defn attach-schema-info-to-qualified-symbol
  [f :- s/Symbol]
  (->> f
       get-schema-lookup
       (map :meta)
       (map collect-schemas)
       (reduce (fn [acc {:keys [name] :as next}] (update acc name merge-with next)) {})))

(s/defn get-schema-info
  [f :- s/Str]
  (->> f
       fully-qualify-str
       attach-schema-info-to-qualified-symbol))

;; https://stackoverflow.com/questions/45555191/is-there-a-way-to-get-clojure-files-source-when-a-namespace-provided
(s/defn source-clj
  [ns]
  (require ns)
  (some->> ns
           ns-publics
           vals
           first
           meta
           :file
           (.getResourceAsStream (RT/baseLoader))
           IOUtils/toString))

(defn ns-schemas
  [ns]
  (->> ns
       symbol
       ns-publics
       vals
       (map symbol)
       (map attach-schema-info-to-qualified-symbol)
       (reduce merge {})))
