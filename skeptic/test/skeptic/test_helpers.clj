(ns skeptic.test-helpers
  (:require
   [clojure.test :as ctest]
   [skeptic.analysis.bridge :as ab]
   [skeptic.analysis.bridge.render :as abr]
   [skeptic.analysis.types :as at]
   [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn T [schema] (ab/schema->type tp schema))

(defmacro is-type=
  "Assert that two semantic types are structurally equal via at/type=?.
   On failure, prints both sides rendered via abr/render-type-form so the
   mismatch is readable instead of a wall of defrecord/Provenance noise."
  [expected actual]
  `(let [e# ~expected
         a# ~actual]
     (ctest/is (at/type=? e# a#)
               (str "type=? mismatch\n"
                    "  expected: " (pr-str (abr/render-type-form e#)) "\n"
                    "  actual:   " (pr-str (abr/render-type-form a#))))))
