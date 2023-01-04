(ns skeptic.core
  (:require [skeptic.schematize :as schematize]
            [skeptic.checking :as checking]
            [skeptic.examples :as examples]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [taoensso.tufte :as tufte]))

#_
(tufte/add-basic-println-handler! {})

;; TODO: get all ns for project, not just those referenced here
;; (not least to avoid circular dependencies)

(defn get-project-schemas
  [group-name]
  (let [nss (->> (all-ns)
                 (map ns-name)
                 (filter #(str/starts-with? % group-name))
                 sort)]
    (println (pr-str nss))
    (doseq [ns nss]
              (require ns)
              (println "*** Checking" ns "***")
              ;; (pprint/pprint (checking/annotate-ns ns))
              (doseq [{:keys [blame path errors]} (checking/check-ns ns)]
                (println "---------")
                (println "Expression: \t" (pr-str blame))
                (println "In: \t\t" (pr-str path))
                (doseq [error errors]
                  (println "---")
                  (println error "\n"))))))