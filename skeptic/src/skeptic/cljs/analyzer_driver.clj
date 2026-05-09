(ns skeptic.cljs.analyzer-driver
  "Single-form cljs analyzer entrypoint. The compiler-env passed in must be
  bootstrapped via `skeptic.cljs.compiler-env/fresh-state`."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]))

(defn- ns-info
  [cenv ns-sym]
  (or (get-in @cenv [::ana/namespaces ns-sym])
      {:name ns-sym}))

(defn analyze-form
  [cenv ns-sym form]
  (env/with-compiler-env cenv
    (binding [ana/*cljs-ns* ns-sym
              ana/*analyze-deps* false]
      (ana-api/no-warn
       (ana/analyze (assoc (ana/empty-env) :ns (ns-info cenv ns-sym))
                    form nil {})))))
