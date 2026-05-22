(ns skeptic.cljs-fixtures.p13-macro-publics.core
  (:require-macros [skeptic.cljs-fixtures.p13-macro-publics.macros :as macros]))

(def string-public-count
  (macros/public-count 'clojure.string))
