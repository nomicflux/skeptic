(ns skeptic.clj-fixtures.per-form-recovery.def-throws)

(def boom (throw (ex-info "boom-def" {})))

(def downstream-of-boom boom)

(defn neighbor [x] (inc x))
