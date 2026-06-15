(ns skeptic.clj-fixtures.per-form-recovery.bare-throws)

(throw (ex-info "bare-boom" {}))

(defn after-bare [x] (str x))
