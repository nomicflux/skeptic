(ns skeptic.inconsistence
  (:require [schema.core :as s])
  (:import [schema.core Maybe]))

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn maybe?
  [s]
  (instance? Maybe s))

(defn any-schema?
  [s]
  (= s s/Any))

(defn mismatched-nullable-msg
  [expr output-schema expected-schema]
  (format "%s is nullable:\n\n\t%s\n\nbut expected is not:\n\n\t%s"
          (pr-str expr) (pr-str (s/explain output-schema))
          (pr-str (s/explain expected-schema))))

(defn mismatched-maybe
  [expected {:keys [output expr]}]
  (when (and (maybe? output)
             (not (maybe? expected))
             (not (any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg expr output expected)))

(defn mismatched-ground-type-msg
  [expr output-schema expected-schema]
  (format "%s is a mismatched type:\n\n\t%s\n\nbut expected is:\n\n\t%s"
          (pr-str expr) (pr-str (s/explain output-schema))
          (pr-str (s/explain expected-schema))))

(defn mismatched-ground-types
  [expected {:keys [output expr]}]
  (when (and (contains? ground-types expected)
             (contains? ground-types output)
             (not= expected output))
    (mismatched-ground-type-msg expr output expected)))

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
