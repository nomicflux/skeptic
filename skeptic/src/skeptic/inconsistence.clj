(ns skeptic.inconsistence
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.colours :as colours]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

(defn ppr-str
  [s]
  (with-out-str (pprint/pprint s)))

(defn public-ref-form
  [ref]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))

(declare user-type-form
         user-display-form)

(defn literal-form
  [value]
  (cond
    (or (nil? value)
        (keyword? value)
        (string? value)
        (integer? value)
        (boolean? value)
        (symbol? value))
    value

    (vector? value)
    (mapv literal-form value)

    (set? value)
    (into #{} (map literal-form) value)

    (and (map? value)
         (not (record? value))
         (not (contains? value as/semantic-type-tag-key)))
    (into {}
          (map (fn [[k v]]
                 [(literal-form k)
                  (literal-form v)]))
          value)

    :else nil))

(defn exact-key-form
  [key]
  (cond
    (and (map? key) (contains? key :cleaned-key))
    (exact-key-form (:cleaned-key key))

    (s/optional-key? key)
    (exact-key-form (:k key))

    :else
    (let [type (try
                 (as/schema->type key)
                 (catch Exception _e nil))]
      (cond
        (nil? type) nil
        (as/optional-key-type? type) (exact-key-form (:inner type))
        (as/value-type? type) (literal-form (:value type))
        :else nil))))

(defn format-user-form
  [form]
  (when (some? form)
    (pr-str form)))

(defn user-fn-input-form
  [method]
  (let [inputs (mapv user-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn user-type-form
  [type]
  (let [raw-type (or (when (and (map? type)
                                (contains? type as/semantic-type-tag-key)
                                (not (as/semantic-type-value? type)))
                         (or (:display-form type)
                             (some-> type :ref public-ref-form)
                             (some-> type :value literal-form)
                             'Unknown))
                       type)
        type (as/schema->type raw-type)]
    (cond
      (as/dyn-type? type) 'Any
      (as/bottom-type? type) 'Bottom
      (as/ground-type? type) (:display-form type)
      (as/refinement-type? type) (:display-form type)
      (as/adapter-leaf-type? type) (:display-form type)
      (as/optional-key-type? type) (user-type-form (:inner type))
      (as/value-type? type) (or (literal-form (:value type))
                                (user-type-form (:inner type)))
      (as/type-var-type? type) (:name type)
      (as/forall-type? type) (list 'forall (:binder type) (user-type-form (:body type)))
      (as/sealed-dyn-type? type) (list 'sealed (user-type-form (:ground type)))
      (as/fn-method-type? type) (list* '=>
                                      (user-type-form (:output type))
                                      (user-fn-input-form type))
      (as/fun-type? type)
      (if (= 1 (count (:methods type)))
        (user-type-form (first (:methods type)))
        (list* '=>* (map user-type-form (:methods type))))
      (as/maybe-type? type) (list 'maybe (user-type-form (:inner type)))
      (as/union-type? type) (list* 'union (map user-type-form (sort-by pr-str (:members type))))
      (as/intersection-type? type) (list* 'intersection (map user-type-form (sort-by pr-str (:members type))))
      (as/map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(user-type-form k)
                    (user-type-form v)]))
            (:entries type))
      (as/vector-type? type) (mapv user-type-form (:items type))
      (as/set-type? type) (into #{} (map user-type-form) (:members type))
      (as/seq-type? type) (doall (map user-type-form (:items type)))
      (as/var-type? type) (user-type-form (:inner type))
      (as/placeholder-type? type) (public-ref-form (:ref type))
      :else 'Unknown)))

(defn user-display-form
  [x]
  (cond
    (and (map? x) (contains? x :cleaned-key))
    (user-display-form (:cleaned-key x))

    (s/optional-key? x)
    (user-display-form (:k x))

    :else
    (user-type-form x)))

(defn describe-type
  [type]
  (format-user-form (user-type-form type)))

(defn describe-schema
  [x]
  (format-user-form (user-display-form x)))

(defn describe-item
  [x]
  (format-user-form (user-display-form x)))

(defn mismatched-nullable-msg
  [{:keys [expr arg]} _actual-type _expected-type]
  (format "%s\n\tin\n\n%s\nis nullable, but expected is not"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))))

(defn mismatched-ground-type-msg
  [{:keys [expr arg]} output-type expected-type]
  (format "%s\n\tin\n\n%s\nis a mismatched type:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-type output-type))
          (colours/yellow (describe-type expected-type))))

(defn mismatched-output-schema-msg
  [{:keys [expr arg]} output-schema expected-schema]
  (format "%s\n\tin\n\n%s\nhas output schema:\n\n%s\n\nbut declared return schema is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-type output-schema))
          (colours/yellow (describe-type expected-schema))))

(defn mismatched-schema-msg
  [{:keys [expr arg]} actual-type expected-type]
  (format "%s\n\tin\n\n%s\nhas incompatible schema:\n\n%s\n\nbut expected is:\n\n%s"
          (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
          (colours/yellow (describe-type actual-type))
          (colours/yellow (describe-type expected-type))))

(defn output-compatible-schemas
  [expected actual]
  [(as/canonicalize-output-schema expected)
   (as/canonicalize-output-schema actual)])

(defn unknown-output-schema?
  [schema]
  (as/unknown-schema? schema))

(declare output-cast-report
         report-summary
         cast-result->message
         plain-key
         missing-key-message
         nullable-key-message
         superfluous-key-message)

(defn cast-leaf-results
  ([cast-result]
   (cast-leaf-results cast-result []))
  ([cast-result parent-path]
   (let [path (into (vec parent-path) (or (:path cast-result) []))]
     (cond
       (or (nil? cast-result) (:ok? cast-result))
       []

       (and (seq (:children cast-result))
            (contains? #{:target-union
                         :source-union
                         :target-intersection
                         :source-intersection
                         :maybe-both
                         :maybe-target
                         :generalize
                         :instantiate
                         :function
                         :function-method
                         :map
                         :vector
                         :seq
                         :set}
                       (:rule cast-result)))
       (->> (:children cast-result)
            (mapcat #(cast-leaf-results % path))
            vec)

       :else
       [(assoc cast-result :path path)]))))

(defn primary-cast-failure
  [cast-result]
  (or (first (cast-leaf-results cast-result))
      cast-result))

(defn superfluous-cast-key
  [actual-key]
  (let [plain-key (plain-key actual-key)]
    {:orig-key actual-key
     :cleaned-key plain-key}))

(defn simple-path-token
  [{:keys [kind key index]}]
  (case kind
    :map-key (exact-key-form key)
    :vector-index index
    :seq-index index
    nil))

(defn render-path-segment
  [{:keys [kind key index member]}]
  (case kind
    :map-key (str "field " (or (describe-item (exact-key-form key))
                               (describe-item key)
                               "unknown"))
    :vector-index (str "index " index)
    :seq-index (str "index " index)
    :set-member (str "set element " (or (describe-item member) "unknown"))
    :function-domain (str "argument " (inc index))
    :function-range "return value"
    :maybe-value "non-nil value"
    :target-union-branch (str "target union branch " (inc index))
    :source-union-branch (str "source union branch " (inc index))
    :target-intersection-branch (str "target intersection branch " (inc index))
    :source-intersection-branch (str "source intersection branch " (inc index))
    (pr-str {:kind kind
             :key key
             :index index
             :member member})))

(defn render-path
  [path]
  (when (seq path)
    (let [simple-tokens (mapv simple-path-token path)]
      (if (every? some? simple-tokens)
        (pr-str simple-tokens)
        (str/join " -> " (map render-path-segment path))))))

(defn structural-path?
  [path]
  (some #(contains? #{:map-key
                      :vector-index
                      :seq-index
                      :set-member
                      :function-domain
                      :function-range}
                    (:kind %))
        path))

(defn visible-path
  [path]
  (->> path
       (filter #(contains? #{:map-key
                             :vector-index
                             :seq-index
                             :set-member
                             :function-domain
                             :function-range}
                           (:kind %)))
       vec))

(defn render-visible-path
  [path]
  (some-> path
          visible-path
          seq
          render-path))

(defn key-description
  [key]
  (or (describe-item key)
      "Unknown"))

(defn missing-detail
  [path expected-key]
  (let [path-text (render-visible-path path)
        exact-key (exact-key-form expected-key)
        key-text (key-description expected-key)]
    (cond
      (and path-text exact-key) (str path-text " is missing")
      path-text (str path-text " is missing required key matching " key-text)
      :else (str "missing required key matching " key-text))))

(defn unexpected-detail
  [report-kind path actual-key]
  (let [path-text (render-visible-path path)
        exact-key (exact-key-form actual-key)
        key-text (key-description actual-key)
        suffix (if (= report-kind :output)
                 "not allowed by the declared schema"
                 "not allowed by the expected schema")]
    (cond
      (and path-text exact-key) (str path-text " is " suffix)
      path-text (str path-text " contains disallowed key matching " key-text)
      :else (str "disallowed key matching " key-text " is provided"))))

(defn nullable-detail
  [path actual-key expected-key]
  (let [path-text (render-visible-path path)
        exact-key (or (exact-key-form actual-key)
                      (exact-key-form expected-key))
        key-text (key-description (or actual-key expected-key))]
    (cond
      (and path-text exact-key)
      (str path-text " is potentially nullable, but the schema doesn't allow that")

      path-text
      (str path-text " has key matching " key-text
           " that is potentially nullable, but the schema doesn't allow that")

      :else
      (str "key matching " key-text
           " is potentially nullable, but the schema doesn't allow that"))))

(defn mismatch-detail
  [path source-type target-type]
  (if-let [path-text (render-visible-path path)]
    (str path-text " has "
         (describe-type source-type)
         " but expected "
         (describe-type target-type))
    (str (describe-type source-type)
         " but expected "
         (describe-type target-type))))

(defn with-path-detail
  [message cast-result]
  (if-let [path-text (render-visible-path (:path cast-result))]
    (str message "\n\nPath:\n\n" (colours/yellow path-text))
    message))

(defn detail-line
  [report-kind {:keys [reason path source-type target-type actual-key expected-key]}]
  (case reason
    :missing-key
    (missing-detail path expected-key)

    :nullable-key
    (nullable-detail path actual-key expected-key)

    :unexpected-key
    (unexpected-detail report-kind path actual-key)

    :nullable-source
    (if-let [path-text (render-visible-path path)]
      (str path-text " is nullable, but expected is not")
      "a nullable value was provided where the schema requires a non-null value")

    (mismatch-detail path source-type target-type)))

(defn union-alternatives-line
  [cast-results]
  (let [without-visible-path (->> cast-results
                                  (remove #(some-> (:path %)
                                                   visible-path
                                                   seq))
                                  vec)
        source-types (->> without-visible-path
                          (map :source-type)
                          distinct
                          vec)
        target-types (->> without-visible-path
                          (map :target-type)
                          distinct
                          vec)]
    (when (and (seq without-visible-path)
               (= 1 (count source-types))
               (> (count target-types) 1))
      (str (describe-type (first source-types))
           " does not match any of: "
           (str/join ", " (map describe-type target-types))))))

(defn input-summary-header
  [{:keys [focuses blame]}]
  (let [arg (if (= 1 (count focuses))
              (first focuses)
              blame)]
    (format "%s\n\tin\n\n%s\nhas incompatible schema:"
            (colours/magenta (ppr-str arg) true)
            (colours/magenta (ppr-str blame)))))

(defn combine-summary-lines
  [header label detail-lines]
  (str header
       "\n\n"
       label
       ":\n\n\t- "
       (str/join "\n\t- " (map #(colours/yellow %) detail-lines))))

(defn report-ctx
  [{:keys [blame focuses]}]
  {:expr blame
   :arg (if (= 1 (count focuses))
          (first focuses)
          blame)})

(defn output-summary-message
  [{:keys [cast-result actual-type expected-type] :as report}]
  (let [actual-type (or (some-> cast-result :source-type) actual-type)
        expected-type (or (some-> cast-result :target-type) expected-type)]
    (mismatched-output-schema-msg (report-ctx report)
                                  actual-type
                                  expected-type)))

(defn rebuilt-leaf-errors
  [report]
  (->> (:cast-results report)
       (map #(cast-result->message (report-ctx report) %))
       distinct
       vec))

(defn summarize-errors
  [{:keys [report-kind cast-results] :as report}]
  (case report-kind
    :output
    (let [detail-lines (->> cast-results
                            (keep #(detail-line :output %))
                            distinct
                            vec)
          summary (output-summary-message report)]
      [(if (seq detail-lines)
         (combine-summary-lines summary "Problem fields" detail-lines)
         summary)])

    :input
    (let [detail-lines (->> cast-results
                            (keep #(detail-line :input %))
                            distinct
                            vec)
          union-line (union-alternatives-line cast-results)
          detail-lines (cond-> detail-lines
                         union-line (conj union-line))
          leaf-errors (rebuilt-leaf-errors report)]
      (cond
        (seq detail-lines)
        [(combine-summary-lines (input-summary-header report) "Problems" detail-lines)]

        (seq leaf-errors)
        [(first leaf-errors)]

        :else
        []))

    (rebuilt-leaf-errors report)))

(defn display-cast
  [{:keys [rule actual-type expected-type cast-result]}]
  (let [rule (or (:rule cast-result) rule)
        actual-type (or (:source-type cast-result) actual-type)
        expected-type (or (:target-type cast-result) expected-type)]
    {:rule rule
     :rule-text (some-> rule name)
     :actual-type actual-type
     :expected-type expected-type
     :actual-type-text (describe-type actual-type)
     :expected-type-text (describe-type expected-type)}))

(defn report-summary
  [{:keys [location blame-side blame-polarity source-expression blame
           focus-sources focuses enclosing-form expanded-expression]
    :as report}]
  (merge {:location location
          :blame-side blame-side
          :blame-polarity blame-polarity
          :source-expression source-expression
          :blame blame
          :focus-sources focus-sources
          :focuses focuses
          :enclosing-form enclosing-form
          :expanded-expression expanded-expression
          :errors (summarize-errors report)}
         (display-cast report)))

(defn cast-result->message
  [ctx cast-result]
  (let [source-type (:source-type cast-result)
        target-type (:target-type cast-result)
        message (case (:reason cast-result)
                  :is-tamper
                  (format "%s\n\tin\n\n%s\nattempts to inspect a sealed value:\n\n%s"
                          (colours/magenta (ppr-str (:arg ctx)) true)
                          (colours/magenta (ppr-str (:expr ctx)))
                          (colours/yellow (describe-type source-type)))

                  :nu-tamper
                  (format "%s\n\tin\n\n%s\nattempts to move a sealed value out of scope:\n\n%s"
                          (colours/magenta (ppr-str (:arg ctx)) true)
                          (colours/magenta (ppr-str (:expr ctx)))
                          (colours/yellow (describe-type source-type)))

                  :nullable-source
                  (mismatched-nullable-msg ctx source-type target-type)

                  :missing-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (ppr-str (:arg ctx)) true)
                          (colours/magenta (ppr-str (:expr ctx)))
                          (colours/yellow (missing-detail (:path cast-result)
                                                         (:expected-key cast-result))))

                  :nullable-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (ppr-str (:arg ctx)) true)
                          (colours/magenta (ppr-str (:expr ctx)))
                          (colours/yellow (nullable-detail (:path cast-result)
                                                          (:actual-key cast-result)
                                                          (:expected-key cast-result))))

                  :unexpected-key
                  (format "%s\n\tin\n\n%s\n%s"
                          (colours/magenta (ppr-str (:arg ctx)) true)
                          (colours/magenta (ppr-str (:expr ctx)))
                          (colours/yellow (unexpected-detail :input
                                                            (:path cast-result)
                                                            (:actual-key cast-result))))

                  (if (and (as/ground-type? source-type)
                           (as/ground-type? target-type)
                           (not= source-type target-type))
                    (mismatched-ground-type-msg ctx source-type target-type)
                    (mismatched-schema-msg ctx source-type target-type)))]
    (if (contains? #{:missing-key :nullable-key :unexpected-key} (:reason cast-result))
      message
      (with-path-detail message cast-result))))

(defn cast-report-metadata
  [cast-result]
  (let [primary (primary-cast-failure cast-result)]
    {:cast-result cast-result
     :cast-results (vec (cast-leaf-results cast-result))
     :blame-side (:blame-side primary)
     :blame-polarity (:blame-polarity primary)
     :rule (:rule primary)
     :expected-type (:target-type primary)
     :actual-type (:source-type primary)}))

(defn cast-report
  [ctx expected actual]
  (let [expected (as/canonicalize-schema expected)
        actual (as/canonicalize-schema actual)
        cast-result (as/check-cast (as/schema->type actual)
                                   (as/schema->type expected))]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (let [errors (->> (cast-leaf-results cast-result)
                        (map #(cast-result->message ctx %))
                        distinct
                        vec)]
        (merge {:ok? false
                :errors errors}
               (cast-report-metadata cast-result))))))

(defn output-cast-report
  [ctx expected actual]
  (let [[expected actual] (output-compatible-schemas expected actual)
        cast-result (as/check-cast (as/schema->type actual)
                                   (as/schema->type expected))]
    (if (:ok? cast-result)
      {:ok? true
       :errors []
       :cast-result cast-result
       :cast-results []
       :blame-side :none
       :blame-polarity :none
       :rule (:rule cast-result)
       :expected-type (:target-type cast-result)
       :actual-type (:source-type cast-result)}
      (merge {:ok? false
              :errors [(mismatched-output-schema-msg ctx actual expected)]}
             (cast-report-metadata cast-result)))))

(s/defn missing-key-message :- (s/maybe s/Str)
  [{:keys [expr arg]} :- ErrorMsgCtx
   missing :- #{s/Any}]
  (when (seq missing)
    (format "%s\n\tin\n\n%s\nhas missing keys:\n\n\t- %s"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (describe-item %)) missing)))))

(s/defn nullable-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   nullables :- #{s/Any}]
  (when (seq nullables)
    (format "%s\n\tin\n\n%s\nin potentially nullable, but the schema doesn't allow that:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (describe-item %)) nullables)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s\n\tin\n\n%s\nhas disallowed keys:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (ppr-str arg) true) (colours/magenta (ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (describe-item %)) actual-keys)))))

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))
