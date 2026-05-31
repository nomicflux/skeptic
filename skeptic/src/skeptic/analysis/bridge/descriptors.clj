(ns skeptic.analysis.bridge.descriptors
  "Per-form descriptor extraction for Plumatic source forms stored in the
  project-wide form-refs map, keyed by qualified symbol (built once in
  `skeptic.checking.pipeline/project-state`, threaded through bridge ctx).
  Pipeline stores prepared descriptors, not raw forms. Each descriptor carries
  the exact source namespace, alias map, and known declaration qsyms used to
  resolve schema-reference symbols deterministically.

  Descriptor shapes:
    :defn      → {:kind :defn :output-form form :arglists {k {:input-forms [...]}}}
    :def       → {:kind :def :schema-form form}
    :defschema → {:kind :defschema :schema-form form}
    :schema-source → {:kind :schema-source :schema-form form}

  Prepared descriptors add:
    :source-env → {:ns ns-sym :aliases {alias target-ns} :ref-qsyms #{qsym}}")

(defn- with-form-meta
  [original rewritten]
  (if (instance? clojure.lang.IObj rewritten)
    (with-meta rewritten (meta original))
    rewritten))

(defn- annotation-form
  [[x y]]
  (when (= x ':-) y))

(defn- extract-input-forms
  [argvec]
  (loop [items (seq argvec)
         acc []]
    (cond
      (empty? items) (vec acc)
      (= '& (first items)) (vec acc)
      (= ':- (second items)) (recur (drop 3 items) (conj acc (nth items 2)))
      :else (recur (next items) (conj acc nil)))))

(defn- arglist-entry
  [argvec]
  (let [input-forms (extract-input-forms argvec)
        has-varargs (some #(= % '&) argvec)]
    (if has-varargs
      [:varargs {:input-forms input-forms :count (count input-forms)}]
      [(count input-forms) {:input-forms input-forms}])))

(defn- peel-defn-prefix
  "Strip leading docstring, attr-map, and `:- T` in any order. Plumatic's
  s/defn accepts both `(s/defn name doc? attr-map? :- T [args] body)` and
  `(s/defn name :- T doc? attr-map? [args] body)`. Returns [output-form rest].
  Stops at the first non-prefix token (vector argvec or list method)."
  [items]
  (loop [items items
         output-form nil]
    (cond
      (empty? items) [output-form items]
      (string? (first items)) (recur (next items) output-form)
      (and (map? (first items)) (not (vector? (first items))))
      (recur (next items) output-form)
      (= ':- (first items)) (recur (nnext items) (second items))
      :else [output-form items])))

(defn extract-defn-annotation-form
  [form]
  (let [[_defn-sym _name & more] form
        [output-form more] (peel-defn-prefix more)
        items (if (vector? (first more))
                [(with-form-meta (first more)
                   (list* (first more) (next more)))]
                more)
        arglists (reduce
                   (fn [acc next-item]
                     (let [argvec (if (seq? next-item) (first next-item) next-item)
                           [key val] (arglist-entry argvec)]
                       (assoc acc key val)))
                   {}
                   items)]
    {:kind :defn
     :output-form output-form
     :arglists arglists}))

(defn extract-def-annotation-form
  [form]
  (let [[_def-sym _name & more] form
        schema-form (annotation-form more)]
    {:kind :def
     :schema-form schema-form}))

(defn extract-defschema-body-form
  [form]
  {:kind :defschema
   :schema-form (nth form 2 nil)})

(defn raw->descriptor
  "Dispatch a raw Plumatic source form to its extractor by head NAME.
  Pipeline ensures only Plumatic-discovered forms reach this fn, so a name
  match (under any alias of schema.core) is sufficient. Returns nil for
  unrecognized heads."
  [raw-form]
  (when (and (seq? raw-form)
             (symbol? (first raw-form)))
    (case (name (first raw-form))
      "defn"      (extract-defn-annotation-form raw-form)
      "def"       (extract-def-annotation-form raw-form)
      "defschema" (extract-defschema-body-form raw-form)
      nil)))

(defn schema-form
  [descriptor]
  (case (:kind descriptor)
    :def       (:schema-form descriptor)
    :defschema (:schema-form descriptor)
    :schema-source (:schema-form descriptor)
    nil))

(defn source-env
  [ns-sym aliases ref-qsyms]
  {:ns ns-sym
   :aliases (or aliases {})
   :ref-qsyms (set ref-qsyms)})

(defn source-symbol-qsym
  "Resolve one source symbol exactly in the descriptor's namespace context.
  Qualified symbols either expand through an explicit alias or remain as written.
  Unqualified symbols resolve to the descriptor's own namespace. This returns a
  single qsym, never a candidate set."
  [{:keys [ns aliases]} form]
  (when (and ns (symbol? form))
    (if-let [ns-part (some-> form namespace symbol)]
      (if-let [target (get aliases ns-part)]
        (symbol (name target) (name form))
        form)
      (symbol (name ns) (name form)))))

(defn exact-ref-qsym
  "The exact admitted declaration qsym named by `form`, or nil when the symbol
  does not name one of the carried declaration qsyms."
  [{:keys [ref-qsyms] :as source-env} form]
  (when-let [qsym (source-symbol-qsym source-env form)]
    (when (contains? ref-qsyms qsym)
      qsym)))

(defn prepare-form-ref
  [ns-sym aliases ref-qsyms raw-form]
  (when-let [descriptor (raw->descriptor raw-form)]
    (assoc descriptor
           :source-env (source-env ns-sym aliases ref-qsyms))))

(defn prepare-source-form
  [ns-sym aliases ref-qsyms form]
  {:kind :schema-source
   :schema-form form
   :source-env (source-env ns-sym aliases ref-qsyms)})
