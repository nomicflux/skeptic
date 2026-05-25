(ns skeptic.cljs-fixtures.p15-reader-conditional-ns.core
  #?(:cljs (:require-macros [skeptic.cljs-fixtures.p15-reader-conditional-ns.missing-macros]))
  (:require #?@(:cljs [[skeptic.cljs-fixtures.p15-reader-conditional-ns.dep :as dep]])))

(def x #?(:cljs dep/value
          :clj nil))
