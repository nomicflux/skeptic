(ns skeptic.clj-fixtures.per-form-recovery.ns-throws
  (:import [no.such.package NoSuchClass]))

(defn unreachable [] :would-not-be-checked)
