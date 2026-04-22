(ns skeptic.analysis.bridge.render
  (:require [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def foldable-sources
  #{:schema :malli-spec :type-override})

(def ^:private source-priority
  {:type-override 0
   :malli-spec 1
   :schema 2})

(def ^:private default-render-opts
  {:explain-full false
   :fold-index nil})

(defn- source-rank
  [source]
  (or (get source-priority source)
      Integer/MAX_VALUE))

(defn- better-fold-entry
  [entry-a entry-b]
  (let [rank-a (source-rank (:source entry-a))
        rank-b (source-rank (:source entry-b))]
    (cond
      (< rank-a rank-b) entry-a
      (> rank-a rank-b) entry-b
      :else (if (neg? (compare (str (:qualified-sym entry-a))
                               (str (:qualified-sym entry-b))))
              entry-a
              entry-b))))

(defn- normalize-fold-key
  [type]
  (letfn [(strip-prov-local [x]
            (cond
              (at/semantic-type-value? x)
              (reduce-kv (fn [acc k v]
                           (assoc acc k (if (= k :prov) nil (strip-prov-local v))))
                         x
                         x)
              (map? x) (into {} (map (fn [[k v]] [(strip-prov-local k) (strip-prov-local v)])) x)
              (vector? x) (mapv strip-prov-local x)
              (set? x) (into #{} (map strip-prov-local) x)
              (seq? x) (doall (map strip-prov-local x))
              :else x))]
  (some-> type
          ato/normalize-for-declared-type
          strip-prov-local)))

(defn build-fold-index
  [dict provenance]
  (reduce-kv (fn [idx qualified-sym type]
               (let [source (prov/source (get provenance qualified-sym))]
                 (if (contains? foldable-sources source)
                   (let [fold-key (normalize-fold-key type)
                         entry {:qualified-sym qualified-sym
                                :source source}]
                     (update idx fold-key
                             (fn [existing]
                               (if existing
                                 (better-fold-entry existing entry)
                                 entry))))
                   idx)))
             {}
             dict))

(defn folded-entry
  [fold-index type]
  (when fold-index
    (get fold-index (normalize-fold-key type))))

(declare render-type-form*)

(defn render-fn-input-form*
  [method opts]
  (let [inputs (mapv #(render-type-form* % opts) (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn render-fn-input-form
  [method]
  (render-fn-input-form* method default-render-opts))

(defn- conditional-branch-types
  [type opts]
  (mapv (comp #(render-type-form* % opts) second) (:branches type)))

(defn render-type-form*
  [type opts]
  (let [opts (merge default-render-opts opts)
        type (ato/normalize-for-declared-type type)
        fold-hit (and (not (:explain-full opts))
                      (folded-entry (:fold-index opts) type))]
    (if fold-hit
      (:qualified-sym fold-hit)
      (cond
        (at/dyn-type? type) 'Any
        (at/bottom-type? type) 'Bottom
        (at/ground-type? type) (:display-form type)
        (at/numeric-dyn-type? type) 'Number
        (at/refinement-type? type) (:display-form type)
        (at/adapter-leaf-type? type) (:display-form type)
        (at/optional-key-type? type) (list 'optional-key (render-type-form* (:inner type) opts))
        (at/value-type? type) (let [v (:value type)] (if (nil? v) (symbol "nil") v))
        (at/type-var-type? type) (:name type)
        (at/forall-type? type) (list 'forall (:binder type) (render-type-form* (:body type) opts))
        (at/sealed-dyn-type? type) (list 'sealed (render-type-form* (:ground type) opts))
        (at/inf-cycle-type? type)
        (if-let [ref (:ref type)]
          (list 'InfCycle (at/ref-display-form ref))
          'InfCycle)
        (at/fn-method-type? type) (list* '=> (render-type-form* (:output type) opts) (render-fn-input-form* type opts))
        (at/fun-type? type)
        (if (= 1 (count (:methods type)))
          (render-type-form* (first (:methods type)) opts)
          (list* '=>* (map #(render-type-form* % opts) (:methods type))))
        (at/maybe-type? type) (list 'maybe (render-type-form* (:inner type) opts))
        (at/conditional-type? type) (list* 'conditional (conditional-branch-types type opts))
        (at/union-type? type) (list* 'union (map #(render-type-form* % opts) (sort-by pr-str (:members type))))
        (at/intersection-type? type) (list* 'intersection (map #(render-type-form* % opts) (sort-by pr-str (:members type))))
        (at/map-type? type)
        (into {}
              (map (fn [[k v]]
                     [(render-type-form* k opts)
                      (render-type-form* v opts)]))
              (:entries type))
        (at/vector-type? type) (mapv #(render-type-form* % opts) (:items type))
        (at/set-type? type) (into #{} (map #(render-type-form* % opts)) (:members type))
        (at/seq-type? type) (doall (map #(render-type-form* % opts) (:items type)))
        (at/var-type? type) (list 'var (render-type-form* (:inner type) opts))
        (at/placeholder-type? type) (at/placeholder-display-form (:ref type))
        :else type))))

(defn render-type-form
  [type]
  (render-type-form* type default-render-opts))

(defn render-type*
  [type opts]
  (some-> type
          (render-type-form* opts)
          pr-str))

(defn render-type
  [type]
  (render-type* type default-render-opts))

(declare type->json-data*)

(defn- name-str [x]
  (cond
    (nil? x) nil
    (or (symbol? x) (keyword? x)) (name x)
    :else (pr-str x)))

(defn- fn-method->json-data*
  [method opts]
  {:t "fn-method"
   :inputs (mapv #(type->json-data* % opts) (:inputs method))
   :output (type->json-data* (:output method) opts)
   :variadic (boolean (:variadic? method))
   :min_arity (:min-arity method)})

(defn type->json-data*
  "Serialize a semantic type into JSON-friendly tagged data. Returns nil for nil."
  [type opts]
  (let [opts (merge default-render-opts opts)
        type (some-> type ato/normalize-for-declared-type)
        fold-hit (and type
                      (not (:explain-full opts))
                      (folded-entry (:fold-index opts) type))]
    (cond
      (nil? type) nil
      fold-hit {:t "named"
                :name (str (:qualified-sym fold-hit))
                :source (name (:source fold-hit))}
      (at/dyn-type? type) {:t "any"}
      (at/bottom-type? type) {:t "bottom"}
      (at/ground-type? type) {:t "ground" :name (name-str (:display-form type))}
      (at/numeric-dyn-type? type) {:t "numeric-dyn" :name "Number"}
      (at/refinement-type? type) {:t "refinement" :name (name-str (:display-form type))}
      (at/adapter-leaf-type? type) {:t "adapter" :name (name-str (:display-form type))}
      (at/optional-key-type? type) {:t "optional-key" :inner (type->json-data* (:inner type) opts)}
      (at/value-type? type) {:t "value" :value (pr-str (:value type))}
      (at/type-var-type? type) {:t "type-var" :name (name-str (:name type))}
      (at/forall-type? type) {:t "forall"
                              :binder (mapv name-str (:binder type))
                              :body (type->json-data* (:body type) opts)}
      (at/sealed-dyn-type? type) {:t "sealed" :ground (type->json-data* (:ground type) opts)}
      (at/inf-cycle-type? type) (cond-> {:t "inf-cycle"}
                                  (:ref type) (assoc :ref (pr-str (at/ref-display-form (:ref type)))))
      (at/fn-method-type? type) (fn-method->json-data* type opts)
      (at/fun-type? type) {:t "fun" :methods (mapv #(fn-method->json-data* % opts) (:methods type))}
      (at/maybe-type? type) {:t "maybe" :inner (type->json-data* (:inner type) opts)}
      (at/conditional-type? type) {:t "conditional"
                                   :branches (mapv (comp #(type->json-data* % opts) second) (:branches type))}
      (at/union-type? type) {:t "union"
                             :members (mapv #(type->json-data* % opts)
                                            (sort-by pr-str (:members type)))}
      (at/intersection-type? type) {:t "intersection"
                                    :members (mapv #(type->json-data* % opts)
                                                   (sort-by pr-str (:members type)))}
      (at/map-type? type) {:t "map"
                           :entries (mapv (fn [[k v]]
                                            {:key (type->json-data* k opts)
                                             :val (type->json-data* v opts)})
                                          (:entries type))}
      (at/vector-type? type) {:t "vector" :items (mapv #(type->json-data* % opts) (:items type))}
      (at/set-type? type) {:t "set" :members (mapv #(type->json-data* % opts)
                                                   (sort-by pr-str (:members type)))}
      (at/seq-type? type) {:t "seq" :items (mapv #(type->json-data* % opts) (:items type))}
      (at/var-type? type) {:t "var" :inner (type->json-data* (:inner type) opts)}
      (at/placeholder-type? type) {:t "placeholder"
                                   :name (pr-str (at/placeholder-display-form (:ref type)))}
      :else {:t "unknown" :form (pr-str type)})))

(defn type->json-data
  [type]
  (type->json-data* type default-render-opts))

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
