(ns skeptic.output.serialize
  "Coerce arbitrary Clojure values to JSON-safe data for the --debug output
  paths. Skeptic types are routed through the bridge's type->json-data;
  Vars / Classes / Namespaces / fns are tagged and stringified. Never throws."
  (:require [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]))

(defn- skeptic-type?
  [v]
  (and (map? v)
       (at/known-semantic-type-tag? (at/semantic-type-tag v))))

(declare json-safe)

(defn- map-entries->safe
  [m]
  (reduce-kv (fn [acc k v]
               (let [safe-k (json-safe k)]
                 (if (nil? safe-k)
                   acc
                   (assoc acc safe-k (json-safe v)))))
             {}
             m))

(defn json-safe
  [v]
  (cond
    (nil? v) nil
    (skeptic-type? v) (let [rendered (abr/type->json-data v)]
                        (if (map? rendered)
                          (map-entries->safe rendered)
                          rendered))
    (keyword? v) v
    (symbol? v) (str v)
    (string? v) v
    (boolean? v) v
    (number? v) v
    (var? v) {:t "var-ref" :sym (str v)}
    (class? v) {:t "class" :name (.getName ^Class v)}
    (instance? clojure.lang.Namespace v)
    {:t "ns" :name (str (ns-name v))}
    (record? v) (assoc (map-entries->safe (into {} v))
                       :t "record"
                       :class (.getName (class v)))
    (map? v) (map-entries->safe v)
    (vector? v) (mapv json-safe v)
    (set? v) (into #{} (map json-safe) v)
    (sequential? v) (mapv json-safe v)
    (fn? v) {:t "fn" :pr (pr-str v)}
    :else {:t "unknown" :pr (pr-str v)}))
