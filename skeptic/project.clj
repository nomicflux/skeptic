(defproject skeptic "0.5.0-SNAPSHOT"
  :description "Schema-based static type checking for Clojure"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :dependencies [[prismatic/schema                                 "1.1.12"]
                 [prismatic/plumbing                               "0.6.0"]
                 [com.taoensso/tufte                               "2.4.5"]
                 [org.clojure/clojure                              "1.11.1"]
                 [commons-io "2.11.0"]]
  :profiles {:dev [{:injections [(do (require 'schema.core)
                                     ((resolve 'schema.core/set-fn-validation!) true))]}]})
