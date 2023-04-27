(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [plumbing.core :as pl]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

;; TODO: Add Keyword back in once resolution of keyword in fn position is in place
(def ground-types #{s/Int s/Str s/Bool s/Symbol})

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

;; Actual is just (maybe expected), so no need to print it out
;; TODO: thread through verbosity flag to output schema when set
(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-schema _expected-schema]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))))

(s/defn mismatched-maybe :- (s/maybe s/Str)
  [ctx :- ErrorMsgCtx
   expected actual]
  (when (and (as/maybe? actual)
             (not (as/maybe? expected))
             (not (as/any-schema? expected))) ;; Technically, an Any could be a (maybe _)
    (mismatched-nullable-msg ctx actual expected)))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
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
    (format "%s\n\tin\n\n%s\nhas missing keys:\n\n\t- %s"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) missing)))))

(s/defn nullable-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   nullables :- #{s/Any}]
  (when (seq nullables)
    (format "%s\n\tin\n\n%s\nin potentially nullable, but the schema doesn't allow that:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (ppr-str expected-keys))
            (str/join "\n\t- " (mapv #(colours/yellow (ppr-str %)) nullables)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s\n\tin\n\n%s\nhas disallowed keys:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
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
    (as/valued-schema? expected)
    (throw (IllegalArgumentException. "Only actual can be a valued schema"))

    (as/valued-schema? actual)
    (let [v (:value actual)
          s (:schema actual)
          e (as/de-maybe expected)]
      (or (= e v)
          (= e s)
          (= e (s/optional-key v))
          (= e (s/optional-key s))
          (= (as/check-if-schema e v) ::as/schema-valid)))

    (or (= expected actual)
        (= expected (s/optional-key actual))
        (= (as/check-if-schema expected actual) ::as/schema-valid))
    true

    (and (map? expected) (map? actual))
    (every? (fn [[k v]] (matches-map expected k v)) actual)

    :else false))

(s/defn matches-map
  [expected actual-k actual-v]
  (let [possible-keys (filter (fn [x] (valued-compare x actual-k)) (keys expected))
        expected-vs (map #(valued-get expected %) possible-keys)]
    (if (empty? expected-vs)
      false
      (seq (filter #(valued-compare % actual-v) expected-vs)))))

(s/defn in-set?
  [expected-m actual-k]
  (let [possible-keys (filter (fn [[k _v]] (valued-compare k (as/flatten-valued-schema-map actual-k))) expected-m)]
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
        ctx (assoc ctx :expected-keys (keys expected))
        {:keys [missing illegally-nullable superfluous]}
        (reduce (fn [{:keys [missing illegally-nullable superfluous]} actual-key]
                  {:missing (valued-disj missing (plain-key actual-key))
                   :illegally-nullable (cond-> illegally-nullable
                                         (and (s/optional-key? actual-key)
                                              (not (contains? opt-expected (plain-key actual-key))))
                                         (conj actual-key))
                   :superfluous (let [match (in-set? expected (plain-key actual-key))]
                                  (cond-> superfluous
                                    (nil? match)
                                    (conj {:orig-key actual-key
                                           :cleaned-key (-> actual-key plain-key as/flatten-valued-schema-map)})))})
                {:missing req-expected
                 :illegally-nullable #{}
                 :superfluous #{}}
                (keys actual))]

    (remove nil? [(missing-key-message ctx missing)
                  (superfluous-key-message ctx superfluous)
                  (nullable-key-message ctx illegally-nullable)])))

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
    (println "Checking inconsistency:" (pr-str expr) "|" (pr-str arg) "|" (pr-str expected) "|" (pr-str actual))
    (filter seq
            (concat (apply-base-rules ctx expected actual)
                    (mismatched-maps ctx expected actual)))))
