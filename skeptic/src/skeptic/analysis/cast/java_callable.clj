(ns skeptic.analysis.cast.java-callable
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def ^:private sam-spec
  {java.lang.Runnable                {:arity 0 :return-builder #(at/Dyn %)}
   java.util.concurrent.Callable     {:arity 0 :return-builder #(at/Dyn %)}
   java.util.Comparator              {:arity 2 :return-builder #(at/->GroundT % :int 'Int)}
   java.util.function.Function       {:arity 1 :return-builder #(at/Dyn %)}
   java.util.function.Supplier       {:arity 0 :return-builder #(at/Dyn %)}
   java.util.function.Consumer       {:arity 1 :return-builder #(at/Dyn %)}
   java.util.function.Predicate      {:arity 1 :return-builder #(at/->GroundT % :bool 'Bool)}
   java.util.function.BiFunction     {:arity 2 :return-builder #(at/Dyn %)}
   java.util.function.BiPredicate    {:arity 2 :return-builder #(at/->GroundT % :bool 'Bool)}
   java.util.function.BiConsumer     {:arity 2 :return-builder #(at/Dyn %)}
   java.util.function.UnaryOperator  {:arity 1 :return-builder #(at/Dyn %)}
   java.util.function.BinaryOperator {:arity 2 :return-builder #(at/Dyn %)}})

(defn- target-class [target-type]
  (-> target-type :ground :class))

(s/defn java-callable-target? :- s/Bool
  [t :- s/Any]
  (boolean
   (and (at/ground-type? t)
        (map? (:ground t))
        (contains? sam-spec (target-class t)))))

(defn- match-method [source-type arity]
  (some #(when (ascs/method-accepts-arity? % arity) %)
        (:methods source-type)))

(defn- range-result [run-child src-meth ret-type opts]
  (run-child {:source-type (:output src-meth)
              :target-type ret-type
              :opts opts
              :path-segment {:kind :function-range}}))

(s/defn check-java-callable-cast :- csch/CastResult
  [run-child   :- (s/pred fn?)
   source-type :- at/SemanticType
   target-type :- at/SemanticType
   opts        :- s/Any]
  (let [class    (target-class target-type)
        {:keys [arity return-builder]} (sam-spec class)
        ret-type (return-builder (prov/of target-type))
        polarity (:polarity opts)]
    (if-let [src-meth (match-method source-type arity)]
      (ascs/aggregate-children source-type target-type
                               :java-callable-return polarity :return-failed
                               [(range-result run-child src-meth ret-type opts)])
      (ascs/cast-fail source-type target-type
                      :java-callable-arity polarity :arity-mismatch
                      [] {:expected-arity arity :class class}))))
