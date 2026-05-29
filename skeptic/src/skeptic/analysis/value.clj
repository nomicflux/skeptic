(ns skeptic.analysis.value
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]
            [skeptic.worker.wire :as wire]))

(def ^:private cljs-tag-symbol-table
  {'string   [:str 'Str]
   'number   [:numeric-dyn]
   'boolean  [:bool 'Bool]
   'clj-nil  [:nil]
   'cljs.core/Keyword [:keyword 'Keyword]
   'cljs.core/Symbol  [:symbol 'Symbol]})

(s/defn ^:private cljs-tag-symbol->type :- at/SemanticType
  [prov :- provs/Provenance tag :- s/Symbol]
  (if-let [[kind name-sym] (get cljs-tag-symbol-table tag)]
    (case kind
      :nil (ato/exact-value-type prov nil)
      :numeric-dyn (at/NumericDyn prov)
      (at/->GroundT prov kind name-sym))
    (at/Dyn prov)))

(s/defn cljs-tag->type :- at/SemanticType
  [prov :- provs/Provenance tag :- s/Any]
  (cond
    (symbol? tag) (cljs-tag-symbol->type prov tag)
    (set? tag) (let [nil? (contains? tag 'clj-nil)
                     non-nil (disj tag 'clj-nil)
                     members (mapv #(cljs-tag->type prov %) non-nil)
                     unioned (cond
                               (empty? members) (at/Dyn prov)
                               (= 1 (count members)) (first members)
                               :else (ato/union-type prov members))]
                 (if nil?
                   (at/->MaybeT prov unioned)
                   unioned))
    :else (at/Dyn prov)))

(def ^:private canonical-class-grounds
  "Host classes that map to a simple, standard Skeptic ground. Keyed by class so
   the handle a worker hands back can be recognized host-side via `host-handle`
   with no oracle round-trip. `:dyn`/`:numeric-dyn` are non-`GroundT` kinds."
  {java.lang.Long [:int 'Int]     java.lang.Integer [:int 'Int]
   java.lang.Short [:int 'Int]    java.lang.Byte [:int 'Int]
   java.math.BigInteger [:int 'Int]
   Long/TYPE [:int 'Int]          Integer/TYPE [:int 'Int]
   Short/TYPE [:int 'Int]         Byte/TYPE [:int 'Int]
   java.lang.Double [:double 'Double]  java.lang.Float [:float 'Float]
   Double/TYPE [:double 'Double]       Float/TYPE [:float 'Float]
   java.lang.String [:str 'Str]   java.lang.Boolean [:bool 'Bool]
   Boolean/TYPE [:bool 'Bool]
   clojure.lang.Keyword [:keyword 'Keyword]  clojure.lang.Symbol [:symbol 'Symbol]
   java.lang.Number [:numeric-dyn]           java.lang.Object [:dyn]})

(s/defn ^:private canonical-handle->type :- (s/maybe at/SemanticType)
  "If `handle` is the bootstrap handle of a canonical class, return its Skeptic
   ground host-side (no oracle call); else nil."
  [prov :- provs/Provenance handle :- s/Any]
  (some (fn [[klass [kind name-sym]]]
          (when (= handle (oracle/host-handle klass))
            (case kind
              :dyn (at/Dyn prov)
              :numeric-dyn (at/NumericDyn prov)
              (at/->GroundT prov kind name-sym))))
        canonical-class-grounds))

(s/defn class->type :- at/SemanticType
  [prov :- provs/Provenance klass :- s/Any]
  (let [klass (sb/canonical-scalar-schema klass)]
    (cond
      (= klass s/Int) (at/->GroundT prov :int 'Int)
      (= klass s/Str) (at/->GroundT prov :str 'Str)
      (= klass s/Keyword) (at/->GroundT prov :keyword 'Keyword)
      (= klass s/Symbol) (at/->GroundT prov :symbol 'Symbol)
      (= klass s/Bool) (at/->GroundT prov :bool 'Bool)
      (or (= klass s/Num)
          (= klass Number)
          (= klass java.lang.Number))
      (at/NumericDyn prov)
      (and (class? klass)
           (not (or (= klass s/Any)
                    (= klass Object)
                    (= klass java.lang.Object))))
      (at/->GroundT prov {:class (oracle/class-handle klass)} (abc/schema-explain klass))
      (symbol? klass) (cljs-tag->type prov klass)
      (set? klass) (cljs-tag->type prov klass)
      (oracle/handle? klass) (or (canonical-handle->type prov klass)
                                 (at/->GroundT prov {:class klass} (symbol (oracle/class-name klass))))
      :else (at/Dyn prov))))

(declare type-of-value type-join*)

(s/defn exact-runtime-value-type :- at/SemanticType
  [prov :- provs/Provenance value :- s/Any]
  (at/->ValueT prov (type-of-value prov value) value))

(s/defn collection-element-type :- at/SemanticType
  [prov :- provs/Provenance values :- s/Any]
  (if (seq values)
    (type-join* prov (map #(type-of-value prov %) values))
    (at/Dyn prov)))

(s/defn closed-seq-type :- at/SemanticType
  [prov :- provs/Provenance constructor :- s/Any values :- s/Any]
  (let [item-types (mapv #(type-of-value prov %) values)]
    (constructor (prov/with-refs prov (mapv prov/of item-types))
                 item-types
                 nil)))

(s/defn map-value-type :- at/SemanticType
  [prov :- provs/Provenance m :- s/Any]
  (let [entries (into {}
                      (map (fn [[k v]]
                             [(exact-runtime-value-type prov k)
                              (exact-runtime-value-type prov v)]))
                      m)
        refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) entries)]
    (at/->MapT (prov/with-refs prov refs) entries)))

(s/defn type-of-value :- at/SemanticType
  [prov :- provs/Provenance value :- s/Any]
  (cond
    (nil? value) (ato/exact-value-type prov nil)
    (or (integer? value)
        (string? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)) (class->type prov (class value))
    (vector? value) (let [item-types (mapv #(type-of-value prov %) value)]
                      (at/->SeqT (prov/with-refs prov (mapv prov/of item-types))
                                 (at/pattern-from-prefix-tail item-types nil)
                                 :vector))
    (or (list? value) (seq? value)) (closed-seq-type prov
                                                     (fn [p items tail] (at/->SeqT p (at/pattern-from-prefix-tail items tail) :sequential))
                                                     value)
    (set? value) (let [element (collection-element-type prov value)]
                   (at/->SetT (prov/with-refs prov [(prov/of element)]) #{element} true))
    (wire/nonedn? value) (class->type prov (wire/nonedn-class value))
    (map? value) (map-value-type prov value)
    (oracle/handle? value) (class->type prov java.lang.Class)
    :else (class->type prov (class value))))

(s/defn type-join* :- at/SemanticType
  [prov :- provs/Provenance types :- [at/SemanticType]]
  (let [types (vec (remove nil? (map #(ato/normalize-type prov %) types)))
        non-bottom (vec (remove at/bottom-type? types))]
    (cond
      (seq non-bottom) (ato/union-type prov non-bottom)
      (seq types) (at/BottomType prov)
      :else (at/Dyn prov))))

(s/defn join :- at/SemanticType
  "Join item types into a union. The result's provenance is `anchor-prov`
  (the container's); item provs stay on the items."
  [anchor-prov :- provs/Provenance types :- [at/SemanticType]]
  (type-join* anchor-prov types))
