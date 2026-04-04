(ns skeptic.inconsistence.mismatch
  (:require [skeptic.colours :as colours]
            [skeptic.inconsistence.display :as disp]
            [skeptic.analysis.type-ops :as ato]))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-type _expected-type]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-type expected-type]
  (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (disp/describe-type-block output-type))
          (colours/yellow (disp/describe-type-block expected-type))))

(defn mismatched-output-schema-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s\n\tin\n\n%s\nhas output schema:\n\n%s\n\nbut declared return schema is:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (disp/describe-type-block output-schema))
          (colours/yellow (disp/describe-type-block expected-schema))))

(defn mismatched-schema-msg
  [{:keys [expr arg]} actual-type expected-type]
  (format "%s\n\tin\n\n%s\nhas incompatible schema:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (disp/describe-type-block actual-type))
          (colours/yellow (disp/describe-type-block expected-type))))

(defn unknown-output-type?
  [type]
  (ato/unknown-type? type))
