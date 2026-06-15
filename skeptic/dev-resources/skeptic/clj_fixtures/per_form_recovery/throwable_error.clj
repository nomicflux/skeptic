(ns skeptic.clj-fixtures.per-form-recovery.throwable-error)

(throw (Error. "error-subclass-boom"))

(defn after-error [x] (str x))
