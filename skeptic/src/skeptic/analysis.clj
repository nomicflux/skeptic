(ns skeptic.analysis
  (:require [schema.core :as s]
            [skeptic.schematize :as schematize]
            [skeptic.analysis.schema :as analysis-schema]
            [skeptic.analysis.pred :as analysis-pred]
            [skeptic.analysis.resolvers :as analysis-resolvers]
            [plumbing.core :as p]
            [clojure.walk :as walk]))

;; TODO: Switch to tools.analyzer to avoid macroexpansion errors

;; TODO: infer function types from actually applied args, if none given
;; TODO: representation for cast values instead of just static types (such as dynamic functions cast to actual arg types)
;; TODO: also, infer outputs from what is actually output
;; TODO: gather schema info for internal functions
;; TODO: check that function output matches schema
;; TODO: how to go from macroexpanded version to line in code?
;; TODO: handle `new`

;; TODO: keywords & other values in function position
;; TODO: atoms? refs? reader macros?

;; TODO: def & defn need to add to a global set of symbols
;; TODO: need support for for, doseq, doall

;; TODO: Global vars from def

(defn push-down-info
  [{:keys [local-vars name path] :or {local-vars {} path []} :as _parent} child]
  (-> child
      (update :local-vars
              #(merge local-vars %))
      (assoc :path
             (if name (conj path name) path))))

(defn annotate-expr
  [expr]
  (walk/postwalk (let [n (atom 0)]
                   (fn [f]
                     (let [idx (swap! n inc)]
                       (cond
                         (instance? clojure.lang.IMapEntry f)
                         {idx {:expr f :idx idx}}

                         (map? f)
                         {:expr (vec (vals f)) :idx idx :map? true}

                         :else
                         {:expr f :idx idx}))))
                 expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk (fn [el] (if (and (map? el) (contains? el :expr))
                           (:expr (if (:map? el) (into {} el) el))
                           el))
                 expr))

(s/defn analyse-let :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this} :- analysis-schema/AnnotatedExpression]
  ;; A block like `(-> cache (doto set-cache-value))` macroexpands to
  ;; `(let* cache [G__125245 set-cache-value] G__125245)`, with an odd
  ;; element between `let*` and the vector. This shouldn't be legal, the Java Clojure compiler
  ;; says it's not legal, the REPL says it's not legal, but that's what we get and
  ;; what we need to analyse.
  (let [[letblock & body] (->> expr (drop-while (complement (comp vector? :expr))))
        letpairs (partition 2 (:expr letblock))

        {:keys [let-clauses local-vars]}
        (reduce (fn [{:keys [let-clauses local-vars]}
                    [newvar varbody]]
                  (let [clause (push-down-info this
                                               (assoc varbody
                                                      :local-vars local-vars
                                                      :name (:expr newvar)))]
                    {:local-vars (cond
                                   (symbol? (:expr newvar))
                                   (assoc local-vars
                                         (:expr newvar)
                                         {::analysis-resolvers/placeholder (:idx varbody)})

                                   (vector? (:expr newvar))
                                   local-vars ;; TODO: implement vector destructuring

                                   (map? (:expr newvar))
                                   local-vars ;; TODO: implement map destructuring
                                   )
                     :let-clauses (conj let-clauses clause)}))
                {:let-clauses []
                 :local-vars local-vars}
                letpairs)

        body-clauses
        (map (partial push-down-info (assoc this
                                            :local-vars local-vars))
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::analysis-resolvers/placeholder (:idx output-clause)}
                              :dep-callback analysis-resolvers/resolve-schema)]
    (concat let-clauses body-clauses [current-clause])))

(s/defn analyse-def :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr path] :or {path []} :as this} :- analysis-schema/AnnotatedExpression]
  (let [[name & body] (->> expr (drop 1))

        with-name (assoc this
                         :name (:expr name))

        body-clauses
        (map (partial push-down-info with-name) body)

        output-clause (last body-clauses)
        current-clause (assoc with-name
                              :path (conj path (:expr name))
                              :schema {::analysis-resolvers/placeholder (:idx output-clause)}
                              :dep-callback analysis-resolvers/resolve-def-schema)]
    (concat body-clauses [current-clause])))

