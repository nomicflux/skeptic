(defproject org.clojars.nomicflux/skeptic "0.9.0-rc7"
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
                 [commons-io                 "2.11.0"]
                 [com.cognitect/transit-clj  "1.0.333"]
                 [nrepl                      "1.3.1"]]
  :profiles {:dev {:injections [(do (require 'schema.core)
                                     ((resolve 'schema.core/set-fn-validation!) true))]}
             :skeptic-plugin {:plugins [[org.clojars.nomicflux/lein-skeptic "0.9.0-rc7"]]}
             ;; Skeptic's worker runtime dependency declaration. The lein-skeptic
             ;; plugin path merges this profile onto the project and resolves
             ;; its `:dependencies` via aether; the resolved jar list is handed
             ;; to the worker spawn as the runtime-cp tail of the launch
             ;; classpath. deps.edn declares the SAME coordinates under the
             ;; `:worker` alias for the `clj -T:skeptic check` path. The 10
             ;; coordinates are the namespaces F1's walk identified as
             ;; load-bearing for the worker JVM.
             :worker {:dependencies [[org.clojure/clojure             "1.12.0"]
                                     [org.clojure/clojurescript       "1.11.132"]
                                     [org.clojure/tools.analyzer      "1.2.2"]
                                     [org.clojure/tools.analyzer.jvm  "1.4.0-beta1"]
                                     [org.clojure/tools.reader        "1.6.0"]
                                     [org.clojure/core.cache          "1.2.249"]
                                     [org.clojure/core.memoize        "1.2.273"]
                                     [org.clojure/data.priority-map   "1.2.1"]
                                     [com.cognitect/transit-clj       "1.0.333"]
                                     [nrepl                           "1.3.1"]]}})
