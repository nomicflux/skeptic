(ns skeptic.output
  (:require [skeptic.output.porcelain :as porcelain]
            [skeptic.output.text :as text]))

(defn printer
  "Return the printer lifecycle map for the given opts.
  Porcelain emits JSONL; otherwise the human-readable text printer."
  [opts]
  (if (:porcelain opts)
    porcelain/printer
    text/printer))
