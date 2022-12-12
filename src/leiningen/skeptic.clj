(ns leiningen.skeptic
  (:require [leiningen.core.main :as lein]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [schema.core :as s]))

(defn skeptic
  [project & args]
  (case (first args)
    "a" (lein/info "Option a")
    "b" (lein/info "Option b")
    (do (lein/info (str "other: " (str/join "," args)))
        (lein/info (pr-str project)))))

