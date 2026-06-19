(ns skeptic.perf.pipeline-probe-test
  "Performance probes for every public+private function in
   skeptic.checking.pipeline. One probe per function. No hypothesis,
   no filtering — the function with the worst numbers identifies
   itself."
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.perf.harness :as h]
            [skeptic.provenance :as prov])
  (:import [java.io File]))

(def tp (prov/make-provenance :inferred 'probe-sym 'skeptic.probe nil [] :clj))

;; ---- Leaf functions: pure, simple inputs --------------------------

(deftest file-extension-probe
  (when (h/enabled?)
    (let [budget-ms 500
          clj-file  (File. "src/skeptic/core.clj")
          cljc-file (File. "src/skeptic/core.cljc")
          cljs-file (File. "src/skeptic/core.cljs")
          noext-file (File. "src/skeptic/core")
          nil-file nil]
      (h/measure "file-extension .clj"
                 budget-ms #(#'pipeline/file-extension clj-file))
      (h/measure "file-extension .cljc"
                 budget-ms #(#'pipeline/file-extension cljc-file))
      (h/measure "file-extension .cljs"
                 budget-ms #(#'pipeline/file-extension cljs-file))
      (h/measure "file-extension no extension"
                 budget-ms #(#'pipeline/file-extension noext-file))
      (h/measure "file-extension nil file"
                 budget-ms #(#'pipeline/file-extension nil-file))))
  (is true))

(deftest lang-of-source-file-probe
  (when (h/enabled?)
    (let [budget-ms 500
          clj-file  (File. "src/skeptic/core.clj")
          cljc-file (File. "src/skeptic/core.cljc")
          cljs-file (File. "src/skeptic/core.cljs")
          nil-file nil]
      (h/measure "lang-of-source-file/1 .clj"
                 budget-ms #(pipeline/lang-of-source-file clj-file))
      (h/measure "lang-of-source-file/1 .cljc"
                 budget-ms #(pipeline/lang-of-source-file cljc-file))
      (h/measure "lang-of-source-file/1 .cljs"
                 budget-ms #(pipeline/lang-of-source-file cljs-file))
      (h/measure "lang-of-source-file/1 nil"
                 budget-ms #(pipeline/lang-of-source-file nil-file))
      (h/measure "lang-of-source-file/2 .cljc cljs-disable=true"
                 budget-ms #(pipeline/lang-of-source-file {:cljs-disable true} cljc-file))
      (h/measure "lang-of-source-file/2 .cljc cljs-disable=false"
                 budget-ms #(pipeline/lang-of-source-file {} cljc-file))))
  (is true))

(deftest lang-of-namespace-source-probe
  (when (h/enabled?)
    (let [budget-ms 500
          cljc-file (File. "src/skeptic/core.cljc")
          ns-sym 'demo.core
          opts-no-restriction {}
          opts-with-cljs-only {:cljs-only-namespaces #{'demo.core}}]
      (h/measure "lang-of-namespace-source no cljs-only set"
                 budget-ms #(#'pipeline/lang-of-namespace-source
                             opts-no-restriction ns-sym cljc-file))
      (h/measure "lang-of-namespace-source cljs-only match"
                 budget-ms #(#'pipeline/lang-of-namespace-source
                             opts-with-cljs-only ns-sym cljc-file))
      (h/measure "lang-of-namespace-source cljs-only miss"
                 budget-ms #(#'pipeline/lang-of-namespace-source
                             opts-with-cljs-only 'demo.other cljc-file))))
  (is true))

(deftest inert-conditional-type?-probe
  (when (h/enabled?)
    (let [budget-ms 500
          dyn (at/Dyn tp)
          bottom (at/BottomType tp)
          ground (at/->GroundT tp :int 'Int)
          maybe-t (at/->MaybeT tp ground)
          map-t (at/->MapT tp {(at/->ValueT tp ground :k) ground})
          union-t (at/->UnionT tp #{ground (at/->GroundT tp :str 'Str)})]
      (h/measure "inert-conditional-type? Dyn"
                 budget-ms #(#'pipeline/inert-conditional-type? dyn))
      (h/measure "inert-conditional-type? Bottom"
                 budget-ms #(#'pipeline/inert-conditional-type? bottom))
      (h/measure "inert-conditional-type? Ground"
                 budget-ms #(#'pipeline/inert-conditional-type? ground))
      (h/measure "inert-conditional-type? Maybe"
                 budget-ms #(#'pipeline/inert-conditional-type? maybe-t))
      (h/measure "inert-conditional-type? Map"
                 budget-ms #(#'pipeline/inert-conditional-type? map-t))
      (h/measure "inert-conditional-type? Union"
                 budget-ms #(#'pipeline/inert-conditional-type? union-t))))
  (is true))

(deftest unary-recurse-field-probe
  (when (h/enabled?)
    (let [budget-ms 500
          ground (at/->GroundT tp :int 'Int)
          maybe-t (at/->MaybeT tp ground)
          map-t (at/->MapT tp {})
          union-t (at/->UnionT tp #{ground})]
      (h/measure "unary-recurse-field Maybe"
                 budget-ms #(#'pipeline/unary-recurse-field maybe-t))
      (h/measure "unary-recurse-field Map (no match)"
                 budget-ms #(#'pipeline/unary-recurse-field map-t))
      (h/measure "unary-recurse-field Union (no match)"
                 budget-ms #(#'pipeline/unary-recurse-field union-t))
      (h/measure "unary-recurse-field Ground (no match)"
                 budget-ms #(#'pipeline/unary-recurse-field ground))))
  (is true))

(deftest n-ary-recurse-field-probe
  (when (h/enabled?)
    (let [budget-ms 500
          ground (at/->GroundT tp :int 'Int)
          maybe-t (at/->MaybeT tp ground)
          union-t (at/->UnionT tp #{ground (at/->GroundT tp :str 'Str)})
          inter-t (at/->IntersectionT tp #{ground})]
      (h/measure "n-ary-recurse-field Union"
                 budget-ms #(#'pipeline/n-ary-recurse-field union-t))
      (h/measure "n-ary-recurse-field Intersection"
                 budget-ms #(#'pipeline/n-ary-recurse-field inter-t))
      (h/measure "n-ary-recurse-field Maybe (no match)"
                 budget-ms #(#'pipeline/n-ary-recurse-field maybe-t))
      (h/measure "n-ary-recurse-field Ground (no match)"
                 budget-ms #(#'pipeline/n-ary-recurse-field ground))))
  (is true))
