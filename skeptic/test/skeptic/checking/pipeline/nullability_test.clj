(ns skeptic.checking.pipeline.nullability-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest guarded-keys-nullability-contract
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/guarded-keys-caller)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-not-blank-maybe-str
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-not-blank-maybe-str-success))))

(deftest when-and-seq-truthy-any
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/when-and-seq-truthy-any-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-and-count-truthy-any
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/when-and-count-truthy-any-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest presents-str
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/presents-str))))

(deftest when-not-throw-nil-local
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-not-throw-nil-local-success))))

(deftest when-truthy-nil-local
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-truthy-nil-local-success))))

(deftest when-and-some?-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-nil-success))))

(deftest when-and-some?-and-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-and-nil-success))))

(deftest when-and-some?-multi-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-multi-nil-success))))

(deftest or-fallback-destructured
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/or-fallback-destructured-success))))

(deftest or-fallback-nil-middle
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/or-fallback-nil-middle-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest or-fallback-nil-last
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/or-fallback-nil-last-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest or-only-nil-alternative
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/or-only-nil-alternative-failure)]
    (is (seq (ps/result-errors results))
        "expected checker errors because (or x nil) can still be nil")))

(deftest let-bound-when-truthy-narrows-success
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/let-bound-when-truthy-narrows-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest or-nil-then-nullable
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/or-nil-then-nullable-failure)]
    (is (seq (ps/result-errors results))
        "expected checker errors because (or x nil x) can still be nil")))

(deftest if-or-nil-blank-destructured-narrows
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/if-or-nil-blank-destructured-narrows-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest if-or-nil-blank-optional-key-narrows
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/if-or-nil-blank-optional-key-narrows-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest if-or-nil-blank-direct-param-narrows
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/if-or-nil-blank-direct-param-narrows-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest pre-some?-narrows-map-key
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/pre-some?-narrows-map-key-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest if-string?-narrows-map-key
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/if-string?-narrows-map-key-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-some?-on-key
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/when-some?-on-key-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest pre-pos?-narrows-map-key
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/pre-pos?-narrows-map-key-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-and-pred-nil-throw-correlated
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/when-and-pred-nil-throw-correlated-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-and-pred-not-contains-throw-correlated
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/when-and-pred-not-contains-throw-correlated-success)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest c1-maybe-int-into-int-arg-fails
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/maybe-int-into-int-arg-failure)]
    (is (seq (ps/result-errors results))
        "expected checker error: (maybe Int) into non-null Int arg")))

(deftest c2-maybe-symbol-into-symbol-arg-fails
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/maybe-symbol-into-symbol-arg-failure)]
    (is (seq (ps/result-errors results))
        "expected checker error: (maybe Symbol) into non-null Symbol arg")))

(deftest c3-chained-maybe-into-int-arg-fails
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/chained-maybe-into-int-arg-failure)]
    (is (seq (ps/result-errors results))
        "expected checker error: chained (maybe Int) into non-null Int arg")))

;; Only the first (+ y x) is asserted: once a let-RHS use of `x` produces a
;; type error, subsequent uses are downstream of an inconsistency and not
;; guaranteed to be flagged.
(deftest let-bound-maybe-int-into-plus-fails
  (let [results (ps/check-fixture
                  'skeptic.test-examples.nullability/let-bound-maybe-int-into-plus-failure)
        blames  (set (map (comp pr-str :blame) results))
        plus-y-x '(. clojure.lang.Numbers (add y x))]
    (is (contains? blames (pr-str plus-y-x))
        (str "expected (+ y x) to be blamed; got blames: " (pr-str blames)))))

(deftest xns-let-bound-maybe-int-into-int-arg-fails
  (let [results (ps/check-fixture
                  'skeptic.test-examples.nullability/xns-let-bound-maybe-int-into-int-arg-failure)]
    (is (seq (ps/result-errors results))
        "expected checker error: cross-ns (maybe Int) from fn-ns/f let-bound and passed into non-null Int arg")))
