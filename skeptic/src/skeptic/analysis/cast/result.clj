(ns skeptic.analysis.cast.result
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]))

(def ^:private structural-rules
  #{:target-union :source-union :target-intersection :source-intersection
    :maybe-both :maybe-target :generalize :instantiate
    :function :function-method :map :vector :seq :set})

(def ^:private source-aggregate-rules
  #{:source-union :source-intersection})

(s/defn ok? :- s/Bool
  [cast-result :- csch/CastResult]
  (boolean (:ok? cast-result)))

(s/defn root-summary :- {s/Keyword s/Any}
  [cast-result :- csch/CastResult]
  {:ok?            (:ok? cast-result)
   :rule           (:rule cast-result)
   :blame-side     (:blame-side cast-result)
   :blame-polarity (:blame-polarity cast-result)
   :actual-type    (:source-type cast-result)
   :expected-type  (:target-type cast-result)})

(s/defn ^:private project-leaf :- {s/Keyword s/Any}
  [leaf :- csch/CastResult path :- s/Any]
  (cond-> {:rule           (:rule leaf)
           :reason         (:reason leaf)
           :path           path
           :actual-type    (:source-type leaf)
           :expected-type  (:target-type leaf)
           :blame-side     (:blame-side leaf)
           :blame-polarity (:blame-polarity leaf)}
    (contains? leaf :actual-key)        (assoc :actual-key (:actual-key leaf))
    (contains? leaf :expected-key)      (assoc :expected-key (:expected-key leaf))
    (contains? leaf :source-key-domain) (assoc :source-key-domain (:source-key-domain leaf))))

(s/defn leaf-diagnostics :- [{s/Keyword s/Any}]
  ([cast-result :- (s/maybe csch/CastResult)]
   (leaf-diagnostics cast-result []))
  ([cast-result :- (s/maybe csch/CastResult) parent-path :- s/Any]
   (let [path (into (vec parent-path) (or (:path cast-result) []))]
     (cond
       (or (nil? cast-result) (:ok? cast-result))
       []

       (and (seq (:children cast-result))
            (contains? structural-rules (:rule cast-result)))
       (if (contains? source-aggregate-rules (:rule cast-result))
         [(project-leaf cast-result path)]
         (->> (:children cast-result)
              (mapcat #(leaf-diagnostics % path))
              vec))

       :else
       [(project-leaf cast-result path)]))))

(s/defn primary-diagnostic :- {s/Keyword s/Any}
  [cast-result :- csch/CastResult]
  (or (first (leaf-diagnostics cast-result))
      (project-leaf cast-result (or (:path cast-result) []))))
