(ns skeptic.analysis
  (:require [schema.core :as s]
            [skeptic.schematize :as schematize]
            [skeptic.analysis.annotation :as aa]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.pred :as ap]
            [skeptic.analysis.resolvers :as ar]
            [plumbing.core :as p]
            [clojure.walk :as walk]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.passes.jvm.emit-form :as ana.ef]
            [clojure.pprint :as pprint]))

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

;; TODO: def & defn need to add to a global set of symbols?
;; TODO: need support for for, doseq, doall

;; TODO: Global vars from def

(defn push-down-info
  [{:keys [local-vars name path]
    :or {local-vars {} path []} :as parent}
   child]
  (if (vector? child)
    (mapv (partial push-down-info parent) child)
    (-> child
        ((fn [c] (pprint/pprint c) c))
        (update :local-vars
                #(merge local-vars %))
        (assoc :path
               (if (and name (not= (first path) name)) (conj path name) path)))))

(s/defn analyse-let :- [as/AnnotatedExpression]
  [{:keys [bindings body local-vars]
    :or {local-vars {}} :as this} :- as/AnnotatedExpression]
  ;; A block like `(-> cache (doto set-cache-value))` macroexpands to
  ;; `(let* cache [G__125245 set-cache-value] G__125245)`, with an odd
  ;; element between `let*` and the vector. This shouldn't be legal, the Java Clojure compiler
  ;; says it's not legal, the REPL says it's not legal, but that's what we get and
  ;; what we need to analyse.
  (let [{:keys [let-clauses local-vars]}
        (reduce (fn [{:keys [let-clauses local-vars]}
                    {:keys [init form] :as clause}]
                  (let [clause (push-down-info this
                                               (assoc clause
                                                      :local-vars local-vars
                                                      :name (:expr form)))]
                    {:local-vars (assoc local-vars
                                        (:expr form)
                                        {::ar/placeholder (:idx init)})
                     :let-clauses (conj let-clauses clause)}))
                {:let-clauses []
                 :local-vars local-vars}
                bindings)

        body-clauses
        (map (partial push-down-info (assoc this
                                            :local-vars local-vars))
             body)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::ar/placeholder (:idx output-clause)}
                              :dep-callback ar/resolve-schema)]
    (concat let-clauses body-clauses [current-clause])))

(s/defn analyse-def :- [as/AnnotatedExpression]
  [{:keys [expr path] :or {path []} :as this} :- as/AnnotatedExpression]
  (let [[name & body] (->> expr (drop 1))

        with-name (assoc this
                         :name (:expr name))

        body-clauses
        (map (comp
              #(assoc % :name (:expr name))
              (partial push-down-info with-name)) body)

        output-clause (last body-clauses)
        current-clause (assoc with-name
                              :path (conj path (:expr name))
                              :schema {::ar/placeholder (:idx output-clause)}
                              :dep-callback ar/resolve-def-schema)]
    (concat body-clauses [current-clause])))

(defn analyse-if
  [{:keys [test then else] :as this}]
  (let [body-clauses
        (map (partial push-down-info this)
             (remove nil? [test
                           (cond-> then
                             (symbol? (:expr test))
                             (update :local-vars
                                     (fn [lvs]
                                       (assoc lvs
                                              (:expr test)
                                              {::ar/placeholder (:idx test)
                                               ;; If if clause was true, it couldn't have been `nil`
                                               ::ar/transform-fn as/de-maybe}))))
                           else]))

        current-clause (assoc (dissoc this :children :test :then :else)
                              :schema {::ar/placeholders [(:idx then) (:idx else)]}
                              :dep-callback ar/resolve-if-schema)]
    (concat body-clauses [current-clause])))

(s/defn analyse-try :- [as/AnnotatedExpression]
  [{:keys [expr] :as this} :- as/AnnotatedExpression]
  (let [[_ & body] expr
        [try-body after-body] (split-with (fn [{:keys [expr]}] (not (or (ap/catch? expr) (ap/finally? expr)))) body)
        [catch-body finally-body] (split-with (fn [{:keys [expr]}] (not (ap/finally? expr))) after-body)

        try-clauses (mapv (partial push-down-info this) try-body)

        catch-clauses (->> catch-body first :expr (drop 3) (mapv (partial push-down-info this)))
        finally-clauses (->> finally-body first :expr (drop 1) (mapv (partial push-down-info this)))
        current-clause (assoc this
                              :schema {::ar/placeholder (:idx (last try-clauses))}
                              :dep-callback ar/resolve-schema)]
    (concat try-clauses catch-clauses finally-clauses [current-clause])))

(s/defn analyse-throw :- as/AnnotatedExpression
  [this :- as/AnnotatedExpression]
  (assoc this
         :schema as/Bottom))

