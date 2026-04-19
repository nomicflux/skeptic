(defproject org.clojars.nomicflux/skeptic "0.7.0-SNAPSHOT"
  :description "Static type checking for Clojure projects that use Plumatic Schema"
  :url "https://github.com/nomicflux/skeptic"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :deploy-repositories [["releases" :clojars]]
  :plugins [[jonase/eastwood "1.3.0"]]
  :dependencies [[prismatic/schema           "1.1.12"]
                 [prismatic/plumbing         "0.6.0"]
                 [org.clojure/clojure        "1.11.1"]
                 [org.clojure/data.json      "2.5.1"]
                 [org.clojure/tools.analyzer "1.1.1"]
                 [org.clojure/tools.analyzer.jvm "1.2.3"]
                 [commons-io                 "2.11.0"]]
  ;; Published as org.clojars.nomicflux/skeptic on Clojars. For local dev without
  ;; fetching from Clojars: lein install here, then lein install in ../lein-skeptic,
  ;; then lein with-profile +skeptic-plugin skeptic (plugin depends on this library).
  :profiles {:dev {:injections [(do (require 'schema.core)
                                     ((resolve 'schema.core/set-fn-validation!) true))]}
             :skeptic-plugin {:plugins [[org.clojars.nomicflux/lein-skeptic "0.7.0-SNAPSHOT"]]}})
