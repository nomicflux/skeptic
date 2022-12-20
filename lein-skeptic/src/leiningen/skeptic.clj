(ns leiningen.skeptic
  (:require [leiningen.core.main]
            [leiningen.core.eval]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skeptic.core]))

(defn skeptic
  [project & args]
  (leiningen.core.eval/eval-in-project
   project
   `(skeptic.core/get-project-schemas ~(:group project))
   '(require 'skeptic.core)))
