(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [clojure.set :as set]
            [plumbing.core :as pl]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is nullable:\n\n\t%s\n\nbut expected is not:\n\n\t%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (ppr-str (s/explain output-schema)))
          (colours/yellow (ppr-str (s/explain expected-schema)))))

(s/defn mismatched-maybe :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (as/maybe? actual)
             (not (as/maybe? expected))
             (not (as/any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg ctx actual expected)))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s in %s is a mismatched type:\n\n\t%s\n\nbut expected is:\n\n\t%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (ppr-str (s/explain output-schema)))
          (colours/yellow (ppr-str (s/explain expected-schema)))))

(s/defn mismatched-ground-types :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (contains? ground-types expected)
             (contains? ground-types actual)
             (not= expected actual))
    (mismatched-ground-type-msg ctx actual expected)))

(def base-mismatch-rules
  [mismatched-maybe
   mismatched-ground-types])

(s/defn apply-base-rules :- [s/Str]
  [ctx :- ErrorMsgCtx
   expected actual]
  (keep
   (fn [f] (f ctx expected actual))
   base-mismatch-rules))

(s/defn missing-key-message :- (s/maybe s/Str)
  [{:keys [expr arg]} :- ErrorMsgCtx
   missing :- #{s/Any}]
  (when (seq missing)
    (format "%s in %s has missing keys:\n\n\t- %s"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) missing)))))

(s/defn nullable-key-message :- s/Str
  [{:keys [expr arg]} :- ErrorMsgCtx
   {:keys [expected actual]}]
  (format "%s in %s in potentially nullable, but the schema doesn't allow that:\n\n\t%s\nexpected, but\n\n%s\nprovided\n"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (ppr-str expected))
          (colours/yellow (str/join "\n" (mapv (comp #(str "\t" %) ppr-str) actual)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s in %s has disallowed keys:\n\n\t%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (ppr-str expected-keys))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) actual-keys)))))

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))

(s/defn get-by-matching-schema
  [m k]
  (let [matches (->> m
             keys
             (filter (fn [s] (= (as/check-if-schema s k) ::as/schema-valid)))
             (select-keys m))]
    (cond
      (empty? matches) nil
      (> (count matches) 1) (throw (IllegalStateException. (format "Multiple results for key %s and m %s: %s"
                                                                   k m matches)))
      :else (-> matches vals first))))

(s/defn valued-get
  [m k]
  (cond
    (as/valued-schema? k)
    (or (get m (:value k))
        (get-by-matching-schema m (:value k))
        (get m (:schema k)))

    :else
    (or (get m k)
        (get-by-matching-schema m k))))

(declare matches-map)

(s/defn valued-compare
  [expected actual]
  (cond
    (and (as/valued-schema? expected)
         (as/valued-schema? actual))
    (throw (IllegalArgumentException. "Only actual can be a valued schema"))

    (as/valued-schema? actual)
    (or (= expected (:value actual))
        (= expected (:schema actual)))

    (or (= expected actual)
        (= (as/check-if-schema expected actual) ::as/schema-valid))
    true

    (and (map? expected) (map? actual))
    (every? (fn [[k v]] (matches-map expected k v)) actual)

    :else false))

(s/defn remove-optional-keys
  [m]
  (if (map? m)
    (->> m
         (remove (comp s/optional-key? first))
         (into {})
         (pl/map-keys remove-optional-keys)
         ;(pl/map-vals remove-optional-keys)
         )
    m))

(s/defn matches-map
  [expected actual-k actual-v]
  (let [possible-keys (filter (fn [x] (valued-compare x actual-k)) (keys expected))
        expected-vs (map #(valued-get expected %) possible-keys)]
    (if (empty? expected-vs)
      false
      (seq (filter #(valued-compare % actual-v) expected-vs)))))

(s/defn in-set?
  [expected actual-k]
  (let [possible-keys (filter (fn [x] (valued-compare x actual-k)) expected)]
    (seq possible-keys)))

(s/defn valued-disj
  [m k]
  (if (as/valued-schema? k)
    (-> m
        (disj (:value k))
        (disj (:schema k)))
    (disj m k)))

(s/defn check-keys :- [s/Str]
  [ctx expected actual]
  (let [req-expected (->> expected keys (filter s/required-key?) (map plain-key) set)
        opt-expected (->> expected keys (filter s/optional-key?) (map plain-key) set)
        expected-keys (->> expected remove-optional-keys keys set)
        ctx (assoc ctx :expected-keys expected-keys)
        {:keys [missing illegally-nullable superfluous]}
        (reduce (fn [{:keys [missing illegally-nullable superfluous]} actual-key]
                  {:missing (valued-disj missing (plain-key actual-key))
                   :illegally-nullable (cond-> illegally-nullable
                                         (contains? opt-expected actual-key)
                                         (conj {:expected expected-keys
                                                :actual actual-key}))
                   :superfluous (let [match (in-set? expected-keys (plain-key actual-key))]
                                  (cond-> superfluous
                                    (nil? match)
                                    (conj actual-key)))})
                {:missing req-expected
                 :illegally-nullable #{}
                 :superfluous #{}}
                (keys actual))]

    (remove nil? (concat
                  [(missing-key-message ctx missing)
                   (superfluous-key-message ctx superfluous)]
                  (map (partial nullable-key-message ctx) illegally-nullable)))))

(s/defn check-for-key-schema :- [s/Str]
  [ctx expected actual ks]
  (let [expected-schemas (->> (as/schema-values ks)
                              (keep (partial get expected)))
        actual-schemas (->> ks (get actual) as/schema-values)]
    (vec (when (and (seq expected-schemas) (seq actual-schemas))
           (let [matches (for [es expected-schemas
                               as actual-schemas]
                           (apply-base-rules ctx es as))]
             (apply concat (filter seq matches)))))))

(s/defn apply-mismatches-by-key :- [s/Str]
  [ctx expected actual]
  (mapcat (partial check-for-key-schema ctx expected actual)
          (keys actual)))

(s/defn map-schema?
  [s]
  (instance? clojure.lang.PersistentArrayMap s))

(s/defn mismatched-maps :- [s/Str]
  [ctx mexpected mactual]
  (let [expected (->> mexpected as/de-maybe)
        ;; TODO: Under what circumstances would actual be a maybe? If in a collection of combined nils and maps?
        actual (->> mactual as/de-maybe)]
    (when (and (map-schema? expected) (map-schema? actual))
      (keep identity (concat (apply-mismatches-by-key ctx expected actual)
                             (check-keys ctx expected actual))))))

(s/defn inconsistent? :- [s/Str]
  [expr arg expected actual]
  (let [ctx {:expr expr :arg arg}]
    (filter seq
           (concat (apply-base-rules ctx expected actual)
                   (mismatched-maps ctx expected actual)))))
