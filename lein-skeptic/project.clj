(defproject org.clojars.nomicflux/lein-skeptic "0.7.0-SNAPSHOT"
  :description "Static type checking for Clojure projects that use Plumatic Schema"
  :url "https://github.com/nomicflux/skeptic"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :deploy-repositories [["releases" :clojars]]
  :dependencies [[org.clojars.nomicflux/skeptic "0.7.0-SNAPSHOT"]
                 [org.clojure/tools.cli "1.0.214"]]
  :eval-in-leiningen true)
