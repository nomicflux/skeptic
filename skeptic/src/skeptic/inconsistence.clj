(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [clojure.set :as set]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any})

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn mismatched-nullable-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is nullable:\n\n\t%s\n\nbut expected is not:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(s/defn mismatched-maybe :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (as/maybe? actual)
             (not (as/maybe? expected))
             (not (as/any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg ctx actual expected)))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is a mismatched type:\n\n\t%s\n\nbut expected is:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(s/defn mismatched-ground-types :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (contains? ground-types expected)
             (contains? ground-types actual)
             (not= expected actual))
    (mismatched-ground-type-msg ctx actual expected)))

(def base-mismatch-rules
  [mismatched-maybe
   mismatched-ground-types])

(s/defn apply-base-rules :- [s/Str]
  [ctx :- ErrorMsgCtx
   expected actual]
  (keep
   (fn [f] (f ctx expected actual))
   base-mismatch-rules))

(s/defn missing-key-message :- s/Str
  [{:keys [expr arg]} :- ErrorMsgCtx
   missing]
  (format "%s in %s has missing keys:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str missing))))

(s/defn superfluous-key-message :- s/Str
  [{:keys [expr arg]} :- ErrorMsgCtx
   superfluous]
  (format "%s in %s has disallowed keys:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str superfluous))))

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))

(s/defn check-keys :- (s/maybe s/Str)
  [ctx expected actual]
  (let [req-expected (->> expected keys (filter s/required-key?) set)
        expected-keys (->> expected keys (map plain-key) set)
        {:keys [missing superfluous]} (reduce (fn [{:keys [missing superfluous]} actual-key]
                                                (let [actual-schemas (->> actual-key
                                                                          as/join->set
                                                                          (map as/de-eq)
                                                                          set)]
                                                  {:missing (set/difference missing actual-schemas)
                                                   :superfluous (cond-> superfluous
                                                                  (and (empty? (set/intersection (set (map plain-key actual-schemas)) expected-keys))
                                                                       (empty? (keep #(= % :as/schema-valid) (for [ek expected-keys
                                                                                                                   ak (map plain-key actual-schemas)]
                                                                                            (as/check-if-schema ek ak)))))
                                                                  (conj {:expected expected-keys
                                                                         :actual (set (map plain-key actual-schemas))}))}))
                                              {:missing req-expected
                                               :superfluous #{}}
                                              (keys actual))]

    (cond
      (seq missing) (missing-key-message ctx missing)
      (seq superfluous) (superfluous-key-message ctx superfluous))))

(s/defn check-for-key-schema :- [s/Str]
  [ctx expected actual ks]
  (let [expected-schemas (->> (as/join->set ks)
                              (map as/de-eq)
                              (keep (partial get expected)))
        actual-schemas (->> ks (get actual) as/join->set (map as/de-eq))]
    (vec (when (and (seq expected-schemas) (seq actual-schemas))
           (let [matches (for [es expected-schemas
                               as actual-schemas]
                           (apply-base-rules ctx es as))
                 no-match? (->> matches (filter seq) empty?)]
             (apply concat (filter seq matches)))))))

(s/defn apply-mismatches-by-key :- [s/Str]
  [ctx expected actual]
  (mapcat (partial check-for-key-schema ctx expected actual)
          (keys actual)))

(s/defn map-schema?
  [s]
  (instance? clojure.lang.PersistentArrayMap s))

(s/defn mismatched-maps :- [s/Str]
  [ctx mexpected mactual]
  (let [expected (as/de-maybe mexpected)
        ;; TODO: Under what circumstances would actual be a maybe? If in a collection of combined nils and maps?
        actual (as/de-maybe mactual)]
    (when (and (map-schema? expected) (map-schema? actual))
      (keep identity (conj (apply-mismatches-by-key ctx expected actual)
                           (check-keys ctx expected actual))))))

(s/defn inconsistent? :- [s/Str]
  [expr arg expected actual]
  (let [ctx {:expr expr :arg arg}]
    (filter seq
           (concat (apply-base-rules ctx expected actual)
                   (mismatched-maps ctx expected actual)))))
