(defproject org.clojars.nomicflux/skeptic "0.9.0-rc4"
  :description "Static type checking for Clojure projects that use Plumatic Schema"
  :url "https://github.com/nomicflux/skeptic"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :deploy-repositories
  [["releases" {:url "https://repo.clojars.org"
                :username :env/CLOJARS_USERNAME
                :password :env/CLOJARS_SKEPTIC_TOKEN}]
   ["snapshots" {:url "https://repo.clojars.org"
                 :username :env/CLOJARS_USERNAME
                 :password :env/CLOJARS_SKEPTIC_TOKEN}]]
  :dependencies [[prismatic/schema           "1.4.1"]
                 [prismatic/plumbing         "0.6.0"]
                 [metosin/malli              "0.20.1"]
                 [org.clojure/clojure        "1.12.0"]
                 [org.clojure/clojurescript  "1.11.132"]
                 [org.clojure/data.json      "2.5.1"]
                 [org.clojure/tools.analyzer "1.2.2"]
                 [org.clojure/tools.analyzer.jvm "1.4.0-beta1"]
                 [org.clojure/tools.cli      "1.0.214"]
                 [org.clojure/tools.deps     "0.29.1598"]
                 [org.babashka/sci           "0.12.51"]
                 [commons-io                 "2.11.0"]]
  :profiles {:dev {:injections [(do (require 'schema.core)
                                     ((resolve 'schema.core/set-fn-validation!) true))]}
             :skeptic-plugin {:plugins [[org.clojars.nomicflux/lein-skeptic "0.9.0-rc4"]]}})
