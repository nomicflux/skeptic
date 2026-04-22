(ns skeptic.inconsistence.mismatch
  (:require [skeptic.colours :as colours]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.display :as disp]
            [skeptic.analysis.type-ops :as ato]))

(defn- describe-display-block
  [value opts]
  (disp/block-user-form
   (if (at/semantic-type-value? value)
     (disp/user-type-form value opts)
     (disp/user-schema-form value))))

(defn mismatched-nullable-msg
  ([ctx actual-type expected-type]
   (mismatched-nullable-msg ctx actual-type expected-type {}))
  ([{:keys [expr arg]} _actual-type _expected-type _opts]
   (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
           (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr)))))

(defn mismatched-ground-type-msg
  ([ctx output-type expected-type]
   (mismatched-ground-type-msg ctx output-type expected-type {}))
  ([{:keys [expr arg]} output-type expected-type opts]
   (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
           (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
           (colours/yellow (describe-display-block output-type opts))
           (colours/yellow (describe-display-block expected-type opts)))))

(defn mismatched-output-schema-msg
  ([ctx output-type expected-type]
   (mismatched-output-schema-msg ctx output-type expected-type {}))
  ([{:keys [expr arg]} output-type expected-type opts]
   (format "%s\n\tin\n\n%s\nhas inferred output type:\n\n%s\n\nbut the declared return type expects:\n\n%s"
           (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
           (colours/yellow (describe-display-block output-type opts))
           (colours/yellow (describe-display-block expected-type opts)))))

(defn mismatched-schema-msg
  ([ctx actual-type expected-type]
   (mismatched-schema-msg ctx actual-type expected-type {}))
  ([{:keys [expr arg]} actual-type expected-type opts]
   (format "%s\n\tin\n\n%s\nhas inferred type incompatible with the expected type:\n\n%s\n\nThe expected type corresponds to:\n\n%s"
           (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
           (colours/yellow (describe-display-block actual-type opts))
           (colours/yellow (describe-display-block expected-type opts)))))

(defn unknown-output-type?
  [type]
  (ato/unknown? type))
