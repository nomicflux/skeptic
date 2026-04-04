 (ns skeptic.analysis.schema.type-helpers-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.bridge :as ab]
             [skeptic.analysis.bridge.algebra :as aba]
             [skeptic.analysis.bridge.canonicalize :as abc]
             [skeptic.analysis.bridge.localize :as abl]
             [skeptic.analysis.bridge.render :as abr]
             [skeptic.analysis.cast.support :as ascs]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema-base :as sb]
             [skeptic.analysis.type-algebra :as ata]
             [skeptic.analysis.type-ops :as ato]
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
            (ata/type-free-vars (at/->ForallT 'X
                                              (at/->FunT [(at/->FnMethodT [type-var]
                                                                          (at/->TypeVarT 'Y)
                                                                          1
                                                                          false)])))))
     (is (= (at/->ForallT 'X (at/->TypeVarT 'X))
            (ata/type-substitute (at/->ForallT 'X (at/->TypeVarT 'X))
                                 'X
                                 (ab/schema->type s/Any))))))

 (deftest resolve-placeholders-stays-bridge-only-test
   (let [placeholder (sb/placeholder-schema 'example/X)
         schema (s/=> placeholder [placeholder])
         resolved (-> (aba/resolve-placeholders schema
                                                (fn [ref]
                                                  (when (= ref 'example/X)
                                                    s/Int)))
                      abc/canonicalize-schema)
         {:keys [input-schemas output-schema]} (into {} resolved)]
     (is (seq input-schemas))
     (is (= s/Int
            output-schema))
     (is (= '(=> Int [Int])
            (abc/schema-display-form resolved)))))

 (deftest display-keeps-type-and-schema-domains-separate-test
   (let [type-var (at/->TypeVarT 'X)
         polymorphic-map (at/->MapT {(at/->GroundT :keyword 'Keyword)
                                     (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                                                (at/->SealedDynT type-var)
                                                                                1
                                                                                false)]))})]
     (is (= '{Keyword (forall X (=> (sealed X) X))}
            (abr/render-type-form polymorphic-map)))
     (is (= "hello"
            (abr/render-type-form (T (s/eq "hello")))))
     (is (= 'Int
            (abc/schema-display-form s/Int)))))

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
                                              (ato/intersection-type [s/Any s/Int])
                                              1
                                              false)])
         polymorphic-fun (at/->FunT [(at/->FnMethodT [(at/->TypeVarT 'X)]
                                                     (at/->SealedDynT (at/->TypeVarT 'X))
                                                     1
                                                     false)])]
     (is (= fun-type (ato/normalize-type fun-type)))
     (is (= "(=> (intersection Any Int) Int)"
            (abr/render-type fun-type)))
     (is (= "(=> (sealed X) X)"
            (abr/render-type polymorphic-fun)))))

 (deftest schema-to-type-rejects-semantic-type-input-test
   (is (thrown-with-msg? IllegalArgumentException
                         #"Expected Schema-domain value"
                         (ab/schema->type (at/->GroundT :int 'Int)))))

 (deftest type-ops-normalization-and-unknown-test
   (is (= (at/->ValueT (at/->GroundT :keyword 'Keyword) :k)
          (ato/exact-value-type :k)))
   (is (= (at/->MaybeT at/Dyn)
          (ato/normalize-type nil)))
   (is (= (at/->GroundT :int 'Int)
          (ato/de-maybe-type (at/->MaybeT (at/->GroundT :int 'Int)))))
   (is (ato/unknown-type? at/Dyn))
   (is (ato/unknown-type? (at/->PlaceholderT 'example/x)))
   (is (not (ato/unknown-type? (ab/schema->type s/Int)))))
