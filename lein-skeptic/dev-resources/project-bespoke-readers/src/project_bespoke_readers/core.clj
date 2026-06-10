(ns project-bespoke-readers.core
  (:require [project-bespoke-readers.tags]))

(def point #bespoke/point [1 2])

(defn shift-point
  [p dx]
  (update p :point (fn [[x y]] [(+ x dx) y])))
