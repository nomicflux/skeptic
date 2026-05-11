(ns skeptic.output
  (:require [schema.core :as s]
            [skeptic.checking.opts :as copts]
            [skeptic.output.porcelain :as porcelain]
            [skeptic.output.text :as text]))

(s/defn printer :- s/Any
  "Return the printer lifecycle map for the given opts.
  Porcelain emits JSONL; otherwise the human-readable text printer."
  [opts :- copts/CheckProjectOpts]
  (if (:porcelain opts)
    porcelain/printer
    text/printer))
