(defproject org.clojars.nomicflux/lein-skeptic "0.9.0"
  :description "Static type checking for Clojure projects that use Plumatic Schema / Malli (hermetic-host launcher)"
  :url "https://github.com/nomicflux/skeptic"
  :license {:name "MIT License"
            :url "https://spdx.org/licenses/MIT.html"}
  ;; CI: GitHub Actions sets CLOJARS_* env vars (see .github/workflows/publish-clojars.yml).
  :deploy-repositories
  [["releases" {:url "https://repo.clojars.org"
                :username :env/CLOJARS_USERNAME
                :password :env/CLOJARS_LEIN_SKEPTIC_TOKEN}]
   ["snapshots" {:url "https://repo.clojars.org"
                 :username :env/CLOJARS_USERNAME
                 :password :env/CLOJARS_LEIN_SKEPTIC_TOKEN}]]
  ;; Zero transitives. The launcher resolves Skeptic host-deps and worker-deps
  ;; at task time via aether against a synthetic project, so neither shows up
  ;; in lein's plugin-tree mediation. This is what makes :pedantic? :abort
  ;; projects (like compojure-api) work and prevents other plugins (codox,
  ;; lein-doo, ...) from polluting Skeptic's host classloader.
  :dependencies []
  :managed-dependencies []
  :eval-in-leiningen true)
