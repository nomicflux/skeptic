(ns skeptic.analysis.predicate-descriptor)

(defn- comp-set-classifier?
  [form]
  (and (seq? form)
       (#{'comp 'clojure.core/comp} (first form))
       (= 3 (count form))
       (set? (second form))
       (symbol? (nth form 2))))

(defn- qualify-sym
  [sym ns-sym]
  (cond
    (nil? sym) nil
    (qualified-symbol? sym) sym
    (and ns-sym (symbol? sym)) (symbol (str ns-sym) (name sym))
    :else sym))

(defn- classifier-summary
  [classifier-sym ns-sym accessor-summaries]
  (or (get accessor-summaries (qualify-sym classifier-sym ns-sym))
      (get accessor-summaries classifier-sym)))

(defn- selected-keys
  [cases literal-set]
  (vec (sort (keep (fn [[k v]] (when (contains? literal-set v) k)) cases))))

(defn predicate-form->descriptor
  "Recognize (comp #{<lit> ...} <classifier-sym>); resolve classifier via
   accessor-summaries (qualifying with ns-sym if needed); return
   {:path ... :values [...]} or nil."
  [pred-form ns-sym accessor-summaries]
  (when (comp-set-classifier? pred-form)
    (let [literal-set (second pred-form)
          classifier-sym (nth pred-form 2)
          summary (classifier-summary classifier-sym ns-sym accessor-summaries)]
      (when (= :unary-map-classifier (:kind summary))
        {:path (:path summary)
         :values (selected-keys (:cases summary) literal-set)}))))
