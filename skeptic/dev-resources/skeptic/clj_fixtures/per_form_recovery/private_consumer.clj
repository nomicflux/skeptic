(ns skeptic.clj-fixtures.per-form-recovery.private-consumer
  (:require [schema.core :as s]
            [skeptic.clj-fixtures.per-form-recovery.private-owner :as owner]))

(s/defn calls-private :- s/Keyword [] (owner/secret))

(s/defn neighbor :- s/Int [x :- s/Int] (+ x 1))
