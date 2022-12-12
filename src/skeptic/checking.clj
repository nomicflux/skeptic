(ns skeptic.checking
  (:require [clojure.walk :as walk]
            [skeptic.schema :as dschema]
            [schema.core :as s]
            [clojure.set :as set]))

(s/defn s-expr?
 [x]
  (and (list? x)
       (-> x first ifn?)))

(s/defn convert-arglists
  [arity {:keys [schema arglists output]}]
  (if-let [res (get arglists arity)]
    (let [schemas (mapv (fn [{:keys [schema optional? name]}] (s/one (if optional? (s/maybe schema) schema) name)) (:schema res))]
      {:schema (s/make-fn-schema output [schemas])
       :output output
       :arglist (mapv :schema schemas)})
    {:schema schema
     :output output}))

(s/defn get-from-dict
  ([dict arity expr]
   (get-from-dict dict arity expr nil))
  ([dict arity expr default]
   (if-let [res (get dict (name expr))]
     (convert-arglists arity res)
     {:schema default :output default})))

(s/defn get-type
  ([dict expr]
   (get-type dict nil expr))
  ([dict arity expr]
   (cond
     (nil? expr) nil
     (symbol? expr) (get-from-dict dict arity expr s/Symbol)
     (int? expr) {:schema s/Int}
     (string? expr) {:schema s/Str}
     (keyword? expr) {:schema s/Keyword}
     :else {:schema (class expr)})))

(s/defn eitherize
  [[t1 t2 & r]]
  (if t2
    (reduce (fn [acc next] (s/either acc next))
            (s/either t1 t2)
            r)
    t1))

(s/defn seq-type
  [dict s]
  (let [types (reduce (fn [acc next]
                        (if (sequential? next)
                          (set/union acc (seq-type dict next))
                          (conj acc (get-type dict next))))
                #{}
                s)]
    (cond
      (empty? types) s/Any
      (contains? types nil) (s/maybe (eitherize (disj types nil)))
      :else (eitherize types))))

(s/defn attach-schema-info
  ([dict expr]
   (attach-schema-info dict nil expr))
  ([dict arity expr]
   (let [res (merge
    {:name (str expr)
     :expr expr}
    (cond
      (nil? expr) {:schema (s/maybe s/Any)}
      (s-expr? expr) (let [[f & args] expr
                           fn-schema (attach-schema-info dict (dec (count expr)) f)
                           arg-schemas (map (partial attach-schema-info dict (dec (count expr))) args)
                           output (-> fn-schema :output)]
                       {:schema fn-schema :output output :arglist arg-schemas})
      (vector? expr) {:schema (vector (seq-type dict expr))}
      (list? expr) {:schema (list (seq-type dict expr))}
      (set? expr) {:schema #{(seq-type dict expr)}}
      (map? expr) {:schema {(seq-type dict (keys expr)) (seq-type dict (vals expr))}}
      :else (get-type dict arity expr)))]
     (if (:output res)
       res
       (assoc res :output (:schema res))))))

(s/defn check-s-expr
 [s-expr dict])

(def sample-dict
  {"+" {:name "+" :schema (s/=> s/Int s/Int) :output s/Int :arglists {1 {:arglist ['x], :schema [{:schema s/Int, :optional? false, :name 'x}]},
   2
   {:arglist ['y 'z],
    :schema
    [{:schema (s/maybe s/Int), :optional? false, :name 'y}
     {:schema s/Int, :optional? false, :name 'z}]}}}})
