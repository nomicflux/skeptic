;; Descriptor: {:kind :def|:defschema|:defn :schema-form form | :output-form form :arglists {k {:input-forms [...] :count n}}}
(ns skeptic.checking.form
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge :as ab])
  (:import [java.io File]))

(def spy-on false)
(def spy-only #{})

(s/defn spy* :- s/Any
  [msg x]
  (when (and spy-on (or (nil? spy-only)
                        (contains? spy-only msg)))
    (try (println msg (pr-str x))
         (catch Exception e
           (println msg e))))
  x)

(defmacro spy
  [_msg x]
  #_
  `(spy* ~_msg ~x)
  x)

(s/defn valid-schema? :- s/Any
  [schema]
  (ab/schema-domain? schema))

(defmacro assert-schema
  [schema]
  #_
  `(do (assert (valid-schema? ~schema) (format "Must be valid schema: %s" ~schema))
       ~schema)
  schema)

(defmacro assert-has-schema
  [x]
  #_
  `(do (assert (valid-schema? (:schema ~x)) (format "Must be valid schema: %s (%s)" (:schema ~x) (pr-str ~x)))
       ~x)
  x)

(s/defn with-form-meta :- s/Any
  [original rewritten]
  (if (instance? clojure.lang.IObj rewritten)
    (with-meta rewritten (meta original))
    rewritten))

(s/defn schema-defn-symbol? :- s/Any
  [sym :- s/Any]
  (and (symbol? sym)
       (= "defn" (name sym))
       (#{"s" "schema.core"} (namespace sym))))

(s/defn strip-schema-argvec :- s/Any
  [argvec]
  (with-form-meta
    argvec
    (loop [[x & more] argvec
           acc []]
      (cond
        (nil? x) (vec acc)
        (= x ':-) (recur (next more) acc)
        :else (recur more (conj acc x))))))

(s/defn strip-schema-method :- s/Any
  [decl]
  (let [[args & body] decl]
    (with-form-meta decl
      (list* (strip-schema-argvec args) body))))

(s/defn strip-schema-defn :- s/Any
  [form]
  (let [[_defn-sym name & more] form
        [more] (if (= ':- (first more))
                 [(nnext more)]
                 [more])
        [docstring more] (if (string? (first more))
                           [(first more) (next more)]
                           [nil more])
        [attr-map more] (if (map? (first more))
                          [(first more) (next more)]
                          [nil more])
        decls (if (vector? (first more))
                [(with-form-meta (first more)
                   (list* (strip-schema-argvec (first more)) (next more)))]
                (map strip-schema-method more))]
    (with-form-meta form
      (list* 'defn
             name
             (concat (when docstring [docstring])
                     (when attr-map [attr-map])
                     decls)))))

(s/defn normalize-check-form :- s/Any
  [form]
  (if (and (seq? form) (schema-defn-symbol? (first form)))
    (strip-schema-defn form)
    form))

(s/defn source-file-path :- (s/maybe s/Str)
  [source-file]
  (cond
    (nil? source-file) nil
    (instance? File source-file) (.getPath ^File source-file)
    :else (str source-file)))

(s/defn merge-location :- s/Any
  [& locations]
  (when-let [present (seq (remove nil? locations))]
    (reduce (fn [acc location]
              (merge acc (into {}
                               (remove (comp nil? val))
                               location)))
            {}
            present)))

(s/defn form-location :- s/Any
  [source-file form]
  (merge-location {:file (source-file-path source-file)}
                  (select-keys (meta form) [:line :column :end-line :end-column])))

(s/defn form-source :- s/Any
  [form]
  (:source (meta form)))

(defn- annotation-symbol
  [[x y]]
  (when (and (= x ':-) (symbol? y)) y))

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

(s/defn defn-decls :- s/Any
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (or (= 'defn (first form))
                 (schema-defn-symbol? (first form))))
    (let [[head _name & more] form
          more (if (and (schema-defn-symbol? head)
                        (= ':- (first more)))
                 (nnext more)
                 more)
          more (if (string? (first more))
                 (next more)
                 more)
          more (if (map? (first more))
                 (next more)
                 more)]
      (if (vector? (first more))
        [(with-form-meta (first more)
           (list* (first more) (next more)))]
        more))))

(s/defn extract-defn-annotation-symbol :- s/Any
  [form]
  (let [[_defn-sym _name & more] form
        more (if (string? (first more)) (next more) more)
        more (if (map? (first more)) (next more) more)]
    (annotation-symbol more)))

(s/defn extract-def-annotation-symbol :- s/Any
  [form]
  (let [[_def-sym _name & more] form]
    (annotation-symbol more)))

(defn- arglist-entry
  [argvec]
  (let [input-forms (extract-input-forms argvec)
        has-varargs (some #(= % '&) argvec)]
    (if has-varargs
      [:varargs {:input-forms input-forms :count (count input-forms)}]
      [(count input-forms) {:input-forms input-forms}])))

(s/defn extract-defn-annotation-form :- s/Any
  [form]
  (let [[_defn-sym _name & more] form
        more (if (string? (first more)) (next more) more)
        more (if (map? (first more)) (next more) more)
        output-form (annotation-form more)
        more (if (= ':- (first more)) (nnext more) more)
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

(s/defn extract-def-annotation-form :- s/Any
  [form]
  (let [[_def-sym _name & more] form
        schema-form (annotation-form more)]
    {:kind :def
     :schema-form schema-form}))

(defn- schema-defschema-symbol?
  [sym]
  (and (symbol? sym)
       (= "defschema" (name sym))
       (#{"s" "schema.core"} (namespace sym))))

(s/defn extract-defschema-body-form :- s/Any
  [form]
  (when (and (seq? form)
             (schema-defschema-symbol? (first form)))
    (let [body-form (nth form 2 nil)]
      {:kind :defschema
       :schema-form body-form})))

(s/defn method-source-body :- s/Any
  [decl]
  (let [[_args & body] decl]
    (cond
      (empty? body) nil
      (= 1 (count body)) (first body)
      :else (with-form-meta (first body)
              (list* 'do body)))))

(s/defn display-expr :- s/Any
  [node]
  (let [expr (aapi/node-form node)
        source-expression (form-source expr)]
    {:expr expr
     :source-expression source-expression
     :expanded-expression (when (and source-expression
                                     (not= source-expression (pr-str expr)))
                            expr)
     :location (aapi/node-location node)}))

(s/defn node-error-context :- s/Any
  [node enclosing-form]
  (let [{:keys [expr source-expression location]} (display-expr node)]
    {:expr expr
     :source-expression source-expression
     :location location
     :enclosing-form enclosing-form}))