(s/defn analyse-if :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr] :as this} :- analysis-schema/AnnotatedExpression]
  (let [[_ if-clause t-clause f-clause] expr

        body-clauses
        (map (partial push-down-info this)
             (remove nil? [if-clause t-clause f-clause]))

        current-clause (assoc this
                              :schema {::analysis-resolvers/placeholders [(:idx t-clause) (:idx f-clause)]}
                              :dep-callback analysis-resolvers/resolve-if-schema)]
    (concat body-clauses [current-clause])))

(s/defn analyse-try :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr] :as this} :- analysis-schema/AnnotatedExpression]
  (let [[_ & body] expr
        [try-body after-body] (split-with (fn [{:keys [expr]}] (not (or (analysis-pred/catch? expr) (analysis-pred/finally? expr)))) body)
        [catch-body finally-body] (split-with (fn [{:keys [expr]}] (not (analysis-pred/finally? expr))) after-body)

        try-clauses (mapv (partial push-down-info this) try-body)

        catch-clauses (->> catch-body first :expr (drop 3) (mapv (partial push-down-info this)))
        finally-clauses (->> finally-body first :expr (drop 1) (mapv (partial push-down-info this)))
        current-clause (assoc this
                              :schema {::analysis-resolvers/placeholder (:idx (last try-clauses))}
                              :dep-callback analysis-resolvers/resolve-schema)]
    (concat try-clauses catch-clauses finally-clauses [current-clause])))

(s/defn analyse-throw :- analysis-schema/AnnotatedExpression
  [this :- analysis-schema/AnnotatedExpression]
  (assoc this
         :schema analysis-schema/Bottom))

(s/defn analyse-do :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr] :as this} :- analysis-schema/AnnotatedExpression]
  (let [body (->> expr (drop 1))

        body-clauses
        (map (partial push-down-info this)
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::analysis-resolvers/placeholder (:idx output-clause)}
                              :dep-callback analysis-resolvers/resolve-schema)]
    (concat body-clauses [current-clause])))

(s/defn analyse-fn :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this} :- analysis-schema/AnnotatedExpression]
  (let [body-clauses
        (->> expr
             (drop 1)
             (map (fn [fn-expr]
                    (let [[vars & body] (:expr fn-expr)
                          {:keys [count args with-varargs varargs]} (->> vars :expr (map :expr) schematize/arg-list)
                          args (filter symbol? args) ;; TODO: destructuring
                          fn-vars (p/map-from-keys (fn [k] {:expr k :name k :schema s/Any}) args)
                          ;; TODO: varargs
                          ;; fn-vars (cond-> fn-vars with-varargs (assoc varargs {:expr varargs
                          ;;                                                      :name (str varargs)
                          ;;                                                     :schema [s/Any]}))
                          clauses (map (partial push-down-info
                                                (assoc this :local-vars (merge local-vars fn-vars)))
                                       body)]
                      {:clauses clauses
                       :expr fn-expr
                       :output-placeholder (-> clauses last :idx)
                       :arglists (if with-varargs
                                   {:varargs {:arglist (conj args varargs)
                                              :count count
                                              :schema (conj (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)
                                                            s/Any)}}
                                   {count {:arglist args
                                           :count count
                                           :schema (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)}})}))))

        all-body-clauses (mapcat :clauses body-clauses)
        full-arglist (reduce merge {} (map :arglists body-clauses))
        full-output (map :output-placeholder body-clauses)
        current-clause (assoc this
                              :dep-callback analysis-resolvers/resolve-fn-outputs
                              :output {::analysis-resolvers/placeholders full-output}
                              :schema {::analysis-resolvers/arglists full-arglist}
                              :arglists full-arglist)]
    (concat all-body-clauses
            [current-clause])))

