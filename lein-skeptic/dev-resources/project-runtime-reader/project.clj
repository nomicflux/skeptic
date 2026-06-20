(defproject project-runtime-reader "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :source-paths ["src"]
  :profiles
  {:dev
   {:injections
    [(set! clojure.core/*default-data-reader-fn*
           (fn [tag value]
             (if (= 'date-time tag)
               {:date-time value}
               (throw (ex-info "Unknown reader tag" {:tag tag})))))]}
   :test {}
   :skeptic-plugin
   {:plugins [[org.clojars.nomicflux/lein-skeptic "0.9.1-SNAPSHOT"]]}})
