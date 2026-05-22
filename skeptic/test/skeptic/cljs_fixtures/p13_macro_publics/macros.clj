(ns skeptic.cljs-fixtures.p13-macro-publics.macros
  (:require [cljs.analyzer.api :as ana]))

(defmacro public-count
  [[_quote ns-sym]]
  (count (ana/ns-publics ns-sym)))
