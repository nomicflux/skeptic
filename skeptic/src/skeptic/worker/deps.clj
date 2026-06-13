(ns skeptic.worker.deps
  "Single source of truth for Skeptic's worker runtime coordinates.

   Both runtime entrypoints consume this declaration:
     - `leiningen.skeptic` resolves it via leiningen's aether wrapper.
     - `skeptic.cli.main` resolves it via tools.deps.

   The coordinates are the namespaces load-bearing for the worker JVM:
   skeptic itself (the worker server/transport code), clojure,
   clojurescript, tools.analyzer(.jvm), tools.reader, tools.namespace
   (ns-decl parsing for dependency-ordered loading), core.cache/memoize,
   data.priority-map, transit-clj, nrepl. Whatever each build system
   transitively resolves from this set is the worker's runtime universe
   under that build system.

   `skeptic/project.clj`'s `:worker` profile and `skeptic/deps.edn`'s
   `:worker` alias mirror this vector for ad-hoc invocations like
   `lein with-profile +worker classpath` and `clj -A:worker`; the runtime
   code paths read FROM this var, not from those build-file declarations.")

(def worker-deps
  "Vector of [group/artifact version-string] tuples. Lein-shape: passed to
   leiningen.core.classpath/resolve-managed-dependencies via a synthetic
   project map. Deps.edn-shape: converted to a {coord {:mvn/version v}} map
   for tools.deps/create-basis."
  '[[org.clojars.nomicflux/skeptic   "0.9.0-rc8"]
    [org.clojure/clojure             "1.12.0"]
    [org.clojure/clojurescript       "1.11.132"]
    [org.clojure/tools.analyzer      "1.2.2"]
    [org.clojure/tools.analyzer.jvm  "1.4.0-beta1"]
    [org.clojure/tools.reader        "1.6.0"]
    [org.clojure/tools.namespace     "1.5.1"]
    [org.clojure/core.cache          "1.2.249"]
    [org.clojure/core.memoize        "1.2.273"]
    [org.clojure/data.priority-map   "1.2.1"]
    [com.cognitect/transit-clj       "1.0.333"]
    [nrepl                           "1.3.1"]
    [bultitude                       "0.2.8"]])

(defn worker-deps-as-mvn-map
  "Convert `worker-deps` to the {coord {:mvn/version v}} shape tools.deps
   accepts as :deps / :replace-deps."
  []
  (into {}
        (map (fn [[coord version]] [coord {:mvn/version version}]))
        worker-deps))
