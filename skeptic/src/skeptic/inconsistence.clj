(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

(defn describe-schema
  [x]
  (ppr-str
   (if (or (as/schema? x)
           (class? x))
     (as/schema-explain x)
     x)))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-schema _expected-schema]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-schema output-schema))
          (colours/yellow (describe-schema expected-schema))))

(defn mismatched-output-schema-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s\n\tin\n\n%s\nhas output schema:\n\n%s\n\nbut declared return schema is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-schema output-schema))
          (colours/yellow (describe-schema expected-schema))))

(defn mismatched-schema-msg
  [{:keys [expr arg]} actual-schema expected-schema]
  (format "%s\n\tin\n\n%s\nhas incompatible schema:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-schema actual-schema))
          (colours/yellow (describe-schema expected-schema))))

(defn output-compatible-schemas
  [expected actual]
  [(as/canonicalize-output-schema expected)
   (as/canonicalize-output-schema actual)])

(defn unknown-output-schema?
  [schema]
  (as/unknown-schema? schema))

(declare output-cast-report
         plain-key
         missing-key-message
         nullable-key-message
         superfluous-key-message)

(defn cast-schema
  [type]
  (-> type as/type->schema as/canonicalize-schema))

(defn cast-leaf-results
  [cast-result]
  (cond
    (or (nil? cast-result) (:ok? cast-result))
    []

    (and (seq (:children cast-result))
         (contains? #{:target-union
                      :source-union
                      :target-intersection
                      :source-intersection
                      :maybe-both
                      :maybe-target
                      :function
                      :function-method
                      :map
                      :vector
                      :seq
                      :set}
                    (:rule cast-result)))
    (->> (:children cast-result)
         (mapcat cast-leaf-results)
         vec)

    :else
    [cast-result]))

(defn primary-cast-failure
  [cast-result]
  (or (first (cast-leaf-results cast-result))
      cast-result))

(defn superfluous-cast-key
  [actual-key]
  (let [plain-key (plain-key actual-key)]
    {:orig-key actual-key
     :cleaned-key (as/flatten-valued-schema-map plain-key)}))

(defn cast-result->message
  [ctx cast-result]
  (let [source-schema (cast-schema (:source-type cast-result))
        target-schema (cast-schema (:target-type cast-result))]
    (case (:reason cast-result)
      :nullable-source
      (mismatched-nullable-msg ctx source-schema target-schema)

      :missing-key
      (missing-key-message ctx #{(:expected-key cast-result)})

      :nullable-key
      (nullable-key-message (assoc ctx
                                   :expected-keys (keys target-schema))
                            #{(:actual-key cast-result)})

      :unexpected-key
      (superfluous-key-message (assoc ctx
                                      :expected-keys (keys target-schema))
                               #{(superfluous-cast-key (:actual-key cast-result))})

      (if (and (contains? ground-types source-schema)
               (contains? ground-types target-schema)
               (not= source-schema target-schema))
        (mismatched-ground-type-msg ctx source-schema target-schema)
        (mismatched-schema-msg ctx source-schema target-schema)))))

(defn cast-report-metadata
  [cast-result]
  (let [primary (primary-cast-failure cast-result)]
    {:cast-result cast-result
     :cast-results (vec (cast-leaf-results cast-result))
     :blame-side (:blame-side primary)
     :blame-polarity (:blame-polarity primary)
     :rule (:rule primary)
     :expected-type (:target-type primary)
     :actual-type (:source-type primary)}))

(defn cast-report
  [ctx expected actual]
  (let [expected (as/canonicalize-schema expected)
        actual (as/canonicalize-schema actual)
        cast-result (as/check-cast (as/schema->type actual)
                                   (as/schema->type expected))]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (let [errors (->> (cast-leaf-results cast-result)
                        (map #(cast-result->message ctx %))
                        distinct
                        vec)]
        (merge {:ok? false
                :errors errors}
               (cast-report-metadata cast-result))))))

(defn output-cast-report
  [ctx expected actual]
  (let [[expected actual] (output-compatible-schemas expected actual)
        cast-result (as/check-cast (as/schema->type actual)
                                   (as/schema->type expected))]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (merge {:ok? false
              :errors [(mismatched-output-schema-msg ctx actual expected)]}
             (cast-report-metadata cast-result)))))

(s/defn missing-key-message :- (s/maybe s/Str)
  [{:keys [expr arg]} :- ErrorMsgCtx
   missing :- #{s/Any}]
  (when (seq missing)
    (format "%s\n\tin\n\n%s\nhas missing keys:\n\n\t- %s"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) missing)))))

(s/defn nullable-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   nullables :- #{s/Any}]
  (when (seq nullables)
    (format "%s\n\tin\n\n%s\nin potentially nullable, but the schema doesn't allow that:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (ppr-str expected-keys))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) nullables)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s\n\tin\n\n%s\nhas disallowed keys:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (ppr-str expected-keys))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) actual-keys)))))

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))
