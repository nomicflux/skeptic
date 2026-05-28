(ns skeptic.worker.discovery
  "Worker-side Plumatic source-form discovery. Mirrors
   `skeptic.schema.discovery/discover` with one change: role lookup is keyed
   by qualified symbol (`'schema.core/defn`) rather than Var
   (`#'schema.core/defn`). Eliminates the `schema.core` worker classpath
   dependency — the worker never has to actually load Plumatic Schema to
   tag a top-level form as `:s/defn`."
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(def ^:private role-by-resolved-qsym
  {'schema.core/defn        :s/defn
   'schema.core/def         :s/def
   'schema.core/defschema   :s/defschema
   'schema.core/defprotocol :s/defprotocol
   'schema.core/defrecord   :s/defrecord})

(defn- try-read
  [^PushbackReader reader]
  (try (read {:read-cond :allow :features #{:clj} :eof nil} reader)
       (catch Exception _ nil)))

(defn- read-top-forms
  [^java.io.File source-file]
  (with-open [reader (PushbackReader. (io/reader source-file))]
    (->> (repeatedly #(try-read reader))
         (take-while some?)
         doall)))

(defn- var->qsym
  [v]
  (when v
    (symbol (str (.name (.ns ^clojure.lang.Var v)))
            (str (.sym ^clojure.lang.Var v)))))

(defn- form-head-qsym
  "Returns the qualified symbol of (first form)'s resolved Var, or nil if
   not a seq, not a symbol, or unresolvable. Caller must bind *ns* to the
   analyzed namespace."
  [ns-sym form]
  (when (seq? form)
    (let [head (first form)]
      (when (symbol? head)
        (try (var->qsym (ns-resolve (the-ns ns-sym) head))
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
  (when-let [resolved-qsym (form-head-qsym ns-sym form)]
    (when-let [role (role-by-resolved-qsym resolved-qsym)]
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
  "Walks top-level forms in `source-file` and returns
   `{:declarations {qualified-sym {:role :form :declared-sym :ns}}
     :source-forms {qualified-sym <raw-form>}
     :errors [...]}`. Loads `ns-sym` on the worker first; the role lookup
   needs `ns-resolve` against the analyzed namespace."
  [ns-sym ^java.io.File source-file]
  (require ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (let [forms (try (read-top-forms source-file)
                     (catch Exception _ nil))
          entries (mapcat #(form-decls ns-sym %) forms)
          decls (into {} entries)
          srcs  (into {} (map (fn [[q m]] [q (:form m)]) entries))]
      {:declarations decls
       :source-forms srcs
       :errors []})))
