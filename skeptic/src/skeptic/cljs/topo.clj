(ns skeptic.cljs.topo
  "Dependency ordering for cljs/cljc source files. Returns files in an
   order where each file's project-local :require'd dependencies appear
   before it. When a cycle blocks standard topo progress, the next pick is
   chosen by tiebreaker: nss without :require-macros / :use-macros first,
   then fewest :requires, then ns-sym alphabetical."
  (:require [schema.core :as s]
            [skeptic.cljs.analyzer-driver :as driver]))

(s/defn ^:private file-head
  [project-nss :- #{s/Symbol}
   source-file :- s/Any]
  (let [ns-ast       (driver/parse-source-ns source-file)
        all-reqs     (set (vals (:requires ns-ast)))
        project-reqs (into #{} (filter project-nss) all-reqs)]
    {:ns               (:name ns-ast)
     :file             source-file
     :project-requires project-reqs
     :macro-free?      (and (empty? (:require-macros ns-ast))
                            (empty? (:use-macros ns-ast)))
     :requires-count   (count all-reqs)}))

(s/defn ^:private heads-by-ns :- {s/Symbol s/Any}
  [ns-sym->file :- {s/Symbol s/Any}]
  (let [project-nss (set (keys ns-sym->file))]
    (into {}
          (map (fn [[ns-sym f]] [ns-sym (file-head project-nss f)]))
          ns-sym->file)))

(s/defn ^:private initial-in-degrees :- {s/Symbol s/Int}
  [heads :- {s/Symbol s/Any}]
  (into {}
        (map (fn [[ns-sym head]]
               [ns-sym (count (:project-requires head))]))
        heads))

(s/defn ^:private cycle-pick :- s/Symbol
  [remaining :- {s/Symbol s/Any}]
  (->> remaining
       (sort-by (fn [[ns-sym {:keys [macro-free? requires-count]}]]
                  [(if macro-free? 0 1) requires-count (str ns-sym)]))
       ffirst))

(s/defn ^:private next-pick :- s/Symbol
  [remaining :- {s/Symbol s/Any}
   in-deg    :- {s/Symbol s/Int}]
  (or (some (fn [[ns-sym _]] (when (zero? (in-deg ns-sym)) ns-sym))
            remaining)
      (cycle-pick remaining)))

(s/defn ^:private decrement-dependents :- {s/Symbol s/Int}
  [in-deg    :- {s/Symbol s/Int}
   remaining :- {s/Symbol s/Any}
   pick      :- s/Symbol]
  (reduce-kv (fn [d other-ns {:keys [project-requires]}]
               (if (contains? project-requires pick)
                 (update d other-ns dec)
                 d))
             in-deg
             remaining))

(s/defn topo-sort-heads :- [s/Symbol]
  "Pure topo sort over a `heads` map. Each head supplies
   `:project-requires`, `:macro-free?`, `:requires-count`. Returns ns-syms
   in dependency-respecting order; cycles fall through to `cycle-pick`."
  [heads :- {s/Symbol s/Any}]
  (loop [remaining heads
         in-deg    (initial-in-degrees heads)
         out       []]
    (if (empty? remaining)
      out
      (let [pick (next-pick remaining in-deg)]
        (recur (dissoc remaining pick)
               (decrement-dependents in-deg remaining pick)
               (conj out pick))))))

(s/defn topo-sort-files :- [s/Any]
  "Order source-files so each file's project deps appear before it."
  [ns-sym->file :- {s/Symbol s/Any}]
  (let [heads (heads-by-ns ns-sym->file)]
    (mapv #(get-in heads [% :file]) (topo-sort-heads heads))))
