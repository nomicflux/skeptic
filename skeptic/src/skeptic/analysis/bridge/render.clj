(ns skeptic.analysis.bridge.render
  (:require [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(declare render-type-form)

(defn render-fn-input-form
  [method]
  (let [inputs (mapv render-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn render-type-form
  [type]
  (let [type (ato/normalize-type type)]
    (cond
      (at/dyn-type? type) 'Any
      (at/bottom-type? type) 'Bottom
      (at/ground-type? type) (:display-form type)
      (at/refinement-type? type) (:display-form type)
      (at/adapter-leaf-type? type) (:display-form type)
      (at/optional-key-type? type) (list 'optional-key (render-type-form (:inner type)))
      (at/value-type? type) (let [v (:value type)] (if (nil? v) (symbol "nil") v))
      (at/type-var-type? type) (:name type)
      (at/forall-type? type) (list 'forall (:binder type) (render-type-form (:body type)))
      (at/sealed-dyn-type? type) (list 'sealed (render-type-form (:ground type)))
      (at/inf-cycle-type? type)
      (if-let [ref (:ref type)]
        (list 'InfCycle (at/ref-display-form ref))
        'InfCycle)
      (at/fn-method-type? type) (list* '=> (render-type-form (:output type)) (render-fn-input-form type))
      (at/fun-type? type)
      (if (= 1 (count (:methods type)))
        (render-type-form (first (:methods type)))
        (list* '=>* (map render-type-form (:methods type))))
      (at/maybe-type? type) (list 'maybe (render-type-form (:inner type)))
      (at/union-type? type) (list* 'union (map render-type-form (sort-by pr-str (:members type))))
      (at/intersection-type? type) (list* 'intersection (map render-type-form (sort-by pr-str (:members type))))
      (at/map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(render-type-form k)
                    (render-type-form v)]))
            (:entries type))
      (at/vector-type? type) (mapv render-type-form (:items type))
      (at/set-type? type) (into #{} (map render-type-form) (:members type))
      (at/seq-type? type) (doall (map render-type-form (:items type)))
      (at/var-type? type) (list 'var (render-type-form (:inner type)))
      (at/placeholder-type? type) (at/placeholder-display-form (:ref type))
      :else type)))

(defn render-type
  [type]
  (some-> type
          render-type-form
          pr-str))

(defn polarity->side
  [polarity]
  (case polarity
    :positive :term
    :negative :context
    :global :global
    :none :none
    :term))

(defn flip-polarity
  [polarity]
  (case polarity
    :positive :negative
    :negative :positive
    polarity))

(def derived-type-keys
  [:node-type])

(defn strip-derived-types
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (let [entry (apply dissoc entry derived-type-keys)]
      (cond-> entry
        (contains? entry :locals) (update :locals (fn [locals]
                                                    (into {}
                                                          (map (fn [[k v]]
                                                                 [k (strip-derived-types v)]))
                                                          locals)))

        (contains? entry :arglists) (update :arglists (fn [arglists]
                                                        (into {}
                                                              (map (fn [[k v]]
                                                                     [k (strip-derived-types v)]))
                                                              arglists)))

        (contains? entry :param-specs) (update :param-specs #(mapv strip-derived-types %))
        (contains? entry :params) (update :params #(mapv strip-derived-types %))))))
