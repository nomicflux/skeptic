(ns skeptic.analysis.bridge.descriptors-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.bridge.descriptors :as descriptors]))

(deftest extract-defn-annotation-form-test
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- Foo [x] x))))
  (is (= {:kind :defn :output-form '{:result Foo :cache Bar} :arglists {0 {:input-forms []}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- {:result Foo :cache Bar} [] {}))))
  (is (= {:kind :defn :output-form '[Foo] :arglists {0 {:input-forms []}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- [Foo] [] []))))
  (is (= {:kind :defn :output-form '(s/maybe Foo) :arglists {0 {:input-forms []}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- (s/maybe Foo) [] nil))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f "doc" :- Foo [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f {:meta 1} :- Foo [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f "doc" {:meta 1} :- Foo [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]} 2 {:input-forms [nil nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- Foo ([x] x) ([x y] y)))))
  (is (= {:kind :defn :output-form nil :arglists {1 {:input-forms [nil]}}}
         (descriptors/extract-defn-annotation-form '(s/defn f [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {:varargs {:input-forms [nil nil] :count 2}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- Foo [x y & rest] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]} :varargs {:input-forms [nil] :count 1}}}
         (descriptors/extract-defn-annotation-form '(s/defn f :- Foo ([x] x) ([a & rest] a))))))

(deftest extract-def-annotation-form-test
  (is (= {:kind :def :schema-form 'Foo} (descriptors/extract-def-annotation-form '(s/def x :- Foo 42))))
  (is (= {:kind :def :schema-form '{:result Foo :cache Bar}} (descriptors/extract-def-annotation-form '(s/def x :- {:result Foo :cache Bar} 42))))
  (is (= {:kind :def :schema-form '[Foo]} (descriptors/extract-def-annotation-form '(s/def x :- [Foo] 42))))
  (is (= {:kind :def :schema-form nil} (descriptors/extract-def-annotation-form '(s/def x 42)))))

(deftest extract-defschema-body-form-test
  (is (= {:kind :defschema :schema-form '{:a Foo}} (descriptors/extract-defschema-body-form '(s/defschema X {:a Foo}))))
  (is (= {:kind :defschema :schema-form '[Foo]} (descriptors/extract-defschema-body-form '(s/defschema X [Foo]))))
  (is (= {:kind :defschema :schema-form '{:a Foo}} (descriptors/extract-defschema-body-form '(schema.core/defschema Y {:a Foo})))))

(deftest raw->descriptor-dispatches-by-head-name
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/raw->descriptor '(s/defn f :- Foo [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/raw->descriptor '(schema.core/defn f :- Foo [x] x))))
  (is (= {:kind :defn :output-form 'Foo :arglists {1 {:input-forms [nil]}}}
         (descriptors/raw->descriptor '(schemy/defn f :- Foo [x] x))))
  (is (= {:kind :def :schema-form 'Foo}
         (descriptors/raw->descriptor '(s/def x :- Foo 42))))
  (is (= {:kind :defschema :schema-form '{:a Foo}}
         (descriptors/raw->descriptor '(s/defschema X {:a Foo})))))

(deftest raw->descriptor-skips-non-plumatic-heads
  (is (nil? (descriptors/raw->descriptor '(do x))))
  (is (nil? (descriptors/raw->descriptor '(when x y))))
  (is (nil? (descriptors/raw->descriptor 'not-a-form)))
  (is (nil? (descriptors/raw->descriptor nil))))
