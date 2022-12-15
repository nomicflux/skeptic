(ns skeptic.checking
  (:require [clojure.walk :as walk]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schema :as dschema]
            [schema.core :as s]
            [clojure.set :as set]
            [skeptic.schematize :as schematize]
            [clojure.repl :as repl])
  (:import [schema.core Either Schema]))

(def spy-on false)
(def spy-only #{:matched-arglists :expected-arglist :actual-arglist :actual-arglist-orig})

;; TODO: infer function types from actually applied args, if none given
;; TODO: representation for cast values instead of just static types (such as dynamic functions cast to actual arg types)
;; TODO: also, infer outputs from what is actually output
;; TODO: gather schema info for internal functions
;; TODO: check that function output matches schema
;; TODO: how to go from macroexpanded version to line in code?
;; TODO: should code resolution de-var everything in macroexpansion? what would this do to actual vars?

(defn spy
  [msg x]
  (when (and spy-on (or (nil? spy-only)
                        (= spy-only msg)
                        (contains? spy-only msg)))
    (try (println msg (pr-str x))
        (catch Exception e
          (println msg e))))
  x)

(s/defn valid-schema?
  [s]
  (or (instance? Schema s)
      (class? s)
      (and (coll? s) (every? valid-schema? s))))

(s/defn s-expr?
 [x]
  (and (seq? x)
       (or (-> x first ifn?)
           (-> x first s-expr?))))

(s/defn def?
  [x]
  (and (seq? x)
       (or (-> x first (= 'def))
           (-> x first (= 'clojure.core/def)))))

(s/defn defn?
  [x]
  (and (seq? x)
       (or (-> x first (= 'defn))
           (-> x first (= 'clojure.core/defn))
           (-> x first (= #'clojure.core/defn)))))

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

(s/defn loop?
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/loop))
           (-> x first (= #'clojure.core/loop))
           (-> x first (= 'loop))
           (-> x first (= 'loop*)))))

(s/defn if?
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/if))
           (-> x first (= 'if))

           ;; Should only get `when` if a var and wasn't macroexpanded
           ;; But the macroexpansion of `(when p x)` is just `(if p (do x))`, so if we treat it like an
           ;; `if` (which can have only one branch), it should parse correctly
           (-> x first (= #'clojure.core/when)))))

(s/defn do?
  [x]
  (and (seq? x)
       (-> x first (= 'do))))

(s/defn try?
  [x]
  (and (seq? x)
       (-> x first (= 'try))))

(s/defn throw?
  [x]
  (and (seq? x)
       (-> x first (= 'throw))))

(s/defn catch?
  [x]
  (and (seq? x)
       (-> x first (= 'catch))))

(s/defn finally?
  [x]
  (and (seq? x)
       (-> x first (= 'finally))))

(s/defn convert-arglists
  [arity {:keys [schema arglists output]}]
  (let [direct-res (spy :ca-dr (get arglists arity))
        {:keys [count] :as varargs-res} (spy :ca-va (get arglists :varargs))]
    (spy :ca-arity arity)
    (if (and arity (or (and count varargs-res) direct-res))
      (let [res (spy :ca-res (if (>= arity count) varargs-res direct-res))
            schemas (spy :ca-schemas (mapv (fn [{:keys [schema name] :as s}] (s/one (or schema s) name)) (:schema res)))]
        {:schema (s/make-fn-schema output [schemas])
         :output output
         :arglist (spy :ca-arglist (mapv :schema schemas))})
      (do
        (assert (valid-schema? schema) "Must provide a schema")
        {:schema schema
         :output output}))))

(s/defn get-from-dict
  ([dict arity expr]
   (get-from-dict dict arity expr nil))
  ([dict arity expr default]
   (if-let [res (or (try (get dict (name expr)) (catch Exception _e nil))
                    (get dict (str expr))
                    (get dict expr))]
     (spy :gfd-found (convert-arglists arity res))
     (do
       (assert (valid-schema? default) "Must provide a schema")
       (spy :gfd-default {:schema default})))))

(s/defn either?
  [s]
  (instance? Either s))

(s/defn eitherize
  [[t1 t2 & _r :as types]]
  (cond
    t2
    (if (contains? types nil)
      (s/maybe (eitherize (disj types nil)))
      (apply s/either types))

    t1 t1

    :else s/Any))

(defn fn-name
  [f]
  (as-> (str f) $
    (repl/demunge $)
    (or (re-find #"(.+)--\d+@" $)
        (re-find #"(.+)@" $))
    (last $)
    (symbol $)))

(declare seq-type)

;; TODO: clean up mutual recursion between get-type and seq-type, make stack-safe
(s/defn get-type
  ([dict expr]
   (get-type dict #{} expr))
  ([dict local-vars expr]
   (get-type dict local-vars nil expr))
  ([dict local-vars arity expr]
   (spy :gt expr)
   (spy (keyword (str "gt-res-" (str expr)))
        (cond
          (nil? expr) nil
          ;;(instance? Schema expr) {:schema s/Schema}
          (vector? expr) {:schema (vector (seq-type dict local-vars arity expr))}
          (set? expr) {:schema #{(seq-type dict local-vars arity expr)}}
          (map? expr) {:schema {(seq-type dict local-vars arity (keys expr)) (seq-type dict local-vars arity (vals expr))}}
          (or (list? expr) (seq? expr)) {:schema (vector (seq-type dict local-vars arity expr))}
          (symbol? expr) (spy :gt-symbol (if (contains? local-vars expr)
                                           (spy :gt-local-symbol (merge {:schema s/Any} (get local-vars expr)))
                                           (spy :gt-global-symbol (get-from-dict dict arity expr s/Symbol))))
          (var? expr) (spy :gt-var (get-type dict local-vars arity (or @expr (symbol expr))))
          (int? expr) {:schema s/Int}
          (string? expr) {:schema s/Str}
          (keyword? expr) {:schema s/Keyword}
          (boolean? expr) {:schema s/Bool}
          (fn? expr) (spy :gt-fn (let [{:keys [schema] :as res} (get-type dict local-vars arity (spy :gt-fn-name (fn-name expr)))]
                                   (assert (valid-schema? schema) "Must return a schema")
                                   (if (and schema (not (= schema s/Symbol)))
                                     res
                                     {:schema (s/=> s/Any [s/Any])
                                      :output s/Any
                                      :arglist [s/Any]})))
          :else (spy :gt-class {:schema (class expr)}))))) ;; TODO: Where to add in dynamic type casts?

(s/defn seq-type
  [dict local-vars arity s]
  (let [types (reduce (fn [acc next]
                        (conj acc (:schema (get-type dict local-vars arity next))))
                #{}
                s)]
    (eitherize types)))

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

;; TODO: Not stack-safe; can we rewrite this to use loop? Or walk? Can we use transducers to better stream
;; intermediate results to improve performance?
(s/defn attach-schema-info
  ([dict expr]
   (attach-schema-info dict {} nil expr))
  ([dict local-vars expr]
   (attach-schema-info dict local-vars nil expr))
  ([dict local-vars arity expr]
   (spy :all-expr [(reduce (fn [curr p] (if (p expr) (reduced (fn-name p)) curr)) :val [nil? let? loop? defn? fn-expr? def? if? try? throw? s-expr?])
                   expr])
   (let [res (merge
              {:context local-vars
               :expr expr}
              (cond
                (nil? expr) {:schema (s/maybe s/Any)} ;; TODO: thread through expectations so that we can cast Anys
                (s-expr? expr)
                (cond
                  ;; TODO: def & defn need to add to a global set of symbols
                  ;; TODO: need support for for, doseq, doall
                  ;; TODO: let over a defn doesn't work, as we grab the defn's code, not the surrounding
                  ;; TODO: arity not passed through correctly in cases like `((if x + -) 2 3)`; is this even desirable?
                  (or (let? expr) (loop? expr))
                  (spy :let-expr (let [[letblock & body] (->> expr (drop 1))
                                       letpairs (spy :let-pairs (partition 2 letblock))

                                       {:keys [local-vars let-clauses]}
                                       (spy :let-clauses (reduce (fn [{:keys [local-vars let-clauses]} [newvar varbody]]
                                                                   (let [clause
                                                                         (spy :let-clause (attach-schema-info dict local-vars arity varbody))]
                                                                     {:local-vars (spy :local-vars (assoc local-vars
                                                                                                          newvar
                                                                                                          (assoc (select-keys clause [:schema :output])
                                                                                                                 :name (name newvar))))
                                                                      :let-clauses (conj let-clauses clause)}))
                                                                 {:let-clauses []
                                                                  :local-vars local-vars}
                                                                 letpairs))

                                       body-clauses (spy :let-body-clauses (map #(attach-schema-info dict local-vars arity %) (spy :let-body body)))
                                       output-clause (spy :let-output-clause (last body-clauses))]
                                   (assert (valid-schema? (:schema output-clause)) (format "Must provide valid schema: %s (%s)" (:schema output-clause) output-clause))
                                   {:schema (:schema output-clause)
                                    :output (:output output-clause)
                                    :extra-clauses (concat let-clauses body-clauses)}))

                  ;; TODO: Global vars
                  (or (defn? expr) (fn-expr? expr))
                  (spy :defn-expr (let [[vars & body] (last expr)
                                        _ (spy :defn-vars vars)
                                        _ (spy :defn-body body)
                                        defn-vars (into {} (map (fn [v] [v {}]) vars))
                                        clauses (map #(attach-schema-info dict (merge local-vars defn-vars) arity %) body)
                                        output (last clauses)]
                                    (assert (valid-schema? (:schema output)))
                                    (cond-> {:schema (:schema output)
                                             :output (:output output)
                                             :extra-clauses clauses}

                                      (defn? expr)
                                      (assoc :name (second expr)))))

                  ;; TODO: Global vars
                  (def? expr) (spy :def-expr (let [def-name (second expr)
                                                   body (spy :def-body (last expr))]
                                               (assoc (attach-schema-info dict local-vars arity body)
                                                      :name def-name)))

                  (if? expr) (spy :if-expr (let [[_ p t f] expr
                                                 p-info (spy :p-clause (attach-schema-info dict local-vars arity (spy :p-clause-body p)))
                                                 t-info (spy :t-clause (attach-schema-info dict local-vars arity (spy :t-clause-body t)))
                                                 f-info (spy :f-clause (attach-schema-info dict local-vars arity (spy :f-clause-body f)))
                                                 output-schema (eitherize (set [(:schema t-info) (:schema f-info)]))]
                                             {:schema (s/=> output-schema (eitherize (set [s/Bool (:schema p-info)])) (:schema t-info) (:schema f-info))
                                              :output output-schema
                                              :extra-clauses [p-info t-info f-info]}))

                  (do? expr) (spy :do-expr (let [exprs (spy :do-clauses (drop 1 expr))
                                                 clauses (map #(attach-schema-info dict local-vars arity %) exprs)
                                                 output-clause (spy :do-last-clause (last clauses))]
                                             (assert (valid-schema? Schema (:schema output-clause) "Must provide a schema"))
                                             {:schema (:schema output-clause) :extra-clauses clauses}))

                  (try? expr) (spy :try-expr (let [[_ & body] expr
                                                   [try-body after-body] (split-with (fn [c] (not (or (catch? c) (finally? c)))) body)
                                                   [catch-body finally-body] (split-with (fn [c] (not (finally? c))) after-body)
                                                   try-clauses (map #(attach-schema-info dict local-vars arity %) try-body)
                                                   try-output (last try-clauses)
                                                   catch-clauses (->> catch-body first (drop 3) (map #(attach-schema-info dict local-vars arity %)))
                                                   finally-clauses (->> finally-body first (drop 1) (map #(attach-schema-info dict local-vars arity %)))
                                                   finally-output (last finally-clauses)]
                                               {:extra-clauses (concat try-clauses catch-clauses finally-clauses)
                                                :schema (or (:schema finally-output) (:schema try-output))})) ;; TODO: Check for exceptions & exception type too?

                  (throw? expr) (spy :throw-expr {:schema s/Any}) ;; TODO: this isn't quite an any....

                  :else (let [[f & args] expr
                              fn-schema (spy :s-expr-fn (attach-schema-info dict local-vars (count args) f))
                              arg-schemas (spy :s-expr-args (map #(attach-schema-info dict local-vars (count args) %) args))]
                          (assert (valid-schema? (:schema fn-schema)) (format "Must provide a schema: %s (%s)" (:schema fn-schema) fn-schema))
                          (assert (valid-schema? (:output fn-schema)) (format "Must provide a schema output: %s (%s)" (:output fn-schema) fn-schema))
                          {:schema (:schema fn-schema)
                           :output (:output fn-schema)
                           :expected-arglist (:arglist fn-schema)
                           :actual-arglist arg-schemas
                           :extra-clauses (concat (or (:extra-clauses fn-schema) []) arg-schemas)}))
                :else (spy :val-expr (assoc (get-type dict local-vars arity expr)
                              :name expr))))] (if (:output res) res
       (assoc res :output (:schema res))))))

;; TODO: what can we assert here? We already either:
;; 1. Found a matching arglist, in which case we know the counts match; if expected is short, the last arg is
;;    a vararg and repeats (can we fix this representation? Is there a better one?). Not sure how actual could
;;    be short.
;; 2. We didn't find a matching arglist, in which case we assume that we have no valid data to match up; what
;;    then? (Can this still happen, or will we always get the dynamic fn type `(=> Any [Any])`?)
(s/defn match-up-arglists
  [expected actual]
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get expected n expected-vararg) (get actual n)])))

(s/defn match-s-exprs
  [{:keys [expected-arglist actual-arglist extra-clauses expr context]}]
  (let [actual-arglist (map #(select-keys % [:output :expr]) (spy :actual-arglist-orig actual-arglist))]
    (concat
     [{:blame expr
       :context context
       :errors (->> (spy :matched-arglists (match-up-arglists (spy :expected-arglist (vec expected-arglist))
                                                              (spy :actual-arglist (vec actual-arglist))))
                    (keep (partial apply inconsistence/inconsistent?)))}]
     (mapcat match-s-exprs extra-clauses))))

(s/defn check-s-expr
  [dict vars s-expr {:keys [keep-empty]}]
  (cond->> (match-s-exprs (attach-schema-info dict vars s-expr))

    (not keep-empty)
    (remove (comp empty? :errors))))

(s/defn normalize-fn-code
  [f]
  (-> f schematize/get-fn-code schematize/resolve-code-references))

(s/defn check-fn
  ([dict f]
   (check-fn dict f {}))
  ([dict f opts]
   (check-s-expr dict {} (normalize-fn-code f) opts)))

(s/defn annotate-fn
  [dict f]
  (->> f normalize-fn-code (attach-schema-info dict)))

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
                 s/Int]}}}
  "clojure.core/str"
   {:name "clojure.core/str"
    :schema (s/=> s/Str s/Any)
    :output s/Str
    :arglists {1 {:arglist ['s], :schema [{:schema s/Any, :optional? false, :name 's}]},
               :varargs
               {:arglist ['s ['more]],
                :count 2
                :schema
                [{:schema s/Any, :optional? false, :name 's}
                 s/Any]}}}})
