(ns skeptic.output.serialize
  "Coerce arbitrary Clojure values to JSON-safe data for the --debug output
  paths. This is a faithful dump: it renders every value uniformly as plain
  data. It does not know about skeptic's type system — skeptic types are
  tagged maps and serialize as tagged maps, same as any other map. Never
  throws.")

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
                       :_class (.getName (class v)))
    (map? v) (map-entries->safe v)
    (vector? v) (mapv json-safe v)
    (set? v) (into #{} (map json-safe) v)
    (sequential? v) (mapv json-safe v)
    (fn? v) {:t "fn" :pr (pr-str v)}
    :else {:t "unknown" :pr (pr-str v)}))
