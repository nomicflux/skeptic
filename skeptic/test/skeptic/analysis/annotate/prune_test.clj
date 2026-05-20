(ns skeptic.analysis.annotate.prune-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.prune :as prune]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- nd
  ([op] {:op op :form op})
  ([op extra] (merge {:op op :form op} extra)))

(deftest drops-env-everywhere
  (is (= (nd :const)
         (prune/project-node (nd :const {:env {:huge :payload}}))))
  (is (= (nd :var {:info {:name 'foo/bar}})
         (prune/project-node (nd :var {:env {:locals {}}
                                       :info {:name 'foo/bar}})))))

(deftest reduces-info-on-var-ops-to-name-and-meta
  (testing ":info kept on :var ops contains only :name and :meta"
    (is (= (nd :var {:info {:name 'cljs.core/inc
                            :meta {:doc "..."}}})
           (prune/project-node
            (nd :var {:info {:name 'cljs.core/inc
                             :ns 'cljs.core
                             :meta {:doc "..."}
                             :fn-var true
                             :arglists '([x])}}))))))

(deftest drops-info-on-non-var-ops
  (is (not (contains? (prune/project-node
                       (nd :const {:info {:any :data}}))
                      :info))))

(deftest recurses-through-children-keyword-slot
  (testing "single-child slots like :test/:then/:else"
    (let [pruned (prune/project-node
                  (nd :if {:children [:test :then :else]
                           :test (nd :const {:env :x})
                           :then (nd :const {:env :y})
                           :else (nd :const {:env :z})}))]
      (is (= [:test :then :else] (:children pruned)))
      (is (not (contains? (:test pruned) :env)))
      (is (not (contains? (:then pruned) :env)))
      (is (not (contains? (:else pruned) :env))))))

(deftest recurses-through-vector-children
  (testing "vector-child slots like :methods/:args"
    (let [pruned (prune/project-node
                  (nd :fn {:children [:methods]
                           :methods [(nd :fn-method {:env :a})
                                     (nd :fn-method {:env :b})]}))]
      (is (= 2 (count (:methods pruned))))
      (is (every? #(not (contains? % :env)) (:methods pruned))))))

(deftest preserves-non-ast-sibling-fields
  (testing "fields like :form, :type, :origin, :name, :val remain"
    (let [prov     (prov/inferred {:name nil :ns nil} :clj)
          sem-type (at/Dyn prov)
          origin   {:kind :root :sym 'x :type sem-type}
          pruned   (prune/project-node
                    (nd :binding {:name 'x
                                  :type sem-type
                                  :origin origin
                                  :val 42
                                  :env :drop-me}))]
      (is (= 'x (:name pruned)))
      (is (= sem-type (:type pruned)))
      (is (= origin (:origin pruned)))
      (is (= 42 (:val pruned)))
      (is (not (contains? pruned :env))))))

(deftest projects-nested-init-on-binding
  (testing ":init subtree pruned recursively"
    (let [pruned (prune/project-node
                  (nd :binding {:name 'y
                                :children [:init]
                                :init (nd :invoke {:env :outer
                                                   :children [:fn :args]
                                                   :fn (nd :var {:env :inner
                                                                 :info {:name 'f :ns 'a :extra :junk}})
                                                   :args [(nd :const {:env :arg :info {:k :v}})]})}))]
      (is (not (contains? (:init pruned) :env)))
      (is (not (contains? (-> pruned :init :fn) :env)))
      (is (= {:name 'f} (-> pruned :init :fn :info)))
      (is (not (contains? (-> pruned :init :args first) :env)))
      (is (not (contains? (-> pruned :init :args first) :info))))))

(deftest idempotent-on-already-pruned-input
  (let [once  (prune/project-node (nd :var {:env :x :info {:name 'a :other :b}}))
        twice (prune/project-node once)]
    (is (= once twice))))
