(ns skeptic.cli.cljs.lein
  "Source discovery for cljs/cljc files in a Leiningen project. Reads
   :source-paths and :test-paths from the project map, plus any cljsbuild
   build :source-paths if present, and walks them for cljs/cljc files."
  (:require [skeptic.cli.cljs.discover :as discover]))

(defn- cljsbuild-paths [project]
  (let [root (:root project)]
    (->> (get-in project [:cljsbuild :builds])
         (mapcat :source-paths)
         (remove nil?)
         (mapv #(discover/absolutize root %)))))

(defn discover-sources
  [project]
  (let [base-paths (concat (:source-paths project)
                           (:test-paths project)
                           (cljsbuild-paths project))
        source-paths (vec (distinct base-paths))
        {:keys [cljs-files cljc-files]} (discover/discover-cljs-and-cljc source-paths)]
    {:source-paths source-paths
     :cljs-files cljs-files
     :cljc-files cljc-files}))
