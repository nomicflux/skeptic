(ns skeptic.analysis.normalize
  (:require [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn arg-entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :type)
           (contains? entry :optional?)
           (contains? entry :name))))

(defn normalize-arg-entry
  [entry]
  (let [base (cond
               (at/semantic-type-value? entry)
               {:type entry}

               (arg-entry-map? entry) entry

               (map? entry)
               (throw (IllegalArgumentException.
                       (format "Expected typed arg entry, got %s" (pr-str entry))))

               :else
               (throw (IllegalArgumentException.
                       (format "Expected type arg entry, got %s" (pr-str entry)))))
        type (some-> (:type base) ato/normalize-type-for-declared-type)]
    {:type type
     :optional? (boolean (:optional? base))
     :name (:name base)}))

(defn normalize-arglist-entry
  [entry]
  (let [types (mapv normalize-arg-entry (or (:types entry) []))]
    (cond-> (-> entry
                (dissoc :types)
                (assoc :types types))
      (not (contains? entry :count))
      (assoc :count (count types)))))

(defn entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :type)
           (contains? entry :output-type)
           (contains? entry :arglists))))

(defn normalize-entry
  [entry]
  (when (some? entry)
    (let [base (cond
                 (at/semantic-type-value? entry)
                 {:type entry}

                 (entry-map? entry) entry

                 (map? entry)
                 (throw (IllegalArgumentException.
                         (format "Expected typed entry, got %s" (pr-str entry))))

                 :else
                 (throw (IllegalArgumentException.
                         (format "Expected type entry, got %s" (pr-str entry)))))
          type (some-> (:type base) ato/normalize-type-for-declared-type)
          output-type (some-> (:output-type base) ato/normalize-type-for-declared-type)
          arglists (some-> (:arglists base)
                           ((fn [arglists]
                              (into {}
                                    (map (fn [[k v]]
                                           [k (normalize-arglist-entry v)]))
                                    arglists))))]
      (abr/strip-derived-types
       (cond-> (merge (dissoc base :type :output-type :arglists)
                      {:type (or type at/Dyn)})
         output-type (assoc :output-type output-type)
         arglists (assoc :arglists arglists))))))
