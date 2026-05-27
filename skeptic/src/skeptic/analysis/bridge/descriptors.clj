(ns skeptic.analysis.bridge.descriptors
  "Per-form descriptor extraction for Plumatic source forms stored in the
  project-wide form-refs map, keyed by qualified symbol (built once in
  `skeptic.checking.pipeline/project-state`, threaded through bridge ctx).
  Pipeline puts raw `(s/defn ...)` / `(s/def ...)` / `(s/defschema ...)` lists
  into the map; bridge.clj normalizes them via `raw->descriptor` on demand.

  Pipeline-side filtering (only Plumatic-discovered Vars get stored) means a
  head-name match is sufficient at consumer time — alias resolution already
  happened in skeptic.schema.discovery. Heads with name 'defn' / 'def' /
  'defschema' under any alias of schema.core resolve to the matching
  extractor.

  Descriptor shapes:
    :defn      → {:kind :defn :output-form form :arglists {k {:input-forms [...]}}}
    :def       → {:kind :def :schema-form form}
    :defschema → {:kind :defschema :schema-form form}")

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
