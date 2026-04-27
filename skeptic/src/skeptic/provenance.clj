(ns skeptic.provenance
  (:require [schema.core :as s]
            [skeptic.provenance.schema :as provs]))

(defrecord Provenance [source qualified-sym declared-in var-meta refs])

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

(s/defn make-provenance :- provs/Provenance
  ([source        :- provs/Source
    qualified-sym :- (s/maybe s/Symbol)
    declared-in   :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
    var-meta      :- (s/maybe {s/Keyword s/Any})]
   (make-provenance source qualified-sym declared-in var-meta []))
  ([source        :- provs/Source
    qualified-sym :- (s/maybe s/Symbol)
    declared-in   :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
    var-meta      :- (s/maybe {s/Keyword s/Any})
    refs          :- [provs/Provenance]]
   (->Provenance (assert-source source) qualified-sym declared-in var-meta (vec refs))))

(s/defn with-refs :- provs/Provenance
  "Return prov with :refs replaced by the given constituent provs."
  [prov :- provs/Provenance
   refs :- [provs/Provenance]]
  (assoc prov :refs (vec refs)))

(s/defn inferred :- provs/Provenance
  [{:keys [name ns]} :- {:name (s/maybe s/Symbol)
                         :ns (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
                         s/Keyword s/Any}]
  (make-provenance :inferred name ns nil []))

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

(s/defn source :- (s/maybe provs/Source)
  [{:keys [source]} :- (s/maybe provs/Provenance)]
  source)

(s/defn of :- provs/Provenance
  [t :- {s/Keyword s/Any}]
  (or (:prov t)
      (throw (IllegalArgumentException.
              "prov/of called on value without provenance"))))

(defn- source-rank
  [p]
  (get source-rank-map (source p) 999))

(s/defn merge-provenances :- provs/Provenance
  [p1 :- (s/maybe provs/Provenance)
   p2 :- provs/Provenance]
  (let [p1-val (if (sequential? p1) (first p1) p1)]
    (if (<= (source-rank p1-val) (source-rank p2))
      p1-val
      p2)))
