(ns skeptic.inconsistence.path
  (:require [schema.core :as s]
            [clojure.string :as str]
            [skeptic.colours :as colours]
            [skeptic.inconsistence.display :as disp]))

(s/defschema ErrorMsgCtx
  {:expr s/Any
   :arg s/Any
   s/Keyword s/Any})

(def ^:private pretty-type-threshold 80)

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

(defn plain-key
  [k]
  (cond-> k
    (s/optional-key? k)
    :k))

(defn superfluous-cast-key
  [actual-key]
  (let [pk (plain-key actual-key)]
    {:orig-key actual-key
     :cleaned-key pk}))

(defn simple-path-token
  [{:keys [kind key index]}]
  (case kind
    :map-key (disp/exact-key-form key)
    :vector-index index
    :seq-index index
    nil))

(defn render-path-segment
  [{:keys [kind key index member]}]
  (case kind
    :map-key (str "field " (or (disp/describe-item (disp/exact-key-form key))
                               (disp/describe-item key)
                               "unknown"))
    :vector-index (str "index " index)
    :seq-index (str "index " index)
    :set-member (str "set element " (or (disp/describe-item member) "unknown"))
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
  (or (disp/describe-item key)
      "Unknown"))

(defn missing-detail
  [path expected-key]
  (let [path-text (render-visible-path path)
        exact-key (disp/exact-key-form expected-key)
        key-text (key-description expected-key)]
    (cond
      (and path-text exact-key) (str path-text " is missing")
      path-text (str path-text " is missing required key matching " key-text)
      :else (str "missing required key matching " key-text))))

(defn unexpected-detail
  [report-kind path actual-key]
  (let [path-text (render-visible-path path)
        exact-key (disp/exact-key-form actual-key)
        key-text (key-description actual-key)
        suffix (if (= report-kind :output)
                 "not allowed by the declared schema"
                 "not allowed by the expected schema")]
    (cond
      path-text (str path-text " is " suffix)
      :else (str "disallowed key matching " key-text " is provided"))))

(defn nullable-detail
  [path actual-key expected-key]
  (let [path-text (render-visible-path path)
        exact-key (or (disp/exact-key-form actual-key)
                      (disp/exact-key-form expected-key))
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
  (let [path-text (render-visible-path path)
        source-text (disp/describe-type source-type)
        target-text (disp/describe-type target-type)]
    (if (or (> (count source-text) pretty-type-threshold)
            (> (count target-text) pretty-type-threshold))
      (str (if path-text
             (str path-text " has:")
             "has:")
           "\n\n"
           (disp/describe-type-block source-type)
           "\n\nbut expected:\n\n"
           (disp/describe-type-block target-type))
      (if path-text
        (str path-text " has "
             source-text
             " but expected "
             target-text)
        (str source-text
             " but expected "
             target-text)))))

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
      (str (disp/describe-type (first source-types))
           " does not match any of: "
           (str/join ", " (map disp/describe-type target-types))))))

(s/defn missing-key-message :- (s/maybe s/Str)
  [{:keys [expr arg]} :- ErrorMsgCtx
   missing :- #{s/Any}]
  (when (seq missing)
    (format "%s\n\tin\n\n%s\nhas missing keys:\n\n\t- %s"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) missing)))))

(s/defn nullable-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   nullables :- #{s/Any}]
  (when (seq nullables)
    (format "%s\n\tin\n\n%s\nin potentially nullable, but the schema doesn't allow that:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map disp/describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) nullables)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- ErrorMsgCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s\n\tin\n\n%s\nhas disallowed keys:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map disp/describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) actual-keys)))))
