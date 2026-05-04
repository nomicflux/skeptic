(ns skeptic.analysis.predicate-descriptor
  (:require [skeptic.analysis.calls :as ac]))

(defn- comp-set-classifier?
  [form]
  (and (seq? form)
       (#{'comp 'clojure.core/comp} (first form))
       (= 3 (count form))
       (set? (second form))
       (symbol? (nth form 2))))

(defn- equality-call?
  [form]
  (and (seq? form)
       (#{'= 'clojure.core/=} (first form))
       (= 3 (count form))))

(defn- fn-body
  [form]
  (when (and (seq? form)
             (#{'fn 'fn* 'clojure.core/fn} (first form)))
    (let [body (rest form)]
      (cond
        (and (= 2 (count body))
             (vector? (first body)))
        {:params (first body)
         :body (second body)}

        (and (= 1 (count body))
             (seq? (first body))
             (= 2 (count (first body)))
             (vector? (ffirst body)))
        {:params (ffirst body)
         :body (second (first body))}))))

(defn- qualify-sym
  [sym ns-sym]
  (cond
    (nil? sym) nil
    (qualified-symbol? sym) sym
    (and ns-sym (symbol? sym)) (symbol (str ns-sym) (name sym))
    :else sym))

(defn- classifier-summary
  [classifier-sym ns-sym accessor-summaries]
  (get accessor-summaries (qualify-sym classifier-sym ns-sym)))

(defn- qualified-classifier-sym
  [classifier-sym ns-sym _accessor-summaries]
  (qualify-sym classifier-sym ns-sym))

(defn- selected-keys
  [cases literal-set]
  (vec (sort (keep (fn [[k v]] (when (contains? literal-set v) k)) cases))))

(defn- literal-form?
  [form]
  (or (keyword? form)
      (string? form)
      (number? form)
      (boolean? form)
      (nil? form)))

(defn- classifier-call-for-param
  [form param]
  (when (and (seq? form)
             (= 2 (count form))
             (symbol? (first form))
             (= param (second form)))
    (first form)))

(defn- equality-classifier-descriptor
  [body params ns-sym accessor-summaries]
  (when (and (= 1 (count params))
             (equality-call? body))
    (let [[left right] (rest body)
          param (first params)
          [lit classifier-sym] (cond
                                 (and (literal-form? left)
                                      (classifier-call-for-param right param))
                                 [left (classifier-call-for-param right param)]

                                 (and (literal-form? right)
                                      (classifier-call-for-param left param))
                                 [right (classifier-call-for-param left param)])]
      (when classifier-sym
        (let [summary (classifier-summary classifier-sym ns-sym accessor-summaries)]
          (when (:path summary)
            {:path (:path summary)
             :classifier-sym (qualified-classifier-sym classifier-sym ns-sym accessor-summaries)
             :values [lit]}))))))

(defn- keyword-accessor-path
  [form param]
  (when (and (seq? form)
             (= 2 (count form))
             (keyword? (first form))
             (= param (second form)))
    [(first form)]))

(defn- get-accessor-path
  [form param]
  (when (and (seq? form)
             (= 3 (count form))
             (#{'get 'clojure.core/get} (first form))
             (= param (second form))
             (keyword? (nth form 2)))
    [(nth form 2)]))

(defn- accessor-path
  "Recognize accessor expressions on `param` and return a keyword path, or nil.
   Handles `(:k x)`, `(get x :k)`, and nested `(:k2 (:k1 x))` (which is the
   post-expansion shape of `(-> x :k1 :k2)`)."
  [form param]
  (or (keyword-accessor-path form param)
      (get-accessor-path form param)
      (when (and (seq? form)
                 (= 2 (count form))
                 (keyword? (first form))
                 (seq? (second form)))
        (when-let [inner (accessor-path (second form) param)]
          (conj inner (first form))))))

(defn- resolve-class
  [sym]
  (let [resolved (try (resolve sym) (catch Exception _ nil))]
    (when (class? resolved) resolved)))

(defn- type-pred-call-info
  "Recognize `(TYPE-PRED ACC)` or `(instance? CLASS ACC)` on `param`. Returns
   `{:pred kw :path [...] [:class cls]}` or nil."
  [form param]
  (when (and (seq? form) (symbol? (first form)))
    (let [head (first form)]
      (cond
        (and (#{'instance? 'clojure.core/instance?} head)
             (= 3 (count form))
             (symbol? (second form)))
        (when-let [cls (resolve-class (second form))]
          (when-let [path (accessor-path (nth form 2) param)]
            {:pred :instance? :path path :class cls}))

        :else
        (when-let [pred-kw (ac/type-predicate-sym->pred head)]
          (when (= 2 (count form))
            (when-let [path (accessor-path (second form) param)]
              {:pred pred-kw :path path})))))))

(defn- path-type-predicate-descriptor
  [body params]
  (when (= 1 (count params))
    (when-let [info (type-pred-call-info body (first params))]
      (assoc info :kind :path-type-predicate))))

(defn predicate-form->descriptor
  "Recognize classifier predicates against the project-wide accessor-summaries
   map; ns-sym is the defining ns of the schema that owns the pred-form, used
   to qualify bare classifier symbols. Returns one of:
     {:path ... :values [...]} for comp/equality classifiers (existing shape), or
     {:kind :path-type-predicate :path ... :pred kw [:class cls]} for
       `(fn [x] (TYPE-PRED ACCESSOR))` shapes,
   or nil."
  [pred-form ns-sym accessor-summaries]
  (or
   (when (comp-set-classifier? pred-form)
     (let [literal-set (second pred-form)
           classifier-sym (nth pred-form 2)
           summary (classifier-summary classifier-sym ns-sym accessor-summaries)]
       (when (and (:path summary) (:cases summary))
         {:path (:path summary)
          :values (selected-keys (:cases summary) literal-set)})))
   (when-let [{:keys [params body]} (fn-body pred-form)]
     (or (equality-classifier-descriptor body params ns-sym accessor-summaries)
         (path-type-predicate-descriptor body params)))))
