(ns skeptic.inconsistence.mismatch
  (:require [skeptic.colours :as colours]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.display :as disp]
            [skeptic.analysis.type-ops :as ato]))

(defn- describe-display-block
  [value]
  (disp/block-user-form
   (if (at/semantic-type-value? value)
     (disp/user-type-form value)
     (disp/user-schema-form value))))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-type _expected-type]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-type expected-type]
  (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (describe-display-block output-type))
          (colours/yellow (describe-display-block expected-type))))

(defn mismatched-output-schema-msg
  [{:keys [expr arg]} output-type expected-type]
  (format "%s\n\tin\n\n%s\nhas inferred output type:\n\n%s\n\nbut the declared return Plumatic Schema expects:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (describe-display-block output-type))
          (colours/yellow (describe-display-block expected-type))))

(defn mismatched-schema-msg
  [{:keys [expr arg]} actual-type expected-type]
  (format "%s\n\tin\n\n%s\nhas inferred type incompatible with the expected Plumatic Schema:\n\n%s\n\nThe expected Plumatic Schema corresponds to:\n\n%s"
          (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
          (colours/yellow (describe-display-block actual-type))
          (colours/yellow (describe-display-block expected-type))))

(defn unknown-output-type?
  [type]
  (ato/unknown-type? type))
