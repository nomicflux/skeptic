(ns skeptic.static-call-examples
  (:require [schema.core :as s]))

(s/defschema UserDesc
  {:name s/Str
   :nickname (s/maybe s/Str)})

(s/defschema OptionalNicknameUser
  {:name s/Str
   (s/optional-key :nickname) s/Str})

(s/defschema MaybeCount
  {(s/optional-key :count) s/Int})

(s/defschema LeftFields
  {:a s/Int})

(s/defschema RightFields
  {:b s/Int})

(s/defschema BothFields
  {:a s/Int
   :b s/Int})

(s/defschema ValueDesc
  {:value s/Int})

(s/defn required-name :- s/Str
  [user :- UserDesc]
  (get user :name))

(s/defn optional-nickname :- (s/maybe s/Str)
  [user :- UserDesc]
  (get user :nickname))

(s/defn nickname-with-default :- s/Str
  [user :- OptionalNicknameUser]
  (get user :nickname "anon"))

(s/defn bad-count-default :- s/Int
  [counts :- MaybeCount]
  (get counts :count "zero"))

(s/defn rebuilt-user :- UserDesc
  [user :- UserDesc]
  {:name (get user :name)
   :nickname (get user :nickname)})

(s/defn bad-rebuilt-user :- UserDesc
  [user :- UserDesc]
  {:name :bad
   :nickname (get user :nickname)})

(s/defn merge-fields :- BothFields
  [left :- LeftFields
   right :- RightFields]
  (merge left right))

(s/defn nested-multi-step-takes-str :- s/Str
  [x :- s/Str]
  x)

(s/defn nested-multi-step-takes-int :- s/Int
  [x :- s/Int]
  x)

(s/defn nested-multi-step-f :- ValueDesc
  []
  {:value 7})

(s/defn nested-multi-step-g :- ValueDesc
  []
  (nested-multi-step-f))

(defn nested-multi-step-failure
  []
  (nested-multi-step-takes-str (get (nested-multi-step-g) :value)))

(defn nested-multi-step-success
  []
  (nested-multi-step-takes-int (get (nested-multi-step-g) :value)))
