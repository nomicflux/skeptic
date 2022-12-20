(ns skeptic.type-vars)

(defn type-var
  [name]
  {:var name})

(defn is-var?
  [{:keys [var]}]
  (not (nil? var)))
