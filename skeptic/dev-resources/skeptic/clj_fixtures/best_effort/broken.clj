(ns skeptic.clj-fixtures.best-effort.broken)

(throw (ex-info "boom at load time" {}))
