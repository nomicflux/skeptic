(ns leiningen.skeptic
  (:require [leiningen.core.main]
            [leiningen.core.eval]
            [skeptic.core]))

(defn skeptic
  [project & args]
  (leiningen.core.eval/eval-in-project
   (update-in project [:dependencies] concat [['skeptic "0.4.0-SNAPSHOT"]])
   `(skeptic.core/get-project-schemas ~(:group project))
   '(require 'skeptic.core)))
