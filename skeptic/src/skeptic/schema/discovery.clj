(ns skeptic.schema.discovery
  "Plumatic source-form discovery. Reads top-level forms from the namespace's
  source file, resolves each form's head symbol against the namespace's
  aliases via ns-resolve, and tags matches with a producer role.

  Data verified at /tmp/skeptic-research/intake-data-dump.out: source-forms
  arrive pre-macro-expansion, so heads like 's/defn / schema.core/defn /
  schemy/defn all resolve to #'schema.core/defn after binding *ns*."
  (:require [schema.core]
            [skeptic.file :as file]))

(def ^:private role-by-resolved-var
  {#'schema.core/defn        :s/defn
   #'schema.core/def         :s/def
   #'schema.core/defschema   :s/defschema
   #'schema.core/defprotocol :s/defprotocol
   #'schema.core/defrecord   :s/defrecord})

(defn- read-top-forms
  [^java.io.File source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (->> (repeatedly #(file/try-read reader))
         (take-while some?)
         doall)))

(defn- form-head-var
  "Returns the resolved Var for (first form) or nil if not a seq, not a symbol,
  or unresolvable. Caller must bind *ns* to the analyzed namespace."
  [ns-sym form]
  (when (seq? form)
    (let [head (first form)]
      (when (symbol? head)
        (try (ns-resolve (the-ns ns-sym) head)
             (catch Exception _ nil))))))

(defn- declaration
  [ns-sym role declared-sym form]
  (let [qsym (symbol (name ns-sym) (name declared-sym))]
    [qsym {:role role :form form :declared-sym declared-sym :ns ns-sym}]))

(defn- protocol-method-decls
  [ns-sym form]
  (->> (drop 2 form)
       (filter seq?)
       (keep (fn [spec]
               (let [method-sym (first spec)]
                 (when (symbol? method-sym)
                   (declaration ns-sym :s/defprotocol-method method-sym form)))))))

(defn- record-factory-decls
  [ns-sym record-sym form]
  (let [base (name record-sym)]
    (for [prefix ["->" "map->" "strict-map->"]]
      (declaration ns-sym :s/defrecord-factory (symbol (str prefix base)) form))))

(defn- form-decls
  [ns-sym form]
  (when-let [resolved-var (form-head-var ns-sym form)]
    (when-let [role (role-by-resolved-var resolved-var)]
      (let [declared-sym (second form)]
        (cond
          (not (symbol? declared-sym))
          nil

          (= role :s/defprotocol)
          (cons (declaration ns-sym :s/defprotocol declared-sym form)
                (protocol-method-decls ns-sym form))

          (= role :s/defrecord)
          (cons (declaration ns-sym :s/defrecord-class declared-sym form)
                (record-factory-decls ns-sym declared-sym form))

          :else
          [(declaration ns-sym role declared-sym form)])))))

(defn discover
  "Walks top-level forms in source-file and returns
  {:declarations {qualified-sym {:role :form :declared-sym :ns}}
   :source-forms {qualified-sym <raw-form>}
   :errors [...]}."
  [ns-sym ^java.io.File source-file]
  (binding [*ns* (the-ns ns-sym)]
    (let [forms (try (read-top-forms source-file)
                     (catch Exception _ nil))
          entries (mapcat #(form-decls ns-sym %) forms)
          decls (into {} entries)
          srcs  (into {} (map (fn [[q m]] [q (:form m)]) entries))]
      {:declarations decls
       :source-forms srcs
       :errors []})))

