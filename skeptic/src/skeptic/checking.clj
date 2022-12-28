(ns skeptic.checking
  (:require [clojure.walk :as walk]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schema :as dschema]
            [schema.core :as s]
            [clojure.set :as set]
            [skeptic.schematize :as schematize]
            [clojure.repl :as repl]
            [clojure.pprint :as pprint]
            [taoensso.tufte :as tufte])
  (:import [schema.core Either Schema]
           [clojure.lang Named]))

(def spy-on false)
(def spy-only #{:fn-expr :fn-once-expr :defn-vars :defn-body :def-name :def-body
                :fn-tests :s-expr-expected-arglist :s-expr-actual-arglist :s-expr-extra-clauses
                :fn-decl :fn-clauses :fn-clause :fn-arglists :fn-outputs
                :match-s-exprs-full
                :defn-expr :def-expr :gt-type :all-expr})

;; TODO: infer function types from actually applied args, if none given
;; TODO: representation for cast values instead of just static types (such as dynamic functions cast to actual arg types)
;; TODO: also, infer outputs from what is actually output
;; TODO: gather schema info for internal functions
;; TODO: check that function output matches schema
;; TODO: how to go from macroexpanded version to line in code?
;; TODO: handle `new`

(defn spy*
  [msg x]
  (when (and spy-on (or (nil? spy-only)
                           (contains? spy-only msg)))
      (try (println msg (pr-str x))
           (catch Exception e
             (println msg e))))
  x)

(defmacro spy
  [msg x]
  #_
  `(spy* ~msg ~x)
  #_
  (if (seq? x)
    `(tufte/p ~msg ~x)
    x)
  x)

(defn valid-schema?
  [s]
  (or (instance? Schema s)
      (class? s)
      (and (coll? s) (every? valid-schema? s))))

(defmacro assert-schema
  [s]
  #_
  `(do (assert (valid-schema? ~s) (format "Must be valid schema: %s" ~s))
       ~s)
  s)

(defmacro assert-has-schema
  [x]
  #_
  `(do (assert (valid-schema? (:schema ~x)) (format "Must be valid schema: %s (%s)" (:schema ~x) (pr-str ~x)))
       ~x)
  x)

