(ns skeptic.schema.discovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.schema.discovery :as discovery]))

(def fixture-ns 'skeptic.research.intake-combined-fixture)
(def fixture-file
  (java.io.File. "test/skeptic/research/intake_combined_fixture.clj"))

(deftest discover-recognizes-each-plumatic-producer
  (require fixture-ns)
  (let [{:keys [declarations]} (discovery/discover fixture-ns fixture-file)]
    (testing "aliased s/defn"
      (is (= :s/defn (get-in declarations
                              ['skeptic.research.intake-combined-fixture/aliased-defn :role]))))
    (testing "fully-qualified schema.core/defn"
      (is (= :s/defn (get-in declarations
                              ['skeptic.research.intake-combined-fixture/qualified-defn :role]))))
    (testing "alternative alias schemy/defn"
      (is (= :s/defn (get-in declarations
                              ['skeptic.research.intake-combined-fixture/schemy-defn :role]))))
    (testing "s/def"
      (is (= :s/def (get-in declarations
                             ['skeptic.research.intake-combined-fixture/aliased-def :role]))))
    (testing "s/defschema"
      (is (= :s/defschema (get-in declarations
                                   ['skeptic.research.intake-combined-fixture/AliasedSchema :role]))))
    (testing "s/defprotocol + method"
      (is (= :s/defprotocol (get-in declarations
                                     ['skeptic.research.intake-combined-fixture/AliasedProtocol :role])))
      (is (= :s/defprotocol-method (get-in declarations
                                            ['skeptic.research.intake-combined-fixture/aliased-method :role]))))
    (testing "s/defrecord class + three factories"
      (is (= :s/defrecord-class (get-in declarations
                                         ['skeptic.research.intake-combined-fixture/AliasedRecord :role])))
      (is (= :s/defrecord-factory (get-in declarations
                                           ['skeptic.research.intake-combined-fixture/->AliasedRecord :role])))
      (is (= :s/defrecord-factory (get-in declarations
                                           ['skeptic.research.intake-combined-fixture/map->AliasedRecord :role])))
      (is (= :s/defrecord-factory (get-in declarations
                                           ['skeptic.research.intake-combined-fixture/strict-map->AliasedRecord :role]))))
    (testing "cross-stream Var: appears in Plumatic discovery"
      (is (= :s/defn (get-in declarations
                              ['skeptic.research.intake-combined-fixture/cross-stream :role]))))))

(deftest discover-skips-non-plumatic-producers
  (require fixture-ns)
  (let [{:keys [declarations]} (discovery/discover fixture-ns fixture-file)]
    (testing "plain defn is NOT a Plumatic declaration"
      (is (not (contains? declarations 'skeptic.research.intake-combined-fixture/plain-defn))))
    (testing "m/=> is NOT a Plumatic declaration"
      (is (not (contains? declarations 'skeptic.research.intake-combined-fixture/malli-arrow))
          "malli-arrow Var was created by plain defn, but the m/=> form does not produce a Plumatic entry"))
    (testing "mx/defn is NOT a Plumatic declaration"
      (is (not (contains? declarations 'skeptic.research.intake-combined-fixture/malli-mx))))
    (testing "defn with :malli/schema Var-meta is NOT a Plumatic declaration"
      (is (not (contains? declarations 'skeptic.research.intake-combined-fixture/malli-meta-only))))
    (testing "do-wrapped s/defn is skipped (head is do, not s/defn)"
      (is (not (contains? declarations 'skeptic.research.intake-combined-fixture/do-wrapped-defn))))))

(deftest source-forms-carries-raw-pre-expansion-forms
  (require fixture-ns)
  (let [{:keys [source-forms]} (discovery/discover fixture-ns fixture-file)]
    (is (some? (get source-forms 'skeptic.research.intake-combined-fixture/aliased-defn)))
    (is (= 's/defn (first (get source-forms 'skeptic.research.intake-combined-fixture/aliased-defn))))))
