(defproject skeptic "0.7.0-SNAPSHOT"
  :description "Schema-based static type checking for Clojure"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :plugins [[jonase/eastwood "1.3.0"]]
  :dependencies [[prismatic/schema           "1.1.12"]
                 [prismatic/plumbing         "0.6.0"]
                 [org.clojure/clojure        "1.11.1"]
                 [org.clojure/tools.analyzer "1.1.1"]
                 [org.clojure/tools.analyzer.jvm "1.2.3"]
                 [commons-io                 "2.11.0"]]
  :profiles {:dev [{:injections [(do (require 'schema.core)
                                     ((resolve 'schema.core/set-fn-validation!) true))]}]})