(defn def?
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

(s/defn fn-once?
  "(fn* [x] (...) (...) ...)"
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/fn))
           (-> x first (= #'clojure.core/fn))
           (-> x first (= 'fn*)))
       (-> x second vector?)))

(s/defn fn-expr?
  "(fn* ([x] ...) ([x y] ...) ...)"
  [x]
  (and (seq? x)
       (or (-> x first (= 'clojure.core/fn))
           (-> x first (= #'clojure.core/fn))
           (-> x first (= 'fn*)))
       (-> x second seq?)
       (-> x second first vector?)))

(defn s-expr?
 [x]
  (and (seq? x)
       (or (-> x first ifn?)
           (-> x first fn-expr?)
           (-> x first fn-once?)
           (-> x first s-expr?))))

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

(defn fn-name
  [f]
  (as-> (str f) $
    (repl/demunge $)
    (or (re-find #"(.+)--\d+@" $)
        (re-find #"(.+)@" $))
    (last $)
    (symbol $)))

(defn ubername
  [s]
  (cond
    (fn? s) (fn-name s)
    (var? s) (-> s symbol name)
    (instance? Named s) (name s)
    :else (str s)))

(defn dynamic-fn-schema
  [arity]
  (s/=> s/Any (vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))))

(s/defn convert-arglists
  [arity {:keys [schema arglists output] :as info}]
  (let [direct-res (spy :ca-dr (get arglists arity))
        {:keys [count] :as varargs-res} (spy :ca-va (get arglists :varargs))]
    (if (and (spy :ca-arity arity)
             (or (and count varargs-res)
                 direct-res))
      (let [res (spy :ca-type (if (and count (>= arity count)) varargs-res direct-res))
            schemas (spy :ca-schemas (mapv (fn [{:keys [schema name] :as s}] (s/one (or schema s s/Any) name))
                                           (or (:schema res)
                                               (vec (repeat (or arity 0) (s/one s/Any 'anon-arg))))))
            arglist (spy :ca-arglist (mapv :schema schemas))]
        ;;(assert (not (nil? arglist)) (format "Function should have arglist: %s (%s) (%s) (%s)" arglist schemas res info))
        (assert-has-schema
         (spy :ca-res
              {:schema (if (and output (seq schemas))
                         (s/make-fn-schema output [schemas])
                         (dynamic-fn-schema arity))
               :output (or output s/Any)
               :arglist arglist})))
      (let [schema (or schema (dynamic-fn-schema arity))]
        ;;(assert (valid-schema? schema) (format "Must provide a schema: %s %s" schema info))
        {:schema schema
         :output (or output s/Any)}))))

(s/defn get-from-dict
  ([dict arity expr]
   (get-from-dict dict arity expr nil))
  ([dict arity expr default]
   (if-let [res (or (try (get dict (name expr)) (catch Exception _e nil))
                    (get dict (str expr) )
                    (get dict expr))]
     (spy :gfd-found (assert-has-schema (convert-arglists arity (spy :gfd-lookup res))))
     (spy :gfd-default (assert-has-schema default)))))

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

(declare seq-type)

;; TODO: clean up mutual recursion between get-type and seq-type, make stack-safe
;; TODO: keywords & other values in function position
(s/defn get-type
  ([dict fn-position? expr]
   (get-type fn-position? dict #{} expr))
  ([dict fn-position? local-vars expr]
   (get-type dict fn-position? local-vars nil expr))
  ([dict fn-position? local-vars arity expr]
   (spy :gt expr)
   (spy :gt-type [(reduce (fn [curr p] (if (p expr) (reduced (fn-name p)) curr)) :class
                          [nil? vector? set? map? list? seq? symbol? var? int? string? keyword? boolean? fn?])
                   expr])
   (spy :gt-res
        (cond
          (nil? expr) (assert-has-schema {:schema (s/maybe s/Any)})
          (vector? expr) (assert-has-schema {:schema (vector (seq-type dict local-vars arity expr))})
          (set? expr) (assert-has-schema {:schema #{(seq-type dict local-vars arity expr)}})
          (map? expr) (assert-has-schema {:schema {(seq-type dict local-vars arity (keys expr)) (seq-type dict local-vars arity (vals expr))}})
          (or (list? expr) (seq? expr)) (assert-has-schema {:schema (vector (seq-type dict local-vars arity expr))})
          (symbol? expr) (spy :gt-symbol (cond
                                           (contains? (spy :gt-local-cache local-vars) (spy :gt-local-expr expr))
                                           (spy :gt-local-symbol (assert-has-schema (merge {:schema s/Any}
                                                                                           (convert-arglists arity (get local-vars expr)))))

                                           :else
                                           (spy :gt-global-symbol (assert-has-schema (get-from-dict dict arity expr (if fn-position?
                                                                                                                      {:schema (dynamic-fn-schema arity)
                                                                                                                       :output s/Any}
                                                                                                                      {:schema s/Symbol}))))))
          (var? expr) (spy :gt-var (assert-has-schema (get-type dict fn-position? local-vars arity (or @expr (symbol expr)))))
          (int? expr) (assert-has-schema {:schema s/Int})
          (string? expr) (assert-has-schema {:schema s/Str})
          (keyword? expr) (assert-has-schema {:schema s/Keyword})
          (boolean? expr) (assert-has-schema {:schema s/Bool})
          (fn? expr) (spy :gt-fn (assert-has-schema (let [{:keys [schema] :as res} (get-type dict fn-position? local-vars arity (spy :gt-fn-name (fn-name expr)))]
                                    (assert (valid-schema? schema) "Must return a schema")
                                    (if (and schema (not (= schema s/Symbol)))
                                      res
                                      {:schema (dynamic-fn-schema arity)
                                       :output s/Any
                                       :arglist [s/Any]}))))
          :else (spy :gt-class (assert-has-schema {:schema (spy :gt-class-expr (class expr))})))))) ;; TODO: Where to add in dynamic type casts?

(s/defn seq-type
  [dict local-vars arity s]
  (let [types (reduce (fn [acc next]
                        ;; TODO: we need to be able to parse s-exprs in a collection
                        ;; Mutual recursion on attach-schema-info?
                        (conj acc (:schema (get-type dict false local-vars arity next))))
                #{}
                s)]
    (assert-schema (eitherize types))))

;; TODO: Not stack-safe; can we rewrite this to use loop? Or walk? Can we use transducers to better stream
;; intermediate results to improve performance?
;; TODO: atoms? refs? reader macros?
(s/defn attach-schema-info
  ([dict expr]
   (attach-schema-info dict false {} nil expr))
  ([dict fn-position? local-vars expr]
   (attach-schema-info dict fn-position? local-vars nil expr))
  ([dict fn-position? local-vars arity expr]

   (spy :all-expr [(reduce (fn [curr p] (if (p expr) (reduced (fn-name p)) curr))
                           :val
                           [nil? let? loop? defn? fn-once? fn-expr? def? if? try? throw? s-expr?])
                   expr])
   (let [res (merge
              {:context local-vars
               :expr (spy :expr expr)}
              (assert-has-schema
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
                                                                          (spy :let-clause (attach-schema-info dict false local-vars arity varbody))]
                                                                      {:local-vars (spy :local-vars (assoc local-vars
                                                                                                           newvar
                                                                                                           (assoc (select-keys clause [:schema
                                                                                                                                       :output
                                                                                                                                       :arglists])
                                                                                                                  :name (ubername (spy :let-newvar newvar)))))
                                                                       :let-clauses (conj let-clauses clause)}))
                                                                  {:let-clauses []
                                                                   :local-vars local-vars}
                                                                  letpairs))

                                        body-clauses (spy :let-body-clauses (mapv #(attach-schema-info dict false local-vars arity %) (spy :let-body body)))
                                        output-clause (spy :let-output-clause (last body-clauses))]
                                    (assert (valid-schema? (:schema output-clause)) (format "Must provide valid schema: %s (%s)" (:schema output-clause) output-clause))
                                    {:schema (:schema output-clause)
                                     :output (:output output-clause)
                                     :extra-clauses (concat let-clauses body-clauses)}))

                   ;; TODO: Global vars from def
                   (or (defn? expr) (fn-once? expr))
                   (spy :fn-once-expr (let [[vars & body] (drop-while (complement vector?) expr)
                                         _ (spy :defn-vars vars)
                                         _ (spy :defn-body body)
                                         defn-vars (try (into {} (map (fn [v] [v {}]) vars))
                                                        (catch Exception e
                                                          (println "Error with vars:" expr vars e)))
                                         clauses (mapv #(attach-schema-info dict false (merge local-vars defn-vars) arity %) body)
                                         output (spy :defn-output (last clauses))
                                         arglist [(mapv (fn [v] (s/one s/Any v)) vars)]
                                         fn-schema (s/make-fn-schema (:output output) arglist)]
                                     (cond-> {:schema fn-schema
                                              :output (:schema output)
                                              :arglists {(count vars) {:arglist vars
                                                                       :schema (mapv (fn [v] {:schema s/Any
                                                                                             :optional? false
                                                                                             :name v})
                                                                                     vars)}}
                                              :extra-clauses clauses}

                                       (defn? expr)
                                       (assoc :name (second expr)))))

                   (fn-expr? expr)
                   (spy :fn-expr (let [pairs (spy :fn-decl (drop-while (complement seq?) expr))
                                       clauses (spy :fn-clauses (mapv (fn [[vs & body :as pair]]
                                                                        (spy :fn-clause (let [vs (into {} (mapv (fn [v] {v {}}) vs))
                                                                                              sub-clauses (mapv #(attach-schema-info dict true (merge local-vars vs) (count vs) %) body)
                                                                                              arglist [(mapv (fn [v] (s/one s/Any v)) vs)]
                                                                                              output (last sub-clauses)]
                                                                                          {:schema (s/make-fn-schema (:output output) arglist)
                                                                                           :output (:schema output)
                                                                                           :expr pair
                                                                                           :context local-vars
                                                                                           :extra-clauses sub-clauses
                                                                                           :arglists {(count vs) {:arglist arglist
                                                                                                                  :schema (mapv (fn [v] {:schema s/Any
                                                                                                                                        :optional? false
                                                                                                                                        :name v})
                                                                                                                                vs)}}})))
                                                                      pairs))
                                       all-arglists (spy :fn-arglists (into {} (mapcat :arglists clauses)))
                                       all-outputs (spy :fn-outputs (eitherize (into #{} (map :output clauses))))]
                                   (assoc (convert-arglists arity
                                                      {:schema all-outputs ;; TODO: how do we express the type of multi-arity functions? Best to drop the output/schema distinction.
                                                       :output all-outputs
                                                       :arglists all-arglists})
                                          :extra-clauses clauses)))

                   ;; TODO: Global vars
                   (def? expr) (spy :def-expr (let [def-name (spy :def-name (second expr))
                                                    body (spy :def-body (last expr))]
                                                (assoc (attach-schema-info dict false local-vars arity body)
                                                       :name def-name)))

                   (if? expr) (spy :if-expr (let [[_ p t f] expr
                                                  p-info (spy :p-clause (attach-schema-info dict false local-vars arity (spy :p-clause-body p)))
                                                  t-info (spy :t-clause (attach-schema-info dict false local-vars arity (spy :t-clause-body t)))
                                                  f-info (spy :f-clause (attach-schema-info dict false local-vars arity (spy :f-clause-body f)))
                                                  output-schema (eitherize (set [(:schema t-info) (:schema f-info)]))]
                                              {:schema (s/=> output-schema (eitherize (set [s/Bool (:schema p-info)])) (:schema t-info) (:schema f-info))
                                               :output output-schema
                                               :extra-clauses [p-info t-info f-info]}))

                   (do? expr) (spy :do-expr (let [exprs (spy :do-clauses (drop 1 expr))
                                                  clauses (mapv #(attach-schema-info dict false local-vars arity %) exprs)
                                                  output-clause (spy :do-last-clause (last clauses))]
                                              (assert (valid-schema? (:schema output-clause)) "Must provide a schema")
                                              {:schema (:schema output-clause) :extra-clauses clauses}))

                   (try? expr) (spy :try-expr (let [[_ & body] expr
                                                    [try-body after-body] (split-with (fn [c] (not (or (catch? c) (finally? c)))) body)
                                                    [catch-body finally-body] (split-with (fn [c] (not (finally? c))) after-body)
                                                    try-clauses (mapv #(attach-schema-info dict false local-vars arity %) try-body)
                                                    try-output (last try-clauses)
                                                    catch-clauses (->> catch-body first (drop 3) (mapv #(attach-schema-info dict false local-vars arity %)))
                                                    finally-clauses (->> finally-body first (drop 1) (mapv #(attach-schema-info dict false local-vars arity %)))
                                                    finally-output (last finally-clauses)]
                                                {:extra-clauses (concat try-clauses catch-clauses finally-clauses)
                                                 :schema (or (:schema finally-output) (:schema try-output))})) ;; TODO: Check for exceptions & exception type too?

                   (throw? expr) (spy :throw-expr {:schema s/Any}) ;; TODO: this isn't quite an any....

                   :else (spy :application-expr (let [[f & args] expr
                                fn-schema (spy :s-expr-fn (attach-schema-info dict true local-vars (count args) f))
                                arg-schemas (spy :s-expr-args (mapv #(attach-schema-info dict false local-vars (count args) %) args))]

                            ;;(assert (valid-schema? (:schema fn-schema)) (format "Must provide a schema: %s (%s)" (:schema fn-schema) fn-schema))
                            ;;(assert (valid-schema? (:output fn-schema)) (format "Must provide a schema output: %s (%s)" (:output fn-schema) fn-schema))
                            (spy :fn-tests [expr (seq? expr)
                                            (when (seq? expr) [(first expr) (= (first expr) 'fn*)])
                                            (when (seq? expr) [(second expr) (list? (second expr)) (type (second expr))])
                                            (when (and (seq? expr) (seq? (second expr)))
                                              [(first (second expr)) (vector? (first (second expr)))])])
                            {:schema (:schema fn-schema)
                             :output (:output fn-schema)
                             :expected-arglist (spy :s-expr-expected-arglist (:arglist fn-schema))
                             :actual-arglist (spy :s-expr-actual-arglist (map #(select-keys % [:expr :schema :output :name]) arg-schemas))
                             :extra-clauses (spy :s-expr-extra-clauses (concat [fn-schema] arg-schemas))})))
                 :else (spy :val-expr (assoc (get-type dict fn-position? local-vars arity expr)
                                             :name expr)))))] (if (:output res) res
       (assoc res :output (:schema res))))))

(defn attach-schema-info-loop
  [dict
   expr]
  (loop [expr-stack (list expr)
         results {}
         step 0]
    (if (empty? expr-stack)
      (vals results)

      (let [{:keys [expr fn-position? local-vars arity callback]} (first expr-stack)]
        (if (s-expr? expr)
          (cond
            (or (loop? expr) (let? expr))
            (let [[letblock & body] (->> expr (drop 1))
                                        letpairs (spy :let-pairs (partition 2 letblock))

                                        {:keys [local-vars let-clauses]}
                                        (spy :let-clauses (reduce (fn [{:keys [local-vars let-clauses]} [newvar varbody]]
                                                                    (let [clause
                                                                          (spy :let-clause (attach-schema-info dict false local-vars arity varbody))]
                                                                      {:local-vars (spy :local-vars (assoc local-vars
                                                                                                           newvar
                                                                                                           (assoc (select-keys clause [:schema
                                                                                                                                       :output
                                                                                                                                       :arglists])
                                                                                                                  :name (ubername (spy :let-newvar newvar)))))
                                                                       :let-clauses (conj let-clauses clause)}))
                                                                  {:let-clauses []
                                                                   :local-vars local-vars}
                                                                  letpairs))

                                        body-clauses (spy :let-body-clauses (mapv #(attach-schema-info dict false local-vars arity %) (spy :let-body body)))
                                        output-clause (spy :let-output-clause (last body-clauses))]
                                    {:schema (:schema output-clause)
                                     :output (:output output-clause)
                                     :extra-clauses (concat let-clauses body-clauses)}
                                    (recur (concat next-clauses expr-stack) {step current-clause} (inc step)))

            :else nil)

          (recur (rest expr-stack) {step (get-type dict fn-position? local-vars arity expr)} (inc step)))))))

;; TODO: what can we assert here? We already either:
;; 1. Found a matching arglist, in which case we know the counts match; if expected is short, the last arg is
;;    a vararg and repeats (can we fix this representation? Is there a better one?). Not sure how actual could
;;    be short.
;; 2. We didn't find a matching arglist, in which case we assume that we have no valid data to match up; what
;;    then? (Can this still happen, or will we always get the dynamic fn type `(=> Any [Any])`?)
(s/defn match-up-arglists
  [expected actual]
  (spy :match-up-actual-list actual)
  (spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(assert-schema (spy :match-up-expected (get expected n expected-vararg)))
       (assert-has-schema (spy :match-up-actual (get actual n)))])))

(s/defn match-s-exprs
  ([to-match]
   (match-s-exprs [] to-match))
  ([parent-name
    {:keys [expected-arglist actual-arglist extra-clauses expr context name] :as to-match}]
   (spy :match-s-exprs-full to-match)
   (spy :match-s-exprs-expected-arglist expected-arglist)
   (spy :match-s-exprs-extra-clauses extra-clauses)
   (let [actual-arglist (mapv #(dissoc % :context) (spy :actual-arglist-orig actual-arglist))
         path (remove nil? (conj parent-name name))]
     (concat
      (if (seq expected-arglist)
        (do
          (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
                  (format "Arglists must not be nil: %s %s\n%s"
                          expected-arglist actual-arglist to-match))
          (assert (>= (count actual-arglist) (count expected-arglist))
                  (format "Actual should have at least as many elements as expected: %s %s\n%s"
                          expected-arglist actual-arglist to-match))
          [{:blame expr
            :path path
            :context context
            :errors (->> (spy :matched-arglists (match-up-arglists (spy :expected-arglist expected-arglist)
                                                                   (spy :actual-arglist actual-arglist)))
                         (keep (partial apply inconsistence/inconsistent?)))}])
        [])
      (mapcat (partial match-s-exprs path) extra-clauses)))))

(s/defn check-s-expr
  [dict vars s-expr {:keys [keep-empty clean-context]}]
  (cond->> (match-s-exprs (spy :check-s-expr-attached (attach-schema-info dict false vars (spy :check-s-expr-expr s-expr))))

    (not keep-empty)
    (remove (comp empty? :errors))

    clean-context
    (map #(dissoc % :context))))

(s/defn normalize-fn-code
  [ns-refs f]
  (->> f
       schematize/get-fn-code
       (schematize/resolve-code-references ns-refs)))

(s/defn check-fn
  ([ns-refs dict f]
   (check-fn ns-refs dict f {}))
  ([ns-refs dict f opts]
   (check-s-expr dict {} (normalize-fn-code ns-refs f) opts)))

(s/defn annotate-fn
  [ns-refs dict f]
  (->> f (normalize-fn-code ns-refs) (attach-schema-info dict)))

(defmacro block-in-ns
  [ns & body]
  `(let [ns-dec# (read-string (schematize/source-clj ~ns))
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

(defn ns-exprs
  [ns]
  (let [code (read-string (str "'(" (schematize/source-clj ns) ")"))]
    (->> code (mapv (partial schematize/resolve-all (ns-map ns))))))
;; TODO: dropping initial `ns` block as it isn't relevant to type-checking and complicates matters,
;; but we should add it back in for checking

(defmacro annotate-ns
  ([ns]
   `(annotate-ns (schematize/ns-schemas ~ns) ~ns))
  ([dict ns]
   `(block-in-ns ~ns (mapcat #(attach-schema-info ~dict %) (ns-exprs ~ns)))))

;; TODO: if unparseable, throws error
;; Should either pass that on, or (ideally) localize it to a single s-expr and flag that
(defmacro check-ns
  ([ns]
   `(check-ns ~ns {}))
  ([ns opts]
   `(check-ns (schematize/ns-schemas ~ns) ~ns ~opts))
  ([dict ns opts]
   `(block-in-ns ~ns
                 (mapcat #(check-s-expr ~dict {} % ~opts)
                         (ns-exprs ~ns)))))
