(ns skeptic.checking.pipeline.named-fold-contract-audit-probe-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge.render :as render]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.provenance :as prov]
            [skeptic.test-examples.named-fold-contract-probe])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))
(def fixture-ns 'skeptic.test-examples.named-fold-contract-probe)

(def declaration-syms
  ['skeptic.test-examples.named-fold-contract-probe/x
   'skeptic.test-examples.named-fold-contract-probe/y
   'skeptic.test-examples.named-fold-contract-probe/z
   'skeptic.test-examples.named-fold-contract-probe/w
   'skeptic.test-examples.named-fold-contract-probe/q])

(def rendered-fn-syms
  ['skeptic.test-examples.named-fold-contract-probe/fn-with-call
   'skeptic.test-examples.named-fold-contract-probe/fn-with-composed
   'skeptic.test-examples.named-fold-contract-probe/fn-with-literal
   'skeptic.test-examples.named-fold-contract-probe/add-with-cache-analogue])

(def input-probe-sym
  'skeptic.test-examples.named-fold-contract-probe/add-with-cache-input-probe)

(defn- type-kind
  [t]
  (cond
    (at/dyn-type? t) :dyn
    (at/bottom-type? t) :bottom
    (at/ground-type? t) :ground
    (at/numeric-dyn-type? t) :numeric-dyn
    (at/refinement-type? t) :refinement
    (at/adapter-leaf-type? t) :adapter-leaf
    (at/optional-key-type? t) :optional-key
    (at/value-type? t) :value
    (at/type-var-type? t) :type-var
    (at/forall-type? t) :forall
    (at/sealed-dyn-type? t) :sealed
    (at/inf-cycle-type? t) :inf-cycle
    (at/fn-method-type? t) :fn-method
    (at/fun-type? t) :fun
    (at/maybe-type? t) :maybe
    (at/conditional-type? t) :conditional
    (at/union-type? t) :union
    (at/intersection-type? t) :intersection
    (at/map-type? t) :map
    (at/vector-type? t) :vector
    (at/set-type? t) :set
    (at/seq-type? t) :seq
    (at/var-type? t) :var
    (at/placeholder-type? t) :placeholder
    :else (symbol (.getSimpleName (class t)))))

(defn- child-pairs
  [t]
  (cond
    (at/map-type? t)
    (mapcat (fn [idx [k v]]
              [[[:entry idx :key] k]
               [[:entry idx :val] v]])
            (range)
            (sort-by (comp pr-str first) (:entries t)))

    (at/vector-type? t)
    (map-indexed (fn [idx item] [[:item idx] item]) (:items t))

    (at/set-type? t)
    (map-indexed (fn [idx item] [[:member idx] item]) (sort-by pr-str (:members t)))

    (at/seq-type? t)
    (map-indexed (fn [idx item] [[:item idx] item]) (:items t))

    (at/fun-type? t)
    (map-indexed (fn [idx method] [[:method idx] method]) (:methods t))

    (at/fn-method-type? t)
    (concat (map-indexed (fn [idx input] [[:input idx] input]) (:inputs t))
            [[[:output] (:output t)]])

    (at/maybe-type? t)
    [[[:inner] (:inner t)]]

    (at/optional-key-type? t)
    [[[:inner] (:inner t)]]

    (at/var-type? t)
    [[[:inner] (:inner t)]]

    (at/value-type? t)
    [[[:inner] (:inner t)]]

    (at/refinement-type? t)
    [[[:base] (:base t)]]

    (at/forall-type? t)
    [[[:body] (:body t)]]

    (at/sealed-dyn-type? t)
    [[[:ground] (:ground t)]]

    (at/conditional-type? t)
    (map-indexed (fn [idx [_pred branch]] [[:branch idx] branch]) (:branches t))

    (at/union-type? t)
    (map-indexed (fn [idx member] [[:member idx] member]) (sort-by pr-str (:members t)))

    (at/intersection-type? t)
    (map-indexed (fn [idx member] [[:member idx] member]) (sort-by pr-str (:members t)))

    :else []))

(defn- trace-type
  [path t]
  (let [p (prov/of t)
        row {:path path
             :kind (type-kind t)
             :source (prov/source p)
             :qualified-sym (:qualified-sym p)
             :declared-in (:declared-in p)
             :refs (mapv :qualified-sym (:refs p))}]
    (cons row
          (mapcat (fn [[seg child]]
                    (trace-type (into path seg) child))
                  (child-pairs t)))))

(defn- value-type-for
  [output-map kw]
  (some (fn [[k v]] (when (and (at/value-type? k) (= (:value k) kw)) v))
        (:entries output-map)))

(defn- analyzed-fn-output
  [analyzed target-sym]
  (let [target-form (some (fn [a]
                            (let [n (some-> a aapi/unwrap-with-meta)
                                  [sym _] (aapi/analyzed-def-entry fixture-ns n)]
                              (when (= sym target-sym) n)))
                          analyzed)
        init-node (some-> target-form aapi/def-init-node aapi/unwrap-with-meta)
        method (first (aapi/function-methods init-node))]
    {:target-form target-form
     :method method
     :actual-output (some-> method aapi/method-result-type :output-type ato/normalize)}))

(deftest audit-declaration-side-provs
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        results (into {}
                      (map (fn [sym]
                             (let [t (get dict sym)
                                   p (prov/of t)]
                               [sym {:kind (type-kind t)
                                     :source (prov/source p)
                                     :qualified-sym (:qualified-sym p)
                                     :render (render/render-type-form t)}])))
                      declaration-syms)]
    (println "\nDECLARATION PROVS")
    (binding [*print-length* nil *print-level* nil]
      (prn results))
    (is (= (set declaration-syms) (set (keys results))))))

(deftest audit-input-side-declared-provs
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        fn-type (get dict input-probe-sym)
        methods (:methods fn-type)
        arity-3 (some #(when (= 3 (:min-arity %)) %) methods)
        input-types (:inputs arity-3)
        output-type (:output arity-3)]
    (println "\nDECLARED INPUT PROVS")
    (binding [*print-length* nil *print-level* nil]
      (prn {:inputs (mapv trace-type [[:input0] [:input1] [:input2]] input-types)
            :output (trace-type [:output] output-type)
            :render (render/render-type-form fn-type)}))
    (is (= 3 (count input-types)))))

(deftest audit-actual-side-renders-and-provs
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        results (into {}
                      (map (fn [sym]
                             (let [{:keys [actual-output]} (analyzed-fn-output analyzed sym)
                                   result-type (when (at/map-type? actual-output)
                                                 (value-type-for actual-output :result))
                                   cache-type (when (at/map-type? actual-output)
                                                (value-type-for actual-output :cache))]
                               [sym {:render (render/render-type-form actual-output)
                                     :result-trace (when result-type
                                                     (trace-type [:result] result-type))
                                     :cache-trace (when cache-type
                                                    (trace-type [:cache] cache-type))}])))
                      rendered-fn-syms)]
    (println "\nACTUAL OUTPUT AUDIT")
    (binding [*print-length* nil *print-level* nil]
      (prn results))
    (is (= (set rendered-fn-syms) (set (keys results))))))
