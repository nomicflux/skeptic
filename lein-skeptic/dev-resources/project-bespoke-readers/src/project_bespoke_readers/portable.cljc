(ns project-bespoke-readers.portable
  #?(:clj (:require [project-bespoke-readers.tags])))

(def spliced
  [:start #?@(:clj [1 2 3] :cljs [4 5 6]) :end])

(def defaulted
  #?(:clj :jvm
     :cljs :js
     :default :other))

(def default-only
  #?(:default :anything))

(def conditional-tagged
  #?(:clj #bespoke/point [3 4]
     :cljs nil))
