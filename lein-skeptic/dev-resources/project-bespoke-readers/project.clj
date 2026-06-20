(defproject project-bespoke-readers "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :source-paths ["src"]
  :profiles
  {:dev {}
   :test {}
   :skeptic-plugin
   {:plugins [[org.clojars.nomicflux/lein-skeptic "0.9.1-SNAPSHOT"]]}})
