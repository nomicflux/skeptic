(ns skeptic.inconsistence.mismatch
  (:require [schema.core :as s]
            [skeptic.colours :as colours]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.inconsistence.display :as disp]
            [skeptic.inconsistence.schema :as isch]))

(defn- describe-display-block
  [value opts]
  (disp/block-user-form
   (if (at/semantic-type-value? value)
     (disp/user-type-form value opts)
     (disp/user-schema-form value))))

(s/defn mismatched-nullable-msg :- s/Str
  ([ctx :- isch/ReportCtx
    actual-type :- s/Any
    expected-type :- s/Any]
   (mismatched-nullable-msg ctx actual-type expected-type {}))
  ([ctx :- isch/ReportCtx
    _actual-type :- s/Any
    _expected-type :- s/Any
    _opts :- isch/ReportOpts]
   (let [{:keys [expr arg]} ctx]
     (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
             (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))))))

(s/defn mismatched-ground-type-msg :- s/Str
  ([ctx :- isch/ReportCtx
    output-type :- s/Any
    expected-type :- s/Any]
   (mismatched-ground-type-msg ctx output-type expected-type {}))
  ([ctx :- isch/ReportCtx
    output-type :- s/Any
    expected-type :- s/Any
    opts :- isch/ReportOpts]
   (let [{:keys [expr arg]} ctx]
     (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
             (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
             (colours/yellow (describe-display-block output-type opts))
             (colours/yellow (describe-display-block expected-type opts))))))

(s/defn mismatched-output-schema-msg :- s/Str
  ([ctx :- isch/ReportCtx
    output-type :- s/Any
    expected-type :- s/Any]
   (mismatched-output-schema-msg ctx output-type expected-type {}))
  ([ctx :- isch/ReportCtx
    output-type :- s/Any
    expected-type :- s/Any
    opts :- isch/ReportOpts]
   (let [{:keys [expr arg]} ctx]
     (format "%s\n\tin\n\n%s\nhas inferred output type:\n\n%s\n\nbut the declared return type expects:\n\n%s"
             (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
             (colours/yellow (describe-display-block output-type opts))
             (colours/yellow (describe-display-block expected-type opts))))))

(s/defn mismatched-schema-msg :- s/Str
  ([ctx :- isch/ReportCtx
    actual-type :- s/Any
    expected-type :- s/Any]
   (mismatched-schema-msg ctx actual-type expected-type {}))
  ([ctx :- isch/ReportCtx
    actual-type :- s/Any
    expected-type :- s/Any
    opts :- isch/ReportOpts]
   (let [{:keys [expr arg]} ctx]
     (format "%s\n\tin\n\n%s\nhas inferred type incompatible with the expected type:\n\n%s\n\nThe expected type corresponds to:\n\n%s"
             (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
             (colours/yellow (describe-display-block actual-type opts))
             (colours/yellow (describe-display-block expected-type opts))))))

(s/defn unknown-output-type? :- s/Bool
  [type :- ats/SemanticType]
  (at/dyn-type? type))
