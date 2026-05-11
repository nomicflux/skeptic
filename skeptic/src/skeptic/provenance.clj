(ns skeptic.provenance
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance.schema :as provs]))

(defrecord Provenance [source qualified-sym declared-in var-meta refs lang])

(def ^:private source-rank-map
  {:type-override 0
   :malli 1
   :schema 2
   :native 3
   :inferred 4})

(defn- valid-source?
  [source]
  (contains? source-rank-map source))

(defn- assert-source
  [source]
  (when-not (valid-source? source)
    (throw (IllegalArgumentException.
            (format "Invalid provenance source: %s" (pr-str source)))))
  source)

(defn- valid-lang?
  [lang]
  (or (= :clj lang)
      (= :cljs lang)
      (= #{:clj :cljs} lang)))

(defn- assert-lang
  [lang]
  (when-not (valid-lang? lang)
    (throw (IllegalArgumentException.
            (format "Invalid provenance lang: %s" (pr-str lang)))))
  lang)

(s/defn make-provenance :- provs/Provenance
  [source        :- provs/Source
   qualified-sym :- (s/maybe s/Symbol)
   declared-in   :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   var-meta      :- (s/maybe {s/Keyword s/Any})
   refs          :- [provs/Provenance]
   lang          :- provs/Lang]
  (->Provenance (assert-source source) qualified-sym declared-in var-meta (vec refs) (assert-lang lang)))

(s/defn with-refs :- provs/Provenance
  "Return prov with :refs replaced by the given constituent provs."
  [prov :- provs/Provenance
   refs :- [provs/Provenance]]
  (assoc prov :refs (vec refs)))

(s/defn inferred :- provs/Provenance
  [{:keys [name ns]} :- {:name (s/maybe s/Symbol)
                         :ns   (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))}
   lang :- provs/Lang]
  (make-provenance :inferred name ns nil [] lang))

(def ^:private ctx-key :skeptic.provenance/ctx-provenance)

(s/defn with-ctx :- provs/Provenance
  "Read the current provenance from an analyzer ctx."
  [ctx :- {s/Keyword s/Any}]
  (or (get ctx ctx-key)
      (throw (IllegalArgumentException.
              (format "prov/with-ctx called with ctx missing provenance: %s" ctx)))))

(s/defn set-ctx :- {s/Keyword s/Any}
  "Return ctx with the given provenance installed."
  [ctx  :- {s/Keyword s/Any}
   prov :- provs/Provenance]
  (assoc ctx ctx-key prov))

(s/defn provenance? :- s/Bool
  [x :- s/Any]
  (instance? Provenance x))

(s/defn source :- provs/Source
  [{:keys [source]} :- provs/Provenance]
  source)

(s/defn lang :- provs/Lang
  [{:keys [lang]} :- provs/Provenance]
  lang)

(s/defn of :- provs/Provenance
  [t :- ats/SemanticType]
  (when-not (at/semantic-type-value? t)
    (throw (IllegalArgumentException.
            (format "prov/of called on non-Type value: %s" (pr-str t)))))
  (or (:prov t)
      (throw (IllegalArgumentException.
              "prov/of called on Type with nil provenance"))))

(defn- source-rank
  [p]
  (get source-rank-map (source p) 999))

(defn- as-lang-set
  [l]
  (cond
    (set? l) l
    :else #{l}))

(defn- combine-langs
  [l1 l2]
  (let [s (into (as-lang-set l1) (as-lang-set l2))]
    (if (= 1 (count s)) (first s) s)))

(s/defn merge-provenances :- provs/Provenance
  [p1 :- provs/Provenance
   p2 :- provs/Provenance]
  (let [winner (if (<= (source-rank p1) (source-rank p2)) p1 p2)]
    (assoc winner :lang (combine-langs (:lang p1) (:lang p2)))))
