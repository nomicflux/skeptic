(ns p16-shadow-preload
  (:require #?@(:clj [[p16.missing-shadow-server :as missing]])))

#?(:cljs
   (defn value [] 1))

#?(:clj
   (defn value [] (missing/nope)))
