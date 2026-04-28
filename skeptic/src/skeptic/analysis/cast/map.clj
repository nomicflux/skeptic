(ns skeptic.analysis.cast.map
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.value-check :as avc]))

(defn- map-entry-failure
  [source-type target-type rule polarity reason path-key details]
  (avc/with-map-path
    (ascs/cast-fail source-type target-type rule polarity reason [] details)
    path-key))

(defn- candidate-request
  [source-value target-entry opts path-segment]
  {:source-type source-value
   :target-type (:value target-entry)
   :opts opts
   :path-segment path-segment})

(defn- candidate-results
  [run-child requests]
  (let [results (mapv run-child requests)]
    (if-let [success (some #(when (:ok? %) %) results)]
      [success]
      results)))

(defn- exact-target-plan
  [source-descriptor target-entry opts]
  (let [exact-value (:exact-value target-entry)
        source-candidates (amo/exact-key-candidates source-descriptor exact-value)
        path-segment (when-let [path-key (avc/path-key (:key target-entry))]
                       {:kind :map-key
                        :key path-key})]
    {:source-candidates source-candidates
     :source-exact-entry (amo/exact-key-entry source-descriptor exact-value)
     :requests (mapv #(candidate-request (:value %) target-entry opts path-segment)
                     source-candidates)}))

(defn- missing-target-entry
  [source-type target-type target-entry polarity]
  (map-entry-failure source-type
                     target-type
                     :map-missing-key
                     polarity
                     :missing-key
                     (:key target-entry)
                     {:expected-key (:key target-entry)}))

(defn- nullable-key-result
  [source-type target-type source-entry target-entry polarity]
  (map-entry-failure source-type
                     target-type
                     :map-nullable-key
                     polarity
                     :nullable-key
                     (:key target-entry)
                     {:actual-key (:key source-entry)
                      :expected-key (:key target-entry)}))

(defn- exact-target-results
  [run-child source-type target-type source-descriptor target-entry opts]
  (let [{:keys [source-candidates source-exact-entry requests]}
        (exact-target-plan source-descriptor target-entry opts)
        value-results (mapv run-child requests)
        nullable? (and (= :required-explicit (:kind target-entry))
                       (= :optional-explicit (:kind source-exact-entry)))]
    (cond
      (empty? source-candidates)
      (if (= :required-explicit (:kind target-entry))
        [(missing-target-entry source-type target-type target-entry (:polarity opts))]
        [])

      nullable?
      (conj value-results
            (nullable-key-result source-type target-type source-exact-entry target-entry (:polarity opts)))

      :else
      value-results)))

(defn- exact-source-results
  [run-child source-type target-type source-entry target-domain-entries opts]
  (let [source-key (ato/exact-value-type (ato/derive-prov (:inner-key-type source-entry))
                                         (:exact-value source-entry))
        targets (vec (filter #(amo/key-domain-covered? source-key (:inner-key-type %))
                             target-domain-entries))
        requests (mapv #(candidate-request (:value source-entry) % opts nil) targets)]
    (if (seq targets)
      (candidate-results run-child requests)
      [(map-entry-failure source-type
                          target-type
                          :map-unexpected-key
                          (:polarity opts)
                          :unexpected-key
                          (:key source-entry)
                          {:actual-key (:key source-entry)})])))

(defn- expand-domain-entry
  [source-entry]
  (let [inner-key-type (:inner-key-type source-entry)]
    (if (at/union-type? inner-key-type)
      (mapcat #(expand-domain-entry (assoc source-entry
                                          :key %
                                          :key-type %
                                          :inner-key-type %
                                          :exact-value nil))
              (:members inner-key-type))
      [source-entry])))

(defn- domain-entry-result
  [run-child source-type target-type source-entry target-domain-entries opts]
  (let [source-key-type (:inner-key-type source-entry)
        targets (vec (filter #(amo/key-domain-covered? source-key-type (:inner-key-type %))
                             target-domain-entries))
        requests (mapv #(candidate-request (:value source-entry) % opts nil) targets)]
    (if (seq targets)
      (candidate-results run-child requests)
      [(ascs/cast-fail source-type
                       target-type
                       :map-key-domain
                       (:polarity opts)
                       :map-key-domain-not-covered
                       []
                       {:actual-key (:key source-entry)
                        :source-key-domain source-key-type})])))

(defn- domain-entry-results
  [run-child source-type target-type source-entry target-domain-entries opts]
  (mapcat #(domain-entry-result run-child
                                source-type
                                target-type
                                %
                                target-domain-entries
                                opts)
          (expand-domain-entry source-entry)))

(defn- map-children
  [run-child source-type target-type opts]
  (let [source-desc (amo/map-entry-descriptor (:entries (ato/normalize source-type)))
        target-desc (amo/map-entry-descriptor (:entries (ato/normalize target-type)))
        target-exacts (vec (amo/effective-exact-entries target-desc))
        target-values (set (map :exact-value target-exacts))
        target-domains (vec (:domain-entries target-desc))
        source-exacts (vec (amo/effective-exact-entries source-desc))
        extra-exacts (vec (remove #(contains? target-values (:exact-value %)) source-exacts))
        source-domains (vec (:domain-entries source-desc))]
    (vec (concat (mapcat #(exact-target-results run-child source-type target-type source-desc % opts)
                         target-exacts)
                 (mapcat #(exact-source-results run-child source-type target-type % target-domains opts)
                         extra-exacts)
                 (mapcat #(domain-entry-results run-child source-type target-type % target-domains opts)
                         source-domains)))))

(s/defn check-map-cast :- csch/CastResult
  [run-child :- (s/pred fn?) source-type :- ats/SemanticType target-type :- ats/SemanticType opts :- s/Any]
  (let [children (map-children run-child source-type target-type opts)]
    (ascs/aggregate-children source-type target-type :map (:polarity opts) :map-cast-failed children)))
