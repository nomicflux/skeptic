(ns skeptic.inconsistence
  (:import [schema.core Maybe])
  (:require [schema.core :as s]))

(def ground-types #{s/Int s/Str s/Bool s/Keyword s/Symbol})

(defn maybe?
  [s]
  (instance? Maybe s))

(defn mismatched-maybe
  [expected actual]
  (when (and (maybe? actual) (not (maybe? expected)))
    (format "Actual is nullable (%s) but expected is not (%s)" (s/explain actual) (s/explain expected))))

(defn inconsistent?
  [expected actual]
  (reduce
   (fn [_ f]
     (if-let [reason (f expected actual)]
       (reduced reason)
       nil))
   nil
   [mismatched-maybe]))
