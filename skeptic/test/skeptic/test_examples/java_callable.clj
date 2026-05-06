(ns skeptic.test-examples.java-callable
  (:require [schema.core :as s])
  (:import [java.util Comparator HashMap]
           [java.util.concurrent Callable]
           [java.util.function BiConsumer BiFunction BiPredicate
                               BinaryOperator Consumer Function
                               Predicate Supplier UnaryOperator]))

(s/defn runnable-takes :- s/Any [_r :- Runnable] nil)
(s/defn runnable-arity-0-success [] (runnable-takes (fn [] :anything)))
(s/defn runnable-arity-2-failure [] (runnable-takes (fn [_x _y] :anything)))

(s/defn callable-takes :- s/Any [_c :- Callable] nil)
(s/defn callable-arity-0-success [] (callable-takes (fn [] 1)))
(s/defn callable-arity-1-failure [] (callable-takes (fn [_x] 1)))

(s/defn comparator-takes :- s/Any [_c :- Comparator] nil)
(s/defn comparator-arity-2-int-success [] (comparator-takes (fn [_a _b] 0)))
(s/defn comparator-arity-1-failure     [] (comparator-takes (fn [_a] 0)))
(s/defn comparator-return-str-failure  [] (comparator-takes (fn [_a _b] "no")))

(s/defn function-takes :- s/Any [_f :- Function] nil)
(s/defn function-arity-1-success [] (function-takes (fn [_x] :anything)))
(s/defn function-arity-0-failure [] (function-takes (fn [] :anything)))

(s/defn supplier-takes :- s/Any [_f :- Supplier] nil)
(s/defn supplier-arity-0-success [] (supplier-takes (fn [] :anything)))
(s/defn supplier-arity-1-failure [] (supplier-takes (fn [_x] :anything)))

(s/defn consumer-takes :- s/Any [_f :- Consumer] nil)
(s/defn consumer-arity-1-success [] (consumer-takes (fn [_x] nil)))
(s/defn consumer-arity-0-failure [] (consumer-takes (fn [] nil)))

(s/defn predicate-takes :- s/Any [_p :- Predicate] nil)
(s/defn predicate-arity-1-bool-success [] (predicate-takes (fn [_x] true)))
(s/defn predicate-arity-0-failure      [] (predicate-takes (fn [] true)))
(s/defn predicate-return-int-failure   [] (predicate-takes (fn [_x] 1)))

(s/defn bifunction-takes :- s/Any [_f :- BiFunction] nil)
(s/defn bifunction-arity-2-success [] (bifunction-takes (fn [_a _b] :anything)))
(s/defn bifunction-arity-1-failure [] (bifunction-takes (fn [_a] :anything)))

(s/defn bipredicate-takes :- s/Any [_p :- BiPredicate] nil)
(s/defn bipredicate-arity-2-bool-success [] (bipredicate-takes (fn [_a _b] true)))
(s/defn bipredicate-arity-1-failure      [] (bipredicate-takes (fn [_a] true)))
(s/defn bipredicate-return-int-failure   [] (bipredicate-takes (fn [_a _b] 1)))

(s/defn biconsumer-takes :- s/Any [_f :- BiConsumer] nil)
(s/defn biconsumer-arity-2-success [] (biconsumer-takes (fn [_a _b] nil)))
(s/defn biconsumer-arity-1-failure [] (biconsumer-takes (fn [_a] nil)))

(s/defn unaryop-takes :- s/Any [_f :- UnaryOperator] nil)
(s/defn unaryop-arity-1-success [] (unaryop-takes (fn [_x] :anything)))
(s/defn unaryop-arity-2-failure [] (unaryop-takes (fn [_a _b] :anything)))

(s/defn binaryop-takes :- s/Any [_f :- BinaryOperator] nil)
(s/defn binaryop-arity-2-success [] (binaryop-takes (fn [_a _b] :anything)))
(s/defn binaryop-arity-0-failure [] (binaryop-takes (fn [] :anything)))

(s/defn hashmap-takes :- s/Any [_m :- HashMap] nil)
(s/defn hashmap-fn-fixture [] (hashmap-takes (fn [] {})))
