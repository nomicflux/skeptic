(ns skeptic.test-examples.wrapped-record
  "Record and protocol defined through a wrapper macro, so the worker's
   name-based defrecord/deftype skip does not apply and analysis reaches
   the deftype* and gen-interface forms.")

(defprotocol WrappedProto
  (wp-value [this]))

(defmacro defrecord-wrapped
  [name fields & body]
  `(defrecord ~name ~fields ~@body))

(defrecord-wrapped WrappedRec [x]
  WrappedProto
  (wp-value [_] x))

(defn make-wrapped []
  (->WrappedRec 1))