(s/defn analyse-fn-once :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr local-vars]
    :or {local-vars {}} :as this} :- analysis-schema/AnnotatedExpression]
  (let [[_ vars & body] expr
        {:keys [count args with-varargs varargs]} (->> vars :expr (map :expr) schematize/arg-list)
        fn-vars (p/map-from-keys (fn [k] {:expr k :name k :schema s/Any}) args)
        fn-vars (cond-> fn-vars with-varargs (assoc varargs {:expr varargs
                                                             :name varargs
                                                             :schema [s/Any]}))
        clauses (map (partial push-down-info
                              (assoc this :local-vars (merge local-vars fn-vars)))
                     body)

        arglist (if with-varargs
                  {:varargs {:arglist (conj args varargs)
                             :count count
                             :schema (conj (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)
                                           s/Any)}}
                  {count {:arglist args
                          :count count
                          :schema (map (fn [arg] {:schema s/Any :optional? false :name arg}) args)}})

        current-clause (assoc this
                              :dep-callback analysis-resolvers/resolve-fn-once-outputs
                              :output {::analysis-resolvers/placeholder (-> clauses last :idx)}
                              :schema {::analysis-resolvers/arglist arglist}
                              :arglists arglist)]
    (concat clauses
            [current-clause])))

(s/defn analyse-application :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr] :as this}] :- analysis-schema/AnnotatedExpression
  (let [[f & args] expr

        arg-clauses
        (map (partial push-down-info this) args)

        fn-clause
        (assoc (push-down-info this f)
               :args (map :idx args)
               :fn-position? true)]
    (concat arg-clauses
            [fn-clause
             (assoc this
                    :actual-arglist {::analysis-resolvers/placeholders (map :idx arg-clauses)}
                    :expected-arglist {::analysis-resolvers/placeholder (:idx fn-clause)}
                    :schema {::analysis-resolvers/placeholder (:idx fn-clause)}
                    :dep-callback analysis-resolvers/resolve-application-schema)])))

