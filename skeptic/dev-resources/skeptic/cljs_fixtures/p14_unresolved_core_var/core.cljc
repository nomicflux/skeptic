(ns skeptic.cljs-fixtures.p14-unresolved-core-var.core)

(defn fns
  [ns]
  (vals (ns-interns ns)))
