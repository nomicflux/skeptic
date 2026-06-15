(ns skeptic.clj-fixtures.per-form-recovery.two-consecutive)

(throw (ex-info "first-boom" {}))

(throw (ex-info "second-boom" {}))

(defn after-two [x] (str x))