(defn analyse-do
  [{:keys [statements] :as this}]
  (let [body-clauses
        (map (partial push-down-info this)
             statements)

        output-clause (last body-clauses)
        current-clause (assoc this
                              :schema {::ar/placeholder (:idx output-clause)}
                              :dep-callback ar/resolve-schema)]
    (concat body-clauses [current-clause])))

(defn zip-to-longest
  [f xs ys]
  (let [count (max (count xs) (count ys))
        xv (vec xs)
        yv (vec ys)]
    (for [n (range 0 count)]
      (f (get xv n nil) (get yv n nil)))))

(defn analyse-fn
  [dict
   {:keys [local-vars name methods]
    :or {local-vars {}} :as this}]
  (let [{:keys [arglists]} (get dict name)
        arglist-dict (p/map-vals (fn [{:keys [schema]}] (mapv (fn [{:keys [schema name]}] [name schema]) schema)) arglists)

        body-clauses
        (->> methods
             (map (fn [{:keys [body params] :as fn-expr}]
                    (pprint/pprint body)
                    (let [args (map :form params)
                          {:keys [count args with-varargs varargs]} (->> args schematize/arg-list)
                          args (filter symbol? args) ;; TODO: destructuring
                          vec-for-arity (get arglist-dict count)
                          fn-vars (into {} (zip-to-longest (fn [k [name schema]] [k {:expr k :name (or name k) :schema (or schema s/Any)}]) args vec-for-arity))
                          ;; TODO: varargs
                          ;; fn-vars (cond-> fn-vars with-varargs (assoc varargs {:expr varargs
                          ;;                                                      :name (str varargs)
                          ;;                                                      :schema [s/Any]}))
                          clauses (push-down-info
                                           (assoc this :local-vars (merge local-vars fn-vars))
                                           body)]
                      {:clauses clauses
                       :expr fn-expr
                       :output-placeholder (-> clauses last :idx)
                       :arglists (if with-varargs
                                   {:varargs {:arglist (conj args varargs)
                                              :count count
                                              :schema (conj (zip-to-longest (fn [arg [name schema]] {:schema (or schema s/Any) :optional? false :name (or name arg)}) args vec-for-arity)
                                                            s/Any)}}
                                   {count {:arglist args
                                           :count count
                                           :schema (zip-to-longest (fn [arg [name schema]] {:schema (or schema s/Any) :optional? false :name (or name arg)}) args vec-for-arity)}})}))))

        all-body-clauses (mapcat :clauses body-clauses)
        full-arglist (reduce merge {} (map :arglists body-clauses))
        full-output (map :output-placeholder body-clauses)
        current-clause (assoc this
                              :dep-callback ar/resolve-fn-outputs
                              :output {::ar/placeholders full-output}
                              :schema {::ar/arglists full-arglist}
                              :arglists full-arglist)]
    (concat all-body-clauses
            [current-clause])))

(s/defn analyse-static-call :- [as/AnnotatedExpression]
  [{:keys [args] :as this}] :- as/AnnotatedExpression
  (let [arg-clauses
        (map (partial push-down-info this) args)

        fn-clause
        (-> this
            (assoc
                :args (map :idx args)
                :fn-position? true)
            (dissoc :children :args))]
    (concat arg-clauses
            [fn-clause
             (assoc this
                    :actual-arglist {::ar/placeholders (map :idx arg-clauses)}
                    :expected-arglist {::ar/placeholder (:idx fn-clause)}
                    :schema {::ar/placeholder (:idx fn-clause)}
                    :dep-callback ar/resolve-application-schema)])))

(s/defn analyse-application :- [as/AnnotatedExpression]
  [{:keys [expr] :as this}] :- as/AnnotatedExpression
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
                    :actual-arglist {::ar/placeholders (map :idx arg-clauses)}
                    :expected-arglist {::ar/placeholder (:idx fn-clause)}
                    :schema {::ar/placeholder (:idx fn-clause)}
                    :dep-callback ar/resolve-application-schema)])))

