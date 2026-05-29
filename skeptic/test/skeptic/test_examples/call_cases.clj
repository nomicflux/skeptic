(ns skeptic.test-examples.call-cases
  "Fixtures for calls-test. Former `(analyze-form '(form) {:locals ...})` probes
   become real `s/defn`s whose parameters supply the former injected locals with
   their declared schemas. Fn-typed locals become real callable parameters or
   declared helper fns so the worker analyzes the call site against a real type."
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :as basics]))

;; invoke-and-static-application-roots-test / analyse-application-test
(s/defn cc-do-roots [] (do (str "hello") (+ 1 2)))
(s/defn cc-plus-local [x :- s/Any] (+ 1 x))
(s/defn cc-zero-arity-invoke [f :- (s/make-fn-schema s/Int [[]])] (f))
(s/defn cc-nested-invoke [f :- s/Any] ((f 1) 3 4))

;; typed-application-call-test / attach-type-info-application-test
(s/defn cc-dynamic-plus [] (+ 1 2))
(s/defn cc-unknown-invoke [f :- s/Any] (f 1 2))
(s/defn cc-known-call [] (skeptic.test-examples.basics/int-add 1 2))

;; canonicalized-callable-entry-test
(s/defn cc-returns-symbol :- clojure.lang.Symbol [arg :- s/Str] (symbol arg))
(s/defn cc-returns-keyword :- clojure.lang.Keyword [arg :- s/Any] (keyword (str arg)))
(s/defn cc-returns-int :- java.lang.Integer [arg :- s/Any] (int 0))
(s/defn cc-symbol-call :- s/Symbol [] (cc-returns-symbol "x"))
(s/defn cc-keyword-call :- s/Keyword [] (cc-returns-keyword :x))
(s/defn cc-int-call :- s/Int [] (cc-returns-int :x))
(s/defn cc-quoted-symbol [] (quote foo))

;; attach-type-info-local-fn-invocation-test
(s/defn cc-local-fn-invocation [x :- s/Any]
  (let [f (fn [x] nil)]
    (basics/int-add 1 (f x))))