(s/defn analyse-coll :- [analysis-schema/AnnotatedExpression]
  [{:keys [expr map?] :as this} :- analysis-schema/AnnotatedExpression]
  (cond
    ;; As the postwalk rewrites things into maps, maps themselves need special annotation processing
    map? (concat (mapcat (comp (partial map (partial push-down-info this)) :expr) expr)
                 [(assoc this
                         :dep-callback (analysis-resolvers/resolve-map-schema (fn [key-schemas val-schemas]
                                                                                {(analysis-schema/schema-join key-schemas)
                                                                                 (analysis-schema/schema-join val-schemas)}))
                         :schema {::analysis-resolvers/key-placeholders (->> expr (map (comp :idx first :expr)))
                                  ::analysis-resolvers/val-placeholders (->> expr (map (comp :idx second :expr)))})])
    (vector? expr) (concat (map (partial push-down-info this) expr)
                           [(assoc this
                                   :dep-callback (analysis-resolvers/resolve-coll-schema (fn [schemas] (vector (analysis-schema/schema-join schemas))))
                                   :schema {::analysis-resolvers/placeholders (map :idx expr)})])
    (set? expr) (concat (map (partial push-down-info this) expr)
                        [(assoc this
                                :dep-callback (analysis-resolvers/resolve-coll-schema (fn [schemas] #{(analysis-schema/schema-join schemas)}))
                                :schema {::analysis-resolvers/placeholders (map :idx expr)})])
    (or (list? expr) (seq? expr)) (concat (map (partial push-down-info this) expr)
                                          [(assoc this
                                                  :dep-callback (analysis-resolvers/resolve-coll-schema (fn [schemas] (vector (analysis-schema/schema-join schemas))))
                                                  :schema {::analysis-resolvers/placeholders (map :idx expr)})])))

(s/defn local-lookup
  [results
   local-vars
   sym]
  (let [lookup (get local-vars sym)
        placeholder (::analysis-resolvers/placeholder lookup)]
    (if placeholder
      (get results placeholder)
      lookup)))

;; TODO: add keywords/maps/vectors used as functions
;; TODO: add `.`, `new`, other Java-specific things
(s/defn analyse-function :- [analysis-schema/AnnotatedExpression]
  [{:keys [fn-position? args idx] :as this} :- analysis-schema/AnnotatedExpression]
  (assert fn-position? "Must be in function position to analyse as a function")
  (assert (not (nil? args)) "A function must have a list of args")
  [(dissoc this :fn-position?)
   (assoc this
          :dep-callback analysis-resolvers/resolve-fn-position
          :arglist {::analysis-resolvers/placeholder [idx args]}
          :schema {::analysis-resolvers/placeholder [idx args]})])

(s/defn analyse-symbol :- analysis-schema/AnnotatedExpression
  [dict
   results
   {:keys [expr local-vars fn-position? args]
    :or {local-vars {}} :as this} :- analysis-schema/AnnotatedExpression]
  (assert (symbol? expr) "Expr should be a symbol; use analyse-value for other basic types")
  (let [default-schema (if fn-position? (analysis-schema/dynamic-fn-schema (count args) s/Any) s/Any)
        lookup (or (local-lookup results local-vars expr)
                   (get dict expr)
                   {:schema default-schema})]
    (merge this
           (select-keys lookup [:schema :output :arglists]))))

(s/defn analyse-value :- {(s/optional-key :finished) analysis-schema/AnnotatedExpression
                          (s/optional-key :enqueue) [analysis-schema/AnnotatedExpression]}
  [{:keys [expr fn-position?] :as this} :- analysis-schema/AnnotatedExpression]
  (assert (not fn-position?) "Functions should be analysed with analyse-function")
  (assert (not (symbol? expr)) "Symbols should be looked up with analyse-symbol")
  (if (var? expr)
    {:enqueue [(assoc this
                      :expr (or @expr (symbol expr)))]}
    {:finished (assoc this
                      :schema (cond
                                (nil? expr) (s/maybe s/Any)
                                (int? expr) s/Int
                                (string? expr) s/Str
                                (keyword? expr) s/Keyword
                                (boolean? expr) s/Bool
                                :else (class expr)))}))

(defmacro report-error
  [f]
  `(try ~f
       (catch Exception e#
         (println "Error analysing expression")
         (println (unannotate-expr ~(last f)))
         (println "---")
         (println (str ~(last f)))
         (println "---")
         (throw e#))))

(defn attach-schema-info-loop
  [dict
   expr]
  (loop [expr-stack [(annotate-expr expr)]
         results {}]
    (if (empty? expr-stack)
      results

      (let [{:keys [dep-callback] :as next} (first expr-stack)
            rest-stack (rest expr-stack)
            {:keys [expr idx fn-position? finished?] :as this} (if dep-callback (dep-callback results (dissoc next :dep-callback)) next)]
        (cond
          finished?
          (recur rest-stack (assoc results idx this))

          fn-position?
          (recur (concat (report-error (analyse-function this)) rest-stack) results)

          (or (not (seq? expr)) (and (seq? expr) (empty? expr)))
          (cond
            (coll? expr)
            (recur (concat (report-error (analyse-coll this)) rest-stack) results)

            (symbol? expr)
            (recur rest-stack (assoc results idx (report-error (analyse-symbol dict results this))))

            :else
            (let [{:keys [finished enqueue]} (report-error (analyse-value this))]
              (recur (concat enqueue (or rest-stack []))
                     (if finished
                       (assoc results idx finished)
                       results))))

          (analysis-pred/def? expr)
          (recur (concat (report-error (analyse-def this)) rest-stack)
                 results)

          (analysis-pred/do? expr)
          (recur (concat (report-error (analyse-do this)) rest-stack)
                 results)

          (analysis-pred/fn-expr? expr)
          (recur (concat (report-error (analyse-fn this)) rest-stack)
                 results)

          (analysis-pred/fn-once? expr)
          (recur (concat (report-error (analyse-fn-once this)) rest-stack)
                 results)

          (analysis-pred/if? expr)
          (recur (concat (report-error (analyse-if this)) rest-stack)
                 results)

          (analysis-pred/try? expr)
          (recur (concat (report-error (analyse-try this)) rest-stack)
                 results)

          (analysis-pred/throw? expr)
          (recur rest-stack
                 (assoc results idx (report-error (analyse-throw this))))

          (or (analysis-pred/loop? expr) (analysis-pred/let? expr))
          (recur (concat (report-error (analyse-let this)) rest-stack)
                 results)

          (and (seq? expr) (not (empty? expr)))
          (recur (concat (report-error (analyse-application this)) rest-stack)
                 results)

          :else
          (throw (ex-info "Unknown expression type" this)))))))

;; TODO: do some-> blocks work correctly? Will they check correctly if a function requires a non-nil argument?
;; TODO: make sure `doto` and `cond->` blocks work appropriately; something introduces an element between `let*` and its vector of assignments
