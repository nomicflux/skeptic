(ns skeptic.cljs-fixtures.p10-var-quote.core
  (:require [cljs.reader :as reader]
            [skeptic.cljs-fixtures.p10-var-quote.dep :as dep]))

(defn read-inst [form]
  (#'reader/read-date form))

(defn bad-var-quote-call []
  (#'dep/parse 1))
