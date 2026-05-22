(ns p10-var-quote
  (:require [cljs.reader :as reader]))

(defn read-inst [form]
  (#'reader/read-date form))
