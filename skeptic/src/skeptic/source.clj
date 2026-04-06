(ns skeptic.source
  (:require [clojure.repl :as repl]
            [schema.core :as s]))

(s/defn get-fn-code :- s/Str
  [{:keys [verbose lookup-failures]}
   func-name :- s/Symbol]
  (if-let [code (repl/source-fn func-name)]
    code
    (do (when lookup-failures
          (when (and verbose (not (contains? @lookup-failures func-name)))
            (println "No code found for" func-name))
          (swap! lookup-failures conj func-name))
        "")))
