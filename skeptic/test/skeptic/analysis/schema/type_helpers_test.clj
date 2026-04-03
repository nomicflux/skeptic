 (ns skeptic.analysis.schema.type-helpers-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.bridge :as ab]
             [skeptic.analysis.bridge.algebra :as aba]
             [skeptic.analysis.bridge.canonicalize :as abc]
             [skeptic.analysis.bridge.localize :as abl]
             [skeptic.analysis.bridge.render :as abr]
             [skeptic.analysis.schema.cast-support :as ascs]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema-base :as sb]
             [skeptic.analysis.types :as at]))

 (declare UnboundSchemaRef)

 (def BoundSchemaRef s/Int)
 (def RecursiveSchemaRef [#'RecursiveSchemaRef])

 (defn T
   [schema]
   (ab/schema->type schema))

 (deftest tagged-polymorphic-type-helpers-test
   (let [type-var (at/->TypeVarT 'X)
         forall (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                            type-var
                                                            1
                                                            false)]))
         sealed (at/->SealedDynT type-var)
         localized (abl/localize-schema-value {:poly forall
                                               :sealed sealed})
         stripped (abr/strip-derived-types {:schema s/Int
                                            :type forall
                                            :output-type sealed})]
     (is (at/type-var-type? type-var))
     (is (at/forall-type? forall))
     (is (at/sealed-dyn-type? sealed))
     (is (at/semantic-type-value? forall))
     (is (= forall (get localized :poly)))
     (is (= sealed (get localized :sealed)))
     (is (= {:schema s/Int
             :type forall
             :output-type sealed}
            stripped))
     (is (= #{'Y}
            (aba/type-free-vars (at/->ForallT 'X
                                              (at/->FunT [(at/->FnMethodT [type-var]
                                                                          (at/->TypeVarT 'Y)
                                                                          1
                                                                          false)])))))
     (is (= (at/->ForallT 'X (at/->TypeVarT 'X))
            (aba/type-substitute (at/->ForallT 'X (at/->TypeVarT 'X))
                                 'X
                                 (ab/schema->type s/Any))))))

 (deftest display-keeps-type-and-schema-domains-separate-test
   (let [type-var (at/->TypeVarT 'X)
         polymorphic-map (at/->MapT {(at/->GroundT :keyword 'Keyword)
                                     (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                                                (at/->SealedDynT type-var)
                                                                                1
                                                                                false)]))})]
     (is (= '{Keyword (forall X (=> (sealed X) X))}
            (abr/display-form polymorphic-map)))
     (is (= "hello"
            (abr/display-form (T (s/eq "hello")))))
     (is (= 'Int
            (abr/display-form s/Int)))))

 (deftest raw-schema-var-normalization-test
   (is (= s/Int
          (abc/canonicalize-schema #'BoundSchemaRef)))
   (is (= "Int"
          (abr/render-type (ab/schema->type #'BoundSchemaRef))))
   (is (= (sb/placeholder-schema 'skeptic.analysis.schema.type-helpers-test/UnboundSchemaRef)
          (abc/canonicalize-schema #'UnboundSchemaRef)))
   (is (= 'skeptic.analysis.schema.type-helpers-test/UnboundSchemaRef
          (-> #'UnboundSchemaRef
              ab/schema->type
              :ref)))
   (let [unbound-root (.getRawRoot ^clojure.lang.Var #'UnboundSchemaRef)]
     (is (= (sb/placeholder-schema 'skeptic.analysis.schema.type-helpers-test/UnboundSchemaRef)
            (abc/canonicalize-schema unbound-root)))
     (is (= 'skeptic.analysis.schema.type-helpers-test/UnboundSchemaRef
            (-> unbound-root
                ab/schema->type
                :ref))))
   (is (= [(sb/placeholder-schema 'skeptic.analysis.schema.type-helpers-test/RecursiveSchemaRef)]
          (abc/canonicalize-schema #'RecursiveSchemaRef)))
   (let [recursive-type (ab/schema->type #'RecursiveSchemaRef)]
     (is (at/vector-type? recursive-type))
     (is (= 'skeptic.analysis.schema.type-helpers-test/RecursiveSchemaRef
            (-> recursive-type :items first :ref)))))

 (deftest semantic-function-type-rendering-test
   (let [fun-type (at/->FunT [(at/->FnMethodT [(ab/schema->type s/Int)]
                                              (ab/intersection-type [s/Any s/Int])
                                              1
                                              false)])
         polymorphic-fun (at/->FunT [(at/->FnMethodT [(at/->TypeVarT 'X)]
                                                     (at/->SealedDynT (at/->TypeVarT 'X))
                                                     1
                                                     false)])]
     (is (= fun-type (ab/schema->type fun-type)))
     (is (= "(=> (intersection Any Int) Int)"
            (abr/render-type fun-type)))
     (is (= "(=> (sealed X) X)"
            (abr/render-type polymorphic-fun)))))
