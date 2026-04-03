(ns skeptic.checking.form-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.form :as cf])
  (:import [java.io File]))

(deftest schema-defn-symbol-test
  (is (cf/schema-defn-symbol? 'schema.core/defn))
  (is (cf/schema-defn-symbol? 's/defn))
  (is (not (cf/schema-defn-symbol? 'defn)))
  (is (not (cf/schema-defn-symbol? 'clojure.core/defn))))

(deftest normalize-check-form-strips-schema-defn
  (is (= '(defn foo ([x] x))
         (cf/normalize-check-form
          '(schema.core/defn foo :- s/Int [x :- s/Int] x))))
  (is (= '(defn bar ([a b] (+ a b)))
         (cf/normalize-check-form
          '(s/defn bar :- s/Int [a :- s/Int b :- s/Int] (+ a b)))))
  (is (= '(defn plain [x] x)
         (cf/normalize-check-form '(defn plain [x] x)))))

(deftest valid-schema-test
  (is (true? (cf/valid-schema? s/Int)))
  (is (true? (cf/valid-schema? s/Any))))

(deftest source-file-path-test
  (is (nil? (cf/source-file-path nil)))
  (is (= "/tmp/example.clj" (cf/source-file-path (File. "/tmp/example.clj"))))
  (is (= "relative.clj" (cf/source-file-path "relative.clj"))))

(deftest merge-and-form-location-test
  (is (= {:file "a.clj" :line 2 :column 3}
         (cf/merge-location {:file "a.clj" :line nil}
                            {:line 2 :column 3})))
  (let [f (File. "src/example.clj")
        frm (with-meta '(+ 1 2) {:line 5 :column 1 :end-line 5 :end-column 8})]
    (is (= {:file (.getPath f) :line 5 :column 1 :end-line 5 :end-column 8}
           (cf/form-location f frm)))))

(deftest form-source-and-with-form-meta-test
  (is (= "(+ 1 2)" (cf/form-source (with-meta '(+ 1 2) {:source "(+ 1 2)"}))))
  (let [orig (with-meta [1] {:preserve true})
        rw [2 3]]
    (is (= {:preserve true} (meta (cf/with-form-meta orig rw))))))

(deftest defn-decls-and-method-source-body-test
  (is (= '([a b] (+ a b))
         (first (cf/defn-decls '(defn foo [a b] (+ a b))))))
  (is (= '(+ 1 2) (cf/method-source-body '([x] (+ 1 2)))))
  (is (nil? (cf/method-source-body '([x]))))
  (is (= '(do a b) (cf/method-source-body '([x] a b)))))

(deftest display-expr-and-node-error-context-test
  (let [expr (with-meta '(f x) {:source "(f x)" :file "f.clj" :line 1 :column 1})
        node {:form expr}
        disp (cf/display-expr node)
        ctx (cf/node-error-context node 'some.ns/wrapper)]
    (is (= '(f x) (:expr disp)))
    (is (= "(f x)" (:source-expression disp)))
    (is (= {:file "f.clj" :line 1 :column 1} (:location disp)))
    (is (= 'some.ns/wrapper (:enclosing-form ctx)))
    (is (= (:expr disp) (:expr ctx)))))

(deftest strip-schema-argvec-test
  (is (= '[x y] (cf/strip-schema-argvec '[x :- s/Int y :- s/Str]))))
