(ns skeptic.cljs.topo-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.cljs.analyzer-driver :as driver]
            [skeptic.cljs.topo :as sut]))

(defn- head [requires macro-free? requires-count]
  {:project-requires (set requires)
   :macro-free?      macro-free?
   :requires-count   requires-count})

(deftest linear-chain-emits-leaves-first
  (let [heads {'a (head '[b] true 1)
               'b (head '[c] true 1)
               'c (head []    true 0)}]
    (is (= '[c b a] (sut/topo-sort-heads heads)))))

(deftest diamond-emits-shared-root-first-and-consumer-last
  (let [heads {'a (head '[b c] true 2)
               'b (head '[d]   true 1)
               'c (head '[d]   true 1)
               'd (head []     true 0)}
        out   (sut/topo-sort-heads heads)]
    (is (= 'd (first out)))
    (is (= 'a (last out)))
    (is (= #{'b 'c} (set (subvec out 1 3))))))

(deftest cycle-prefers-macro-free-namespace
  (let [heads {'a {:project-requires #{'b} :macro-free? false :requires-count 2}
               'b {:project-requires #{'a} :macro-free? true  :requires-count 1}}]
    (is (= '[b a] (sut/topo-sort-heads heads)))))

(deftest cycle-prefers-fewer-requires-when-macro-status-equal
  (let [heads {'a {:project-requires #{'b} :macro-free? true :requires-count 5}
               'b {:project-requires #{'a} :macro-free? true :requires-count 1}}]
    (is (= '[b a] (sut/topo-sort-heads heads)))))

(deftest cycle-alphabetical-final-tiebreaker
  (let [heads {'zz {:project-requires #{'aa} :macro-free? true :requires-count 1}
               'aa {:project-requires #{'zz} :macro-free? true :requires-count 1}}]
    (is (= '[aa zz] (sut/topo-sort-heads heads)))))

(deftest cycle-fallback-then-resumes-normal-topo
  ;; a ↔ b (cycle), c depends on a. After cycle-pick emits b then a, c is
  ;; in-degree 0 and emits normally last.
  (let [heads {'a {:project-requires #{'b} :macro-free? false :requires-count 1}
               'b {:project-requires #{'a} :macro-free? true  :requires-count 1}
               'c {:project-requires #{'a} :macro-free? true  :requires-count 1}}]
    (is (= '[b a c] (sut/topo-sort-heads heads)))))

(deftest namespace-head-uses-cljs-reader-conditionals-without-loading-macros
  (let [core "dev-resources/skeptic/cljs_fixtures/p15_reader_conditional_ns/core.cljc"
        dep  "dev-resources/skeptic/cljs_fixtures/p15_reader_conditional_ns/dep.cljs"
        ns-ast (driver/parse-source-ns-head core)]
    (is (= 'skeptic.cljs-fixtures.p15-reader-conditional-ns.dep
           (get (:requires ns-ast) 'dep)))
    (is (contains? (:require-macros ns-ast)
                   'skeptic.cljs-fixtures.p15-reader-conditional-ns.missing-macros))
    (is (= [dep core]
           (sut/topo-sort-files
            {'skeptic.cljs-fixtures.p15-reader-conditional-ns.core core
             'skeptic.cljs-fixtures.p15-reader-conditional-ns.dep dep})))))
