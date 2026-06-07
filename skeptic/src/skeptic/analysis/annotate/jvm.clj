(ns skeptic.analysis.annotate.jvm
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.call-kinds.numeric :as ck-numeric]
            [skeptic.analysis.call-kinds.projection :as ck-projection]
            [skeptic.analysis.call-kinds.static-output :as ck-static-output]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]))

(s/defn annotate-instance-call :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:instance node)
   (fn [instance]
     (runner/sequence-children ctx (:args node)
      (fn [args]
        (let [type (when (= 'nth (:method node))
                     (coll/instance-nth-element-type (:type instance) (first args)))]
          (runner/done
           (assoc node :instance instance :args args :type (or type (aapi/dyn ctx))))))))))

(defn- static-native-output-type
  [ctx node args actual-argtypes default-output-type native-info]
  (if native-info
    (ck-numeric/static-numeric-narrow-type (ato/derive-prov default-output-type)
                                           node args actual-argtypes native-info)
    (aapi/dyn ctx)))

(s/defn annotate-static-call :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children ctx (:args node)
   (fn [args]
     (let [actual-argtypes (mapv :type args)
           native-info (native-fns/static-call-native-info (:class node) (:method node) (count args))
           default-output-type (or (some-> native-info :output-type)
                                   (some-> args first :type)
                                   (aapi/dyn ctx))
           expected-argtypes (or (:expected-argtypes native-info)
                                 (vec (repeat (count args) (aapi/dyn ctx))))
           type (or (ck-static-output/static-call-output-type ctx node args default-output-type)
                    (static-native-output-type ctx node args actual-argtypes default-output-type native-info))
           origin (ck-projection/static-get-map-key-lookup-origin ctx node args)]
       (runner/done
        (cond-> (assoc node
                       :args args
                       :actual-argtypes actual-argtypes
                       :expected-argtypes expected-argtypes
                       :type type)
          origin
          (assoc :origin origin)))))))
