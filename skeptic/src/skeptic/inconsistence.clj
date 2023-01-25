(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as analysis-schema]
            [skeptic.colours :as colours]
            [clojure.set :as set]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any})

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn any-schema?
  [s]
  (= s s/Any))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is nullable:\n\n\t%s\n\nbut expected is not:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(defn mismatched-maybe
  [ctx expected actual]
  (when (and (analysis-schema/maybe? actual)
             (not (analysis-schema/maybe? expected))
             (not (any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg ctx actual expected)))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is a mismatched type:\n\n\t%s\n\nbut expected is:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(defn mismatched-ground-types
  [ctx expected actual]
  (when (and (contains? ground-types expected)
             (contains? ground-types actual)
             (not= expected actual))
    (mismatched-ground-type-msg ctx actual expected)))

(def base-mismatch-rules
  [mismatched-maybe
   mismatched-ground-types])

(s/defn apply-base-rules :- [s/Str]
  [ctx expected actual]
  (keep
   (fn [f] (f ctx expected actual))
   base-mismatch-rules))

(s/defn check-keys :- (s/maybe s/Str)
  [ctx expected actual]
  (let [req-expected (->> expected keys (filter s/required-key?) (into #{}))
        expected-keys (->> expected keys (into #{}))
        actual-keys (->> actual keys (into #{}))
        missing (set/difference req-expected actual-keys)
        superfluous (set/difference actual-keys expected-keys)]
    (cond
      (seq missing) (str missing)
      (seq superfluous) (str superfluous))))

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))

(s/defn check-for-key-schema :- [s/Str]
  [ctx expected actual k]
  (let [expected-schema (get expected k)
        actual-schema (get actual k)]
    (when (and expected-schema actual-schema)
      (apply-base-rules ctx expected-schema actual-schema))))

(s/defn apply-mismatches-by-key :- [s/Str]
  [ctx expected actual]
  (let [expected-keys (->> expected keys (map plain-key))]
    (->> expected-keys
         (mapcat (partial check-for-key-schema ctx expected actual))
         (filter nil?))))

(s/defn mismatched-maps :- [s/Str]
  [ctx mexpected actual]
  (let [expected (analysis-schema/de-maybe mexpected)]
    (when (and (map? expected) (map? actual))
      (or (keep identity [(check-keys ctx expected actual)])
          (apply-mismatches-by-key ctx expected actual)))))

(s/defn inconsistent? :- [s/Str]
  [expr arg expected actual]
  (let [ctx {:expr expr :arg arg}]
    (filter seq
           (concat (apply-base-rules ctx expected actual)
                   (mismatched-maps ctx expected actual)))))
