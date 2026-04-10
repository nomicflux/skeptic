(ns skeptic.inconsistence.path-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.inconsistence.report :as inc]
            [skeptic.inconsistence.path :as sut]))

(def sample-ctx
  {:expr '(f x 2)
   :arg 'x})

(defn T
  [schema]
  (ab/schema->type schema))

(def ^:private ui-internal-markers
  [":skeptic.analysis.types/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn- assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(deftest plain-key-and-superfluous-cast-key-test
  (is (= :a (sut/plain-key :a)))
  (is (= :a (sut/plain-key (s/optional-key :a))))
  (let [k (s/optional-key :a)
        m (sut/superfluous-cast-key k)]
    (is (= k (:orig-key m)))
    (is (= :a (:cleaned-key m)))))

(deftest render-visible-path-and-tokens-test
  (is (= "[:a :b]" (sut/render-visible-path [{:kind :map-key :key :a}
                                             {:kind :map-key :key :b}])))
  (is (nil? (sut/render-visible-path [{:kind :source-union-branch :index 0}]))))

(deftest nested-map-cast-report-includes-field-path-test
  (let [report (inc/cast-report sample-ctx
                                (T {:user {:name s/Str}})
                                (T {:user {:name s/Keyword}}))
        leaf (first (:cast-diagnostics report))
        error (first (:errors report))]
    (is (not (:ok? report)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[:user :name]"))))

(deftest map-cast-report-key-errors-test
  (let [missing (inc/cast-report {:expr '{:a 1}
                                 :arg '{:a 1}}
                                (T {:a s/Int
                                    :b s/Int})
                                (T {:a s/Int}))
        unexpected (inc/cast-report {:expr '{:a 1 :c 2}
                                    :arg '{:a 1 :c 2}}
                                   (T {:a s/Int})
                                   (T {:a s/Int
                                       :c s/Int}))]
    (is (not (:ok? missing)))
    (is (some #(= :missing-key (:reason %)) (:cast-diagnostics missing)))
    (is (some #(str/includes? % "[:b] is missing") (:errors missing)))

    (is (not (:ok? unexpected)))
    (is (some #(= :unexpected-key (:reason %)) (:cast-diagnostics unexpected)))
    (is (some #(str/includes? % "[:c] is not allowed by the expected type") (:errors unexpected)))
    (run! assert-no-ui-internals (concat (:errors missing)
                                         (:errors unexpected)))))

(deftest nested-map-key-errors-include-full-path-test
  (let [missing (inc/cast-report sample-ctx
                                 (T {:user {:name s/Str}})
                                 (T {:user {}}))
        unexpected (inc/cast-report sample-ctx
                                    (T {:user {:name s/Str}})
                                    (T {:user {:name s/Str
                                               :age s/Int}}))
        nullable (inc/cast-report sample-ctx
                                  (T {:user {:name s/Str}})
                                  (T {:user {(s/optional-key :name) s/Str}}))]
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> missing :cast-diagnostics first :path)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :age}]
           (-> unexpected :cast-diagnostics first :path)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> nullable :cast-diagnostics first :path)))
    (is (some #(str/includes? % "[:user :name]") (:errors missing)))
    (is (some #(str/includes? % "[:user :age]") (:errors unexpected)))
    (is (some #(str/includes? % "[:user :name]") (:errors nullable)))
    (run! assert-no-ui-internals (concat (:errors missing)
                                         (:errors unexpected)
                                         (:errors nullable)))))

(deftest non-literal-map-key-path-stops-at-last-code-segment-test
  (let [report (inc/cast-report sample-ctx
                                (T {:account {:state {s/Keyword s/Int}}})
                                (T {:account {:state {:name s/Str}}}))
        [leaf] (:cast-diagnostics report)
        [error] (:errors report)]
    (is (not (:ok? report)))
    (is (= [{:kind :map-key :key :account}
            {:kind :map-key :key :state}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[:account :state]"))
    (is (not (str/includes? error "[:account :state :name]")))
    (assert-no-ui-internals error)))

(deftest map-nullable-key-message-test
  (let [message (inc/cast-result->message
                 {:expr '{:a 1}
                  :arg '{:a 1}}
                 {:actual-type (ab/schema->type {(s/optional-key :a) s/Int})
                  :expected-type (ab/schema->type {:a s/Int})
                  :rule :map-nullable-key
                  :reason :nullable-key
                  :actual-key (s/optional-key :a)
                  :expected-key :a})]
    (is (str/includes? message "potentially nullable"))))

(deftest vector-cast-report-includes-index-path-test
  (let [report (inc/cast-report sample-ctx
                               (T [s/Int s/Int])
                               (T [s/Int s/Str]))
        leaf (first (:cast-diagnostics report))
        error (first (:errors report))]
    (is (not (:ok? report)))
    (is (= [{:kind :vector-index :index 1}]
           (:path leaf)))
    (is (str/includes? error "Path:"))
    (is (str/includes? error "[1]"))))

(deftest rendered-path-hides-internal-cast-branches
  (let [message (inc/cast-result->message
                 sample-ctx
                 {:actual-type (ab/schema->type s/Int)
                  :expected-type (ab/schema->type s/Str)
                  :rule :leaf-overlap
                  :reason :leaf-mismatch
                  :path [{:kind :source-union-branch :index 1}
                         {:kind :map-key :key :name}]})]
    (is (str/includes? message "[:name]"))
    (assert-no-ui-internals message)))
