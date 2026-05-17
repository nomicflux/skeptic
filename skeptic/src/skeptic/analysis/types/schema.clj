(ns skeptic.analysis.types.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types.proto :as proto]))

(s/defschema SemanticType
  (s/protocol proto/SemanticType))

(s/defschema OrderedCollKind
  (s/enum :vector :sequential))

(s/defschema OneAtom
  {:kind (s/eq :one) :type SemanticType})

(s/defschema StarAtom
  {:kind (s/eq :star) :type SemanticType})

(s/defschema RegexAtom
  (s/conditional #(= :star (:kind %)) StarAtom
                 :else OneAtom))

(defn- valid-pattern?
  "A pattern matches the regex (:one)* (:star)? — zero or more :one atoms
  followed by at most one trailing :star atom."
  [pattern]
  (let [pv (vec pattern)
        n (count pv)]
    (if (zero? n)
      true
      (and (every? #(= :one (:kind %)) (subvec pv 0 (dec n)))
           (contains? #{:one :star} (:kind (peek pv)))))))

(s/defschema Pattern
  (s/constrained [RegexAtom] valid-pattern? 'valid-pattern?))
