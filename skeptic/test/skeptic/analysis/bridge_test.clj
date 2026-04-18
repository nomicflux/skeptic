(ns skeptic.analysis.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare UnboundSchemaRef)

(def BoundSchemaRef s/Int)
(def RecursiveSchemaRef [#'RecursiveSchemaRef])

(defn T
  [schema]
  (ab/schema->type schema))

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
    (is (= s/Int output-schema))
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
  (is (= (sb/placeholder-schema 'skeptic.analysis.bridge-test/UnboundSchemaRef)
         (abc/canonicalize-schema #'UnboundSchemaRef)))
  (is (= 'skeptic.analysis.bridge-test/UnboundSchemaRef
         (-> #'UnboundSchemaRef
             ab/schema->type
             :ref)))
  (let [unbound-root (.getRawRoot ^clojure.lang.Var #'UnboundSchemaRef)]
    (is (= (sb/placeholder-schema 'skeptic.analysis.bridge-test/UnboundSchemaRef)
           (abc/canonicalize-schema unbound-root)))
    (is (= 'skeptic.analysis.bridge-test/UnboundSchemaRef
           (-> unbound-root
               ab/schema->type
               :ref))))
  (is (= [(sb/placeholder-schema 'skeptic.analysis.bridge-test/RecursiveSchemaRef)]
         (abc/canonicalize-schema #'RecursiveSchemaRef)))
  (let [recursive-type (ab/schema->type #'RecursiveSchemaRef)]
    (is (at/vector-type? recursive-type))
    (is (= 'skeptic.analysis.bridge-test/RecursiveSchemaRef
           (-> recursive-type :items first :ref)))))

(deftest primitive-ground-type-skips-collection-classes-test
  (is (nil? (ab/primitive-ground-type clojure.lang.PersistentArrayMap)))
  (is (nil? (ab/primitive-ground-type clojure.lang.PersistentVector)))
  (is (nil? (ab/primitive-ground-type clojure.lang.LazySeq)))
  (is (nil? (ab/primitive-ground-type clojure.lang.PersistentHashSet)))
  (is (some? (ab/primitive-ground-type Exception)))
  (is (some? (ab/primitive-ground-type s/Int))))

(deftest schema-to-type-rejects-semantic-type-input-test
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected schema value"
                        (ab/schema->type (at/->GroundT :int 'Int)))))

(deftest canonicalize-schema-rejects-semantic-type-input-test
  (let [semantic-type (at/->GroundT :int 'Int)]
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected schema value"
                          (abc/canonicalize-schema semantic-type)))))

(deftest localize-and-strip-derived-types-test
  (let [type-var (at/->TypeVarT 'X)
        forall (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                           type-var
                                                           1
                                                           false)]))
        sealed (at/->SealedDynT type-var)
        localized (abl/localize-value {:poly forall
                                       :sealed sealed})
        stripped (abr/strip-derived-types {:schema s/Int
                                           :type forall
                                           :output-type sealed})]
    (is (at/semantic-type-value? forall))
    (is (= forall (get localized :poly)))
    (is (= sealed (get localized :sealed)))
    (is (= {:schema s/Int
            :type forall
            :output-type sealed}
           stripped))))
