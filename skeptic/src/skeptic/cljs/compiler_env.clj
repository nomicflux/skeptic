(ns skeptic.cljs.compiler-env
  "Bootstraps and drives a ClojureScript compiler-env for skeptic.

  All cljs-side admission and analysis go through a compiler-env produced by
  `fresh-state`. The state is bootstrapped with cljs.core seeded and
  `*analyze-deps*` bound false so analyzing one form does not recursively
  walk transitive cljs requires."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [clojure.java.io :as io]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]))

(defn fresh-state
  []
  (let [cenv (env/default-compiler-env)]
    (binding [ana/*analyze-deps* false]
      (env/with-compiler-env cenv
        (comp/with-core-cljs nil (fn []))))
    cenv))

(defn- read-all-forms
  [source-file]
  (with-open [r (io/reader source-file)]
    (let [pbr (reader-types/indexing-push-back-reader r 1 (str source-file))
          eof (Object.)
          opts {:eof eof :read-cond :allow :features #{:cljs}}]
      (loop [acc []]
        (let [form (reader/read opts pbr)]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defn- ns-info
  [cenv ns-sym]
  (or (get-in @cenv [::ana/namespaces ns-sym])
      {:name ns-sym}))

(defn- analyze-top-level
  [cenv ns-sym form]
  (env/with-compiler-env cenv
    (binding [ana/*cljs-ns* ns-sym
              ana/*analyze-deps* false]
      (ana-api/no-warn
       (ana/analyze (assoc (ana/empty-env) :ns (ns-info cenv ns-sym))
                    form nil {})))))

(defn- ns-form-name
  [form]
  (when (and (seq? form) (= 'ns (first form)))
    (second form)))

(defn load-source!
  [cenv source-file]
  (loop [forms (read-all-forms source-file)
         ns-sym 'cljs.user
         asts []]
    (if-let [form (first forms)]
      (let [ast (analyze-top-level cenv ns-sym form)
            next-ns (or (ns-form-name form) ns-sym)]
        (recur (rest forms) next-ns (conj asts ast)))
      asts)))

(defn load-project!
  [cenv source-files macro-libs]
  (doseq [lib macro-libs]
    (require lib))
  (reduce (fn [m f]
            (assoc m f (load-source! cenv f)))
          {}
          source-files))
