(ns skeptic.analysis.bridge.render
  (:require [schema.core :as s]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def foldable-sources
  #{:schema :malli :type-override})

(s/defschema FnMethodJsonData
  {:t (s/eq "fn-method")
   :inputs [s/Any]
   :output s/Any
   :variadic s/Bool
   :min_arity s/Int})

(def ^:private default-render-opts
  {:explain-full false})

(def ^:private core-schema-leaf-syms
  '#{schema.core/Int
     schema.core/Str
     schema.core/Keyword
     schema.core/Symbol
     schema.core/Bool
     schema.core/Num
     schema.core/Any})

(s/defn ^:private folded-name :- (s/maybe s/Symbol)
  [t :- at/SemanticType]
  (let [p (prov/of t)
        sym (:qualified-sym p)]
    (when (and sym
               (contains? foldable-sources (prov/source p))
               (not (contains? core-schema-leaf-syms sym)))
      sym)))

(declare render-type-form*)

(s/defn render-fn-input-form* :- s/Any
  [method :- at/SemanticType
   opts :- s/Any]
  (let [inputs (mapv #(render-type-form* % opts) (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(s/defn render-fn-input-form :- s/Any
  [method :- at/SemanticType]
  (render-fn-input-form* method default-render-opts))

(declare type-sort-key)

(s/defn ^:private conditional-branch-types :- [s/Any]
  [type :- at/SemanticType
   opts :- s/Any]
  (mapv (comp #(render-type-form* % opts) :type) (:branches type)))

(s/defn ^:private render-refinement-form :- s/Any
  [type :- at/SemanticType
   opts :- s/Any]
  (let [display (:display-form type)]
    (if (and (seq? display)
             (= 'constrained (first display))
             (= 3 (count display)))
      (list 'constrained
            (render-type-form* (:base type) opts)
            (nth display 2))
      display)))

(s/defn render-type-form* :- s/Any
  [type :- (s/maybe at/SemanticType)
   opts :- s/Any]
  (let [opts (merge default-render-opts opts)
        type (some-> type ato/normalize)
        fold-hit (and type
                      (not (:explain-full opts))
                      (folded-name type))]
    (if fold-hit
      fold-hit
      (cond
        (nil? type) nil
        (at/dyn-type? type) 'Any
        (at/bottom-type? type) 'Bottom
        (at/ground-type? type) (:display-form type)
        (at/numeric-dyn-type? type) 'Number
        (at/refinement-type? type) (render-refinement-form type opts)
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
        (at/specialization-ref-type? type)
        (list 'SpecializationRef (at/ref-display-form (:ref type)))
        (at/fn-method-type? type) (list* '=> (render-type-form* (:output type) opts) (render-fn-input-form* type opts))
        (at/fun-type? type)
        (if (= 1 (count (:methods type)))
          (render-type-form* (first (:methods type)) opts)
          (list* '=>* (map #(render-type-form* % opts) (:methods type))))
        (at/maybe-type? type) (list 'maybe (render-type-form* (:inner type) opts))
        (at/conditional-type? type) (list* 'conditional (conditional-branch-types type opts))
        (at/union-type? type) (list* 'union (map #(render-type-form* % opts) (sort-by #(type-sort-key % opts) (:members type))))
        (at/intersection-type? type) (list* 'intersection (map #(render-type-form* % opts) (sort-by #(type-sort-key % opts) (:members type))))
        (at/map-type? type)
        (into {}
              (map (fn [[k v]]
                     [(render-type-form* k opts)
                      (render-type-form* v opts)]))
              (:entries type))
        (at/set-type? type) (into #{} (map #(render-type-form* % opts)) (sort-by #(type-sort-key % opts) (:members type)))
        (at/seq-type? type)
        (let [items (mapv #(render-type-form* % opts) (at/pattern-prefix (:pattern type)))
              tail  (at/pattern-tail (:pattern type))]
          (case (:ordered-coll-kind type)
            :vector     (if tail
                          (conj items '& (render-type-form* tail opts))
                          items)
            :sequential (if tail
                          (doall (concat items ['& (render-type-form* tail opts)]))
                          (doall items))))
        (at/var-type? type) (list 'var (render-type-form* (:inner type) opts))
        (at/placeholder-type? type) (at/placeholder-display-form (:ref type))
        :else type))))

(s/defn ^:private type-sort-key :- s/Str
  [type :- at/SemanticType
   opts :- s/Any]
  (pr-str (render-type-form* type opts)))

(s/defn render-type-form :- s/Any
  [type :- (s/maybe at/SemanticType)]
  (render-type-form* type default-render-opts))

(s/defn render-type* :- (s/maybe s/Str)
  [type :- (s/maybe at/SemanticType)
   opts :- s/Any]
  (some-> type
          (render-type-form* opts)
          pr-str))

(s/defn render-type :- (s/maybe s/Str)
  [type :- (s/maybe at/SemanticType)]
  (render-type* type default-render-opts))

(declare type->json-data*)

(s/defn ^:private name-str :- (s/maybe s/Str)
  [x :- s/Any]
  (cond
    (nil? x) nil
    (or (symbol? x) (keyword? x)) (name x)
    :else (pr-str x)))

(s/defn ^:private fn-method->json-data* :- FnMethodJsonData
  [method :- at/SemanticType
   opts :- s/Any]
  {:t "fn-method"
   :inputs (mapv #(type->json-data* % opts) (:inputs method))
   :output (type->json-data* (:output method) opts)
   :variadic (boolean (:variadic? method))
   :min_arity (:min-arity method)})

(s/defn type->json-data* :- s/Any
  "Serialize a semantic type into JSON-friendly tagged data. Returns nil for nil."
  [type :- (s/maybe at/SemanticType)
   opts :- s/Any]
  (let [opts (merge default-render-opts opts)
        type (some-> type ato/normalize)
        fold-hit (and type
                      (not (:explain-full opts))
                      (folded-name type))]
    (cond
      (nil? type) nil
      fold-hit {:t "named"
                :name (str fold-hit)
                :source (name (prov/source (prov/of type)))}
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
      (at/specialization-ref-type? type) {:t "specialization-ref"
                                          :ref (pr-str (at/ref-display-form (:ref type)))}
      (at/fn-method-type? type) (fn-method->json-data* type opts)
      (at/fun-type? type) {:t "fun" :methods (mapv #(fn-method->json-data* % opts) (:methods type))}
      (at/maybe-type? type) {:t "maybe" :inner (type->json-data* (:inner type) opts)}
      (at/conditional-type? type) {:t "conditional"
                                   :branches (mapv (comp #(type->json-data* % opts) :type) (:branches type))}
      (at/union-type? type) {:t "union"
                             :members (mapv #(type->json-data* % opts)
                                            (sort-by #(type-sort-key % opts) (:members type)))}
      (at/intersection-type? type) {:t "intersection"
                                    :members (mapv #(type->json-data* % opts)
                                                   (sort-by #(type-sort-key % opts) (:members type)))}
      (at/map-type? type) {:t "map"
                           :entries (mapv (fn [[k v]]
                                            {:key (type->json-data* k opts)
                                             :val (type->json-data* v opts)})
                                          (:entries type))}
      (at/set-type? type) {:t "set" :members (mapv #(type->json-data* % opts)
                                                   (sort-by #(type-sort-key % opts) (:members type)))}
      (at/seq-type? type) (let [tail (at/pattern-tail (:pattern type))]
                            (cond-> {:t (case (:ordered-coll-kind type)
                                          :vector "vector"
                                          :sequential "seq")
                                     :items (mapv #(type->json-data* % opts)
                                                  (at/pattern-prefix (:pattern type)))}
                              tail (assoc :tail (type->json-data* tail opts))))
      (at/var-type? type) {:t "var" :inner (type->json-data* (:inner type) opts)}
      (at/placeholder-type? type) {:t "placeholder"
                                   :name (pr-str (at/placeholder-display-form (:ref type)))}
      :else {:t "unknown" :form (pr-str type)})))

(s/defn type->json-data :- s/Any
  [type :- (s/maybe at/SemanticType)]
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
