(ns skeptic.checking
  (:require [clojure.walk :as walk]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schema :as dschema]
            [schema.core :as s]
            [clojure.set :as set]
            [skeptic.schematize :as schematize]))

;; TODO: infer function types from actually applied args, if none given
;; TODO: representation for cast values instead of just static types (such as dynamic functions cast to actual arg types)
;; TODO: also, infer outputs from what is actually output
;; TODO: gather schema info for internal functions
;; TODO: check that function output matches schema
;; TODO: how to go from macroexpanded version to line in code?

(s/defn s-expr?
 [x]
  (and (seq? x)
       (-> x first ifn?)))

(s/defn def?
  [x]
  (and (seq? x)
       (-> x first (= 'def))))

(s/defn fn-expr?
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/fn))
           (-> x first (= #'clojure.core/fn))
           (-> x first (= 'fn*)))))

(s/defn let?
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/let))
           (-> x first (= #'clojure.core/let))
           (-> x first (= 'let))
           (-> x first (= 'let*)))))

(s/defn convert-arglists
  [arity {:keys [schema arglists output]}]
  (let [direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)]
    (if-let [res (if (>= arity count) varargs-res direct-res)]
      (let [schemas (mapv (fn [{:keys [schema name] :as s}] (s/one (or schema s) name)) (:schema res))]
       {:schema (s/make-fn-schema output [schemas])
        :output output
        :arglist (mapv :schema schemas)})
     {:schema schema
      :output output})))

(s/defn get-from-dict
  ([dict arity expr]
   (get-from-dict dict arity expr nil))
  ([dict arity expr default]
   (if-let [res (or (get dict (name expr))
                    (get dict (str expr))
                    (get dict expr))]
     (convert-arglists arity res)
     {:schema default :output default})))

(s/defn get-type
  ([dict expr]
   (get-type dict #{} expr))
  ([dict vars expr]
   (get-type dict vars nil expr))
  ([dict vars arity expr]
   (cond
     (nil? expr) nil
     (symbol? expr) (if (contains? vars expr)
                      (get vars expr) ;; TODO: allow for variables to receive types
                      (get-from-dict dict arity expr s/Symbol))
     (var? expr) (get-from-dict dict arity (symbol expr) s/Symbol)
     (int? expr) {:schema s/Int}
     (string? expr) {:schema s/Str}
     (keyword? expr) {:schema s/Keyword}
     (boolean? expr) {:schema s/Bool}
     :else {:schema (class expr)}))) ;; TODO: Where to add in dynamic type?

(s/defn eitherize
  [[t1 t2 & _r :as types]]
  (if t2
    (apply s/either types)
    t1))

(s/defn seq-type
  [dict vars s]
  (let [types (reduce (fn [acc next]
                        (if (sequential? next)
                          (set/union acc (seq-type dict vars next))
                          (conj acc (get-type dict vars next))))
                #{}
                s)]
    (cond
      (empty? types) s/Any
      (contains? types nil) (s/maybe (eitherize (disj types nil)))
      :else (eitherize types))))

(defn de-def
  [x]
  (if (def? x)
    (->> x (drop 2) first)
    x))

(defn de-fn
  [x]
  (if (fn-expr? x)
    (let [[args body] (->> x (drop 1) first)]
      {:variables (->> args (map (fn [n] [n {}])) (into {}))
       :body body})
    {:variables {}
     :body x}))

(s/defn attach-schema-info
  ([dict expr]
   (attach-schema-info dict #{} nil expr))
  ([dict vars expr]
   (attach-schema-info dict vars nil expr))
  ([dict vars arity expr]
   (let [res (merge
              {:name (str expr)
               :context vars
               :expr expr}
              (cond
                (nil? expr) {:schema (s/maybe s/Any)}
                (s-expr? expr) (cond
                                 (let? expr) (let [[letblock body] (->> expr (drop 1))
                                                   letpairs (partition 2 letblock)

                                                   {:keys [vars let-clauses]}
                                                   (reduce (fn [{:keys [vars let-clauses]} [newvar varbody]]
                                                             (let [clause
                                                                   (attach-schema-info dict vars nil varbody)]
                                                               {:vars (assoc vars
                                                                             newvar
                                                                             (assoc clause
                                                                                    :name (name newvar)))
                                                                :let-clauses (conj let-clauses clause)}))
                                                           {:let-clauses []
                                                            :vars vars}
                                                           letpairs)]
                                               (assoc (attach-schema-info dict vars nil body)
                                                      :let-clauses let-clauses))

                                 (fn-expr? expr) (let [{:keys [variables body]} (de-fn expr)]
                                                   (attach-schema-info dict (merge vars variables) nil body))

                                 (def? expr) (attach-schema-info dict vars nil (de-def expr))

                                 :else (let [[f & args] expr
                                      fn-schema (attach-schema-info dict vars (dec (count expr)) f)
                                      arg-schemas (map (partial attach-schema-info dict vars (dec (count expr))) args)
                                      output (-> fn-schema :output)]
                                  {:schema fn-schema :output output :arglist arg-schemas}))
                (vector? expr) {:schema (vector (seq-type dict vars expr))}
                (list? expr) {:schema (list (seq-type dict vars expr))}
                (set? expr) {:schema #{(seq-type dict vars expr)}}
                (map? expr) {:schema {(seq-type dict vars (keys expr)) (seq-type dict vars (vals expr))}}
                :else (get-type dict vars arity expr)))]
     (if (:output res)
       res
       (assoc res :output (:schema res))))))

(s/defn match-s-exprs
  [{{expected-args :arglist} :schema
    actual-args :arglist
    let-clauses :let-clauses
    expr :expr
    context :context}]
  (let [actual-args (map :output actual-args)]
    (concat
     (if (seq expected-args)
       [{:blame expr
         :context context
         :errors (->> (map vector expected-args actual-args)
                      (keep (partial apply inconsistence/inconsistent?)))}]
       [])
     (mapcat match-s-exprs let-clauses))))

(s/defn check-s-expr
  [dict vars s-expr]
  (->> (attach-schema-info dict vars s-expr)
       match-s-exprs
       (remove (comp empty? :errors))))

;; TODO: this only shows the problematic term, the blame is sometimes on context
(s/defn check-with-blame
  [dict vars s-expr]
  (loop [[next & rest :as ctx] [s-expr]
         acc []]
    (cond
      (empty? ctx) acc
      (s-expr? next) (recur (concat rest next)
                            (let [errors (check-s-expr dict vars next)]
                              (conj acc errors)))
      :else (recur rest acc))))

(s/defn normalize-fn-code
  [f]
  (-> f schematize/get-fn-code schematize/resolve-code-references de-def de-fn))

(s/defn check-fn
  [dict f]
  (let [{:keys [variables body]} (normalize-fn-code f)]
    (check-with-blame dict variables body)))

(def sample-dict
  {"clojure.core/+"
   {:name "clojure.core/+"
    :schema (s/=> s/Int s/Int)
    :output s/Int
    :arglists {1 {:arglist ['x], :schema [{:schema s/Int, :optional? false, :name 'x}]},
               2
               {:arglist ['y 'z],
                :schema
                [{:schema s/Int, :optional? false, :name 'y}
                 {:schema s/Int, :optional? false, :name 'z}]}
               :varargs
               {:arglist ['y 'z ['more]],
                :count 3
                :schema
                [{:schema s/Int, :optional? false, :name 'y}
                 {:schema s/Int, :optional? false, :name 'z}
                 s/Int]}}}})
