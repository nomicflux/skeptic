(ns leiningen.skeptic
  (:require [leiningen.core.main]
            [leiningen.core.eval]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [schema.core :as s]))

(defn skeptic
  [project & args]
  (leiningen.core.eval/eval-in-project
   project
   `(skeptic.core/get-project-schemas ~(:group project))
   '(require 'skeptic.core 'leiningen.core.main 'leiningen.core.eval)))
