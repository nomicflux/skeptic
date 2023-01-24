(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as analysis-schema]
            [skeptic.colours :as colours]))

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn any-schema?
  [s]
  (= s s/Any))

(defn mismatched-nullable-msg
  [expr arg output-schema expected-schema]
  (format "%s in %s is nullable:\n\n\t%s\n\nbut expected is not:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(defn mismatched-maybe
  [expr arg expected actual]
  (when (and (analysis-schema/maybe? actual)
             (not (analysis-schema/maybe? expected))
             (not (any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg expr arg actual expected)))

(defn mismatched-ground-type-msg
  [expr arg output-schema expected-schema]
  (format "%s in %s is a mismatched type:\n\n\t%s\n\nbut expected is:\n\n\t%s"
          (pr-str arg) (pr-str expr)
          (colours/yellow (pr-str (s/explain output-schema)))
          (colours/yellow (pr-str (s/explain expected-schema)))))

(defn mismatched-ground-types
  [expr arg expected actual]
  (when (and (contains? ground-types expected)
             (contains? ground-types actual)
             (not= expected actual))
    (mismatched-ground-type-msg expr arg actual expected)))

(defn inconsistent?
  [expr arg expected actual]
  (reduce
   (fn [_ f]
     (if-let [reason (f expr arg expected actual)]
       (reduced reason)
       nil))
   nil
   [mismatched-maybe
    mismatched-ground-types]))
