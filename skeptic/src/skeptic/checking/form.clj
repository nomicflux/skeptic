(ns skeptic.checking.form
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge :as ab])
  (:import [java.io File]))

(def spy-on false)
(def spy-only #{})

(defn spy*
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

(defn valid-schema?
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

(defn with-form-meta
  [original rewritten]
  (if (instance? clojure.lang.IObj rewritten)
    (with-meta rewritten (meta original))
    rewritten))

(defn schema-defn-symbol?
  [sym]
  (and (symbol? sym)
       (= "defn" (name sym))
       (#{"s" "schema.core"} (namespace sym))))

(defn strip-schema-argvec
  [argvec]
  (with-form-meta
    argvec
    (loop [[x & more] argvec
           acc []]
      (cond
        (nil? x) (vec acc)
        (= x ':-) (recur (next more) acc)
        :else (recur more (conj acc x))))))

(defn strip-schema-method
  [decl]
  (let [[args & body] decl]
    (with-form-meta decl
      (list* (strip-schema-argvec args) body))))

(defn strip-schema-defn
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

(defn normalize-check-form
  [form]
  (if (and (seq? form) (schema-defn-symbol? (first form)))
    (strip-schema-defn form)
    form))

(defn source-file-path
  [source-file]
  (cond
    (nil? source-file) nil
    (instance? File source-file) (.getPath ^File source-file)
    :else (str source-file)))

(defn merge-location
  [& locations]
  (when-let [present (seq (remove nil? locations))]
    (reduce (fn [acc location]
              (merge acc (into {}
                               (remove (comp nil? val))
                               location)))
            {}
            present)))

(defn form-location
  [source-file form]
  (merge-location {:file (source-file-path source-file)}
                  (select-keys (meta form) [:line :column :end-line :end-column])))

(defn form-source
  [form]
  (:source (meta form)))

(defn- annotation-symbol
  [[x y]]
  (when (and (= x ':-) (symbol? y)) y))

(defn extract-defn-annotation-symbol
  [form]
  (let [[_defn-sym _name & more] form
        more (if (string? (first more)) (next more) more)
        more (if (map? (first more)) (next more) more)]
    (annotation-symbol more)))

(defn extract-def-annotation-symbol
  [form]
  (let [[_def-sym _name & more] form]
    (annotation-symbol more)))

(defn defn-decls
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

(defn method-source-body
  [decl]
  (let [[_args & body] decl]
    (cond
      (empty? body) nil
      (= 1 (count body)) (first body)
      :else (with-form-meta (first body)
              (list* 'do body)))))

(defn display-expr
  [node]
  (let [expr (aapi/node-form node)
        source-expression (form-source expr)]
    {:expr expr
     :source-expression source-expression
     :expanded-expression (when (and source-expression
                                     (not= source-expression (pr-str expr)))
                            expr)
     :location (aapi/node-location node)}))

(defn node-error-context
  [node enclosing-form]
  (let [{:keys [expr source-expression location]} (display-expr node)]
    {:expr expr
     :source-expression source-expression
     :location location
     :enclosing-form enclosing-form}))
