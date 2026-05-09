(ns skeptic.cli.cljs.deps
  "Source discovery for cljs/cljc files in a deps.edn project. Reuses
   `skeptic.cli.paths/discover-paths` to obtain the project's resolved
   source paths from `clojure.tools.deps/create-basis`, then walks them
   for cljs/cljc files. `discover-paths` returns paths verbatim from the
   deps.edn (relative strings like \"src\"); they are absolutized against
   `root` here before walking."
  (:require [skeptic.cli.cljs.discover :as discover]
            [skeptic.cli.paths :as paths]))

(defn discover-sources
  [root aliases]
  (let [raw-paths (paths/discover-paths root aliases)
        source-paths (mapv #(discover/absolutize root %) raw-paths)
        {:keys [cljs-files cljc-files]} (discover/discover-cljs-and-cljc source-paths)]
    {:source-paths source-paths
     :cljs-files cljs-files
     :cljc-files cljc-files}))
