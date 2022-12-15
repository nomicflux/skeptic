(ns skeptic.inconsistence
  (:import [schema.core Maybe])
  (:require [schema.core :as s]))

(def ground-types #{s/Int s/Str s/Bool s/Keyword s/Symbol})

(defn maybe?
  [s]
  (instance? Maybe s))

(defn any-schema?
  [s]
  (= s s/Any))

(defn mismatched-maybe
  [expected {:keys [output expr]}]
  (when (and (maybe? output)
             (not (maybe? expected))
             (not (any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (format "Actual is nullable (%s as %s) but expected is not (%s)"
            (pr-str expr) (s/explain output)
            (s/explain expected))))

(defn mismatched-ground-types
  [expected {:keys [output expr]}]
  (when (and (contains? ground-types expected)
             (contains? ground-types output)
             (not= expected output))
    (format "Mismatched types: %s is %s, but expected is %s"
            (pr-str expr) (s/explain output)
            (s/explain expected))))

(defn inconsistent?
  [expected actual]
  (reduce
   (fn [_ f]
     (if-let [reason (f expected actual)]
       (reduced reason)
       nil))
   nil
   [mismatched-maybe
    mismatched-ground-types]))
