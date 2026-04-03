(ns skeptic.analysis.bridge.render
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare render-type-form
         type->schema-compat)

(defn render-fn-input-form
  [method]
  (let [inputs (mapv render-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn render-type-form
  [type]
  (let [type (ab/normalize-type type)]
    (cond
      (at/dyn-type? type) 'Any
      (at/bottom-type? type) 'Bottom
      (at/ground-type? type) (:display-form type)
      (at/refinement-type? type) (:display-form type)
      (at/adapter-leaf-type? type) (:display-form type)
      (at/optional-key-type? type) (list 'optional-key (render-type-form (:inner type)))
      (at/value-type? type) (:value type)
      (at/type-var-type? type) (:name type)
      (at/forall-type? type) (list 'forall (:binder type) (render-type-form (:body type)))
      (at/sealed-dyn-type? type) (list 'sealed (render-type-form (:ground type)))
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

(defn render-schema
  [schema]
  (some-> schema
          abc/schema-display-form
          pr-str))

(defn display-form
  [value]
  (let [value (abl/localize-schema-value value)]
    (cond
      (abc/schema? value) (abc/schema-display-form value)
      :else (render-type-form (ab/schema->type value)))))

(defn display
  [value]
  (some-> value
          display-form
          pr-str))

(defn fn-method->schema-compat
  [method]
  (mapv (fn [idx input]
          (s/one (type->schema-compat input)
                 (symbol (str "arg" idx))))
        (range)
        (:inputs method)))

(defn type->schema-compat
  [type]
  (let [type (ab/normalize-type type)]
    (cond
      (at/dyn-type? type) s/Any
      (at/bottom-type? type) sb/Bottom
      (at/ground-type? type)
      (let [ground (:ground type)]
        (cond
          (= ground :int) s/Int
          (= ground :str) s/Str
          (= ground :keyword) s/Keyword
          (= ground :symbol) s/Symbol
          (= ground :bool) s/Bool
          (and (map? ground) (:class ground)) (:class ground)
          :else ground))

      (at/refinement-type? type)
      (or (get-in type [:adapter-data :source-schema])
          (type->schema-compat (:base type)))

      (at/adapter-leaf-type? type)
      (or (get-in type [:adapter-data :source-schema])
          s/Any)

      (at/optional-key-type? type)
      (s/optional-key (type->schema-compat (:inner type)))

      (at/value-type? type)
      (let [value (:value type)
            inner (type->schema-compat (:inner type))]
        (if (sb/schema-literal? value)
          value
          (sb/valued-schema inner value)))

      (at/type-var-type? type) type
      (at/forall-type? type) type
      (at/sealed-dyn-type? type) type

      (at/fn-method-type? type)
      (s/make-fn-schema (type->schema-compat (:output type))
                        [(fn-method->schema-compat type)])

      (at/fun-type? type)
      (s/make-fn-schema (type->schema-compat (:output (first (:methods type))))
                        (mapv fn-method->schema-compat (:methods type)))

      (at/maybe-type? type) (s/maybe (type->schema-compat (:inner type)))
      (at/union-type? type) (apply sb/join (map type->schema-compat (:members type)))
      (at/intersection-type? type) (apply s/both (map type->schema-compat (:members type)))
      (at/map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(type->schema-compat k)
                    (type->schema-compat v)]))
            (:entries type))
      (at/vector-type? type) (mapv type->schema-compat (:items type))
      (at/set-type? type) (into #{} (map type->schema-compat) (:members type))
      (at/seq-type? type) (doall (map type->schema-compat (:items type)))
      (at/var-type? type) (sb/variable (type->schema-compat (:inner type)))
      (at/placeholder-type? type) (sb/placeholder-schema (:ref type))
      :else type)))

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

        (contains? entry :arg-schema) (update :arg-schema #(mapv strip-derived-types %))
        (contains? entry :params) (update :params #(mapv strip-derived-types %))))))
