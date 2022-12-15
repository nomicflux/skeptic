(defproject skeptic "0.3.0"
  :description "Schema-based static type checking for Clojure"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  :dependencies [[prismatic/schema                                 "1.1.12"]
                 [prismatic/plumbing                               "0.5.5"]]
  :main skeptic.core
  :eval-in-leiningen true)
