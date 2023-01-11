(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [clojure.string :as str]))

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
    (println "Namespaces to check: " (pr-str nss))
    (doseq [ns nss]
              (require ns)
              (println "*** Checking" ns "***")
              ;; (pprint/pprint (checking/annotate-ns ns))
              (try
                (doseq [{:keys [blame path errors]} (checking/check-ns ns)]
                 (println "---------")
                 (println "Expression: \t" (pr-str blame))
                 (println "In: \t\t" (pr-str path))
                 (doseq [error errors]
                   (println "---")
                   (println error "\n")))
                (catch Exception e
                  (println "Error parsing namespace" ns ":" e))))))
