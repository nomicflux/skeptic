(ns leiningen.skeptic
  (:require [leiningen.core.main]
            [leiningen.core.eval]
            [leiningen.core.project]
            [skeptic.core]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['skeptic              "0.5.0-SNAPSHOT"]]})

(defn skeptic
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)]
    (leiningen.core.eval/eval-in-project
     (leiningen.core.project/merge-profiles project [profile])
    `(skeptic.core/get-project-schemas ~(:group project))
    '(require 'skeptic.core))))