(s/defn analyse-coll :- [as/AnnotatedExpression]
  [{:keys [expr map?] :as this} :- as/AnnotatedExpression]
  (cond
    ;; As the postwalk rewrites things into maps, maps themselves need special annotation processing
    map? (concat (mapcat (comp (partial map (partial push-down-info this)) :expr) expr)
                 [(assoc this
                         :dep-callback ar/resolve-map-schema
                         :schema {::ar/key-val-placeholders (->> expr (map (comp (partial map :idx) :expr)))})])
    (vector? expr) (concat (map (partial push-down-info this) expr)
                           [(assoc this
                                   :dep-callback (ar/resolve-coll-schema (fn [schemas] (vector (as/schema-join schemas))))
                                   :schema {::ar/placeholders (map :idx expr)})])
    (set? expr) (concat (map (partial push-down-info this) expr)
                        [(assoc this
                                :dep-callback (ar/resolve-coll-schema (fn [schemas] #{(as/schema-join schemas)}))
                                :schema {::ar/placeholders (map :idx expr)})])
    (or (list? expr) (seq? expr)) (concat (map (partial push-down-info this) expr)
                                          [(assoc this
                                                  :dep-callback (ar/resolve-coll-schema (fn [schemas] (vector (as/schema-join schemas))))
                                                  :schema {::ar/placeholders (map :idx expr)})])))

(s/defn local-lookup
  [results
   local-vars
   sym]
  (let [lookup (get local-vars sym)
        placeholder (::ar/placeholder lookup)]
    (if placeholder
      (get results placeholder)
      lookup)))

;; TODO: add keywords/maps/vectors used as functions
;; TODO: add `.`, `new`, other Java-specific things
(s/defn analyse-function :- [as/AnnotatedExpression]
  [{:keys [fn-position? args idx] :as this} :- as/AnnotatedExpression]
  (assert fn-position? "Must be in function position to analyse as a function")
  (assert (not (nil? args)) "A function must have a list of args")
  [(dissoc this :fn-position?)
   (assoc this
          :dep-callback ar/resolve-fn-position
          :arglist {::ar/placeholder [idx args]}
          :schema {::ar/placeholder [idx args]})])

(s/defn analyse-symbol :- as/AnnotatedExpression
  [dict
   results
   {:keys [expr local-vars fn-position? args]
    :or {local-vars {}} :as this} :- as/AnnotatedExpression]
  (assert (symbol? expr) "Expr should be a symbol; use analyse-value for other basic types")
  (let [default-schema (if fn-position? (as/dynamic-fn-schema (count args) s/Any) s/Any)
        lookup (or (local-lookup results local-vars expr)
                   (get dict expr)
                   {:schema default-schema})
        lookup (-> lookup
                   (update :expr (fn [e] (if e e expr)))
                   (update :schema (fn [s] (if s s s/Any))))]
    (merge (assoc this
                  :resolution-path (concat (get this :resolution-path [])
                                           (get lookup :resolution-path [])
                                           [(select-keys lookup [:idx :expr :schema])]))
           (select-keys lookup [:schema :output :arglists]))))

(s/defn analyse-value :- {(s/optional-key :finished) as/AnnotatedExpression
                          (s/optional-key :enqueue) [as/AnnotatedExpression]}
  [{:keys [expr fn-position?] :as this} :- as/AnnotatedExpression]
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
          (println (aa/unannotate-expr ~(last f)))
          (println "---")
          (println (str ~(last f)))
          (println "---")
          (throw e#))))

(defn attach-schema-info-loop
  [dict
   expr]
  (loop [expr-stack [(aa/idx-expression expr)]
         results {}]
    (if (empty? expr-stack)
      results

      (let [{:keys [dep-callback] :as next} (first expr-stack)
            rest-stack (rest expr-stack)
            {:keys [op type idx finished?] :as this} (ar/resolve-local-vars
                                                      results
                                                      (if dep-callback
                                                        (dep-callback results (dissoc next :dep-callback))
                                                        next))]
        (if finished?
          (recur rest-stack (assoc results idx this))
          (case op
           :binding nil
           :case nil
           :case-test nil
           :case-then nil
           :catch nil
           :const (case type
                    :vector (recur (concat (report-error (analyse-coll this)) rest-stack) results)
                    :map (recur (concat (report-error (analyse-coll this)) rest-stack) results)
                    :set (recur (concat (report-error (analyse-coll this)) rest-stack) results)
                    (recur (concat (report-error (analyse-value this)) rest-stack) results))
           :def (recur (concat (report-error (analyse-def this)) rest-stack) results)
           :deftype nil
           :do (recur (concat (report-error (analyse-do this)) rest-stack) results)
           :fn (recur (concat (report-error (analyse-fn dict this)) rest-stack) results)
           :fn-method nil
           :host-interop nil
           :if (recur (concat (report-error (analyse-if this)) rest-stack) results)
           :import nil
           :instance-call nil
           :instance-field nil
           :instance? nil
           :invoke nil
           :keyword-invoke nil
           :let (recur (concat (report-error (analyse-let this)) rest-stack) results)
           :letfn nil
           :local nil
           :loop nil
           ;;:map nil
           :method nil
           :monitor-enter nil
           :monitor-exit nil
           :new nil
           :prim-invoke nil
           :protocol-invoke nil
           :quote nil
           :recur nil
           :reify nil
           ;;:set nil
           :set! nil
           :static-call (recur (concat (report-error (analyse-static-call this)) rest-stack) results)
           :static-field nil
           :the-var nil
           :throw nil
           :try (recur (concat (report-error (analyse-try this)) rest-stack) results)
           :var nil
           ;;:vector nil
           :with-meta nil
           (throw (ex-info "Unknown op type:" this))))
        ))))

;; TODO: do some-> blocks work correctly? Will they check correctly if a function requires a non-nil argument?
;; TODO: make sure `doto` and `cond->` blocks work appropriately; something introduces an element between `let*` and its vector of assignments
