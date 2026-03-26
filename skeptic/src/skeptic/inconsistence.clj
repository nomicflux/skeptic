(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [plumbing.core :as pl]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

(defn describe-schema
  [x]
  (ppr-str
   (if (or (as/schema? x)
           (class? x))
     (s/explain x)
     x)))

;; Actual is just (maybe expected), so no need to print it out
;; TODO: thread through verbosity flag to output schema when set
(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-schema _expected-schema]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))))

(s/defn mismatched-maybe :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (as/maybe? actual)
             (not (as/maybe? expected))
             (not (as/any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg ctx actual expected)))

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

(defn canonical-compatible?
  [expected actual]
  (as/schema-compatible? (as/canonicalize-schema expected)
                         (as/canonicalize-schema actual)))

(s/defn mismatched-ground-types :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (let [expected (as/canonicalize-schema expected)
        actual (as/canonicalize-schema actual)]
    (when (and (contains? ground-types expected)
               (contains? ground-types actual)
               (not= expected actual))
      (mismatched-ground-type-msg ctx actual expected))))

(defn unknown-output-schema?
  [schema]
  (as/unknown-schema? schema))

(defn output-schema-compatible?
  [expected actual]
  (canonical-compatible? expected actual))

(declare mismatched-maps
         explain-incompatibility)

(s/defn mismatched-output-schema :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (seq (explain-incompatibility ctx expected actual))
    (mismatched-output-schema-msg ctx actual expected)))

(def base-mismatch-rules
  [mismatched-maybe
   mismatched-ground-types])

(s/defn apply-base-rules :- [s/Str]
  [ctx :- ErrorMsgCtx
   expected actual]
  (vec
   (keep
    (fn [f] (f ctx expected actual))
    base-mismatch-rules)))

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

(s/defn get-by-matching-schema
  [m k]
  (as/get-by-matching-schema m k))

(s/defn valued-get
  [m k]
  (as/valued-get m k))

(declare matches-map)

(s/defn valued-compare
  [expected actual]
  (as/valued-compatible? expected actual))

(s/defn matches-map
  [expected actual-k actual-v]
  (as/matches-map expected actual-k actual-v))

(s/defn in-set?
  [expected-m actual-k]
  (let [expected-m (as/canonicalize-schema expected-m)
        actual-k (-> actual-k as/flatten-valued-schema-map as/canonicalize-schema)
        possible-keys (filter (fn [[k _v]] (valued-compare k actual-k)) expected-m)]
    (seq possible-keys)))

(s/defn valued-disj
  [m k]
  (if (as/valued-schema? k)
    (-> m
        (disj (:value k))
        (disj (:schema k)))
    (disj m k)))

(s/defn check-keys :- [s/Str]
  [ctx expected actual]
  (let [req-expected (->> expected keys (filter s/required-key?) (map plain-key) set)
        opt-expected (->> expected keys (filter s/optional-key?) (map plain-key) set)
        ctx (assoc ctx :expected-keys (keys expected))
        {:keys [missing illegally-nullable superfluous]}
        (reduce (fn [{:keys [missing illegally-nullable superfluous]} actual-key]
                  {:missing (valued-disj missing (plain-key actual-key))
                   :illegally-nullable (cond-> illegally-nullable
                                         (and (s/optional-key? actual-key)
                                              (not (contains? opt-expected (plain-key actual-key))))
                                         (conj actual-key))
                   :superfluous (let [match (in-set? expected (plain-key actual-key))]
                                  (cond-> superfluous
                                    (nil? match)
                                    (conj {:orig-key actual-key
                                           :cleaned-key (-> actual-key plain-key as/flatten-valued-schema-map)})))})
                {:missing req-expected
                 :illegally-nullable #{}
                 :superfluous #{}}
                (keys actual))]
    (vec
     (keep identity
           [(missing-key-message ctx missing)
            (superfluous-key-message ctx superfluous)
            (nullable-key-message ctx illegally-nullable)]))))

(s/defn check-for-key-schema :- [s/Str]
  [ctx expected actual ks]
  (let [expected-schemas (->> (as/schema-values ks)
                              (keep (partial get expected)))
        actual-schemas (->> ks (get actual) as/schema-values)
        pairs (for [expected-schema expected-schemas
                    actual-schema actual-schemas]
                [expected-schema actual-schema])]
    (if (and (seq pairs)
             (not-any? (fn [[expected-schema actual-schema]]
                         (canonical-compatible? expected-schema actual-schema))
                       pairs))
      (vec
       (distinct
        (mapcat (fn [[expected-schema actual-schema]]
                  (explain-incompatibility ctx expected-schema actual-schema))
                pairs)))
      [])))

(s/defn apply-mismatches-by-key :- [s/Str]
  [ctx expected actual]
  (vec
   (mapcat (partial check-for-key-schema ctx expected actual)
           (keys actual))))

(s/defn map-schema?
  [s]
  (and (map? s)
       (not (record? s))))

(s/defn mismatched-maps :- s/Any
  [ctx mexpected mactual]
  (let [expected (->> mexpected as/de-maybe as/canonicalize-schema)
        ;; TODO: Under what circumstances would actual be a maybe? If in a collection of combined nils and maps?
        actual (->> mactual as/de-maybe as/canonicalize-schema)]
    (if (and (map-schema? expected) (map-schema? actual))
      (let [details (into (apply-mismatches-by-key ctx expected actual)
                          (check-keys ctx expected actual))]
        (cond
          (seq details) details
          (canonical-compatible? expected actual) []
          :else [(mismatched-schema-msg ctx actual expected)]))
      [])))

(s/defn explain-incompatibility :- s/Any
  [ctx :- ErrorMsgCtx
   expected actual]
  (let [expected (as/canonicalize-schema expected)
        actual (as/canonicalize-schema actual)
        messages (into (apply-base-rules ctx expected actual)
                       (mismatched-maps ctx expected actual))]
    (cond
      (seq messages) messages
      (canonical-compatible? expected actual) []
      :else [(mismatched-schema-msg ctx actual expected)])))

(s/defn inconsistent? :- [s/Str]
  [expr arg expected actual]
  (explain-incompatibility {:expr expr :arg arg} expected actual))
