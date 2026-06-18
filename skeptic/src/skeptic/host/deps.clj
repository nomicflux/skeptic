(ns skeptic.host.deps
  "Single source of truth for Skeptic's HOST runtime coordinates.

   The hermetic-host launcher (lein-skeptic) resolves these via its build
   system (lein's aether wrapper) and uses them as the host JVM's -cp.
   The deps.edn path (`clj -T:skeptic check`) uses the equivalent alias
   declaration in skeptic/deps.edn.

   Parallel to skeptic.worker.deps/worker-deps for the worker JVM.

   tools.reader is explicitly pinned at >= 1.0.0-beta3 (the first version
   whose SourceLoggingPushbackReader is Closeable). Pre-1.0.0-beta3 the
   host's `with-open` against the reader (skeptic.file/ns-for-clojure-file)
   reflectively calls .close on a non-Closeable class and crashes — the
   shape that caused the rc9 plumbing host-side bug.")

(def host-deps
  '[[org.clojars.nomicflux/skeptic            "0.9.0-rc9"]
    [org.clojure/clojure                      "1.12.0"]
    [org.clojure/tools.reader                 "1.6.0"]])

(defn host-deps-as-mvn-map
  "Convert host-deps to the {coord {:mvn/version v}} shape tools.deps
   accepts as :deps / :replace-deps."
  []
  (into {}
        (map (fn [[coord version]] [coord {:mvn/version version}]))
        host-deps))
