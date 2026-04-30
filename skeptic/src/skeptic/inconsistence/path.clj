(ns skeptic.inconsistence.path
  (:require [schema.core :as s]
            [clojure.string :as str]
            [skeptic.colours :as colours]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.inconsistence.display :as disp]
            [skeptic.inconsistence.schema :as isch]))

(def ^:private pretty-type-threshold 80)


(s/defn plain-key :- s/Any
  [k :- s/Any]
  (cond-> k
    (s/optional-key? k)
    :k))

(s/defn superfluous-cast-key :- s/Any
  [actual-key :- s/Any]
  (let [pk (plain-key actual-key)]
    {:orig-key actual-key
     :cleaned-key pk}))

(s/defn simple-path-token :- s/Any
  [segment :- s/Any]
  (let [{:keys [kind key index]} segment]
    (case kind
      :map-key (disp/exact-key-form key)
      :vector-index index
      :seq-index index
      nil)))

(s/defn render-path-segment :- s/Str
  [segment :- s/Any]
  (let [{:keys [kind key index member]} segment]
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
               :member member}))))

(s/defn render-path :- (s/maybe s/Str)
  [path :- [s/Any]]
  (when (seq path)
    (let [simple-tokens (mapv simple-path-token path)]
      (if (every? some? simple-tokens)
        (pr-str simple-tokens)
        (str/join " -> " (map render-path-segment path))))))

(s/defn structural-path? :- s/Bool
  [path :- [s/Any]]
  (some #(contains? #{:map-key
                      :vector-index
                      :seq-index
                      :set-member
                      :function-domain
                      :function-range}
                    (:kind %))
        path))

(s/defn visible-path :- [s/Any]
  [path :- [s/Any]]
  (->> path
       (filter #(contains? #{:map-key
                             :vector-index
                             :seq-index
                             :set-member
                             :function-domain
                             :function-range}
                           (:kind %)))
       vec))

(s/defn render-visible-path :- (s/maybe s/Str)
  [path :- [s/Any]]
  (some-> path
          visible-path
          seq
          render-path))

(s/defn key-description :- s/Str
  ([key :- s/Any]
   (key-description key {}))
  ([key :- s/Any
    opts :- s/Any]
   (or (disp/describe-item key opts)
       "Unknown")))

(s/defn missing-detail :- s/Str
  ([path :- [s/Any]
    expected-key :- s/Any]
   (missing-detail path expected-key {}))
  ([path :- [s/Any]
    expected-key :- s/Any
    opts :- s/Any]
   (let [path-text (render-visible-path path)
         exact-key (disp/exact-key-form expected-key)
         key-text (key-description expected-key opts)]
     (cond
       (and path-text exact-key) (str path-text " is missing")
       path-text (str path-text " is missing required key matching " key-text)
       :else (str "missing required key matching " key-text)))))

(s/defn unexpected-detail :- s/Str
  ([report-kind :- s/Keyword
    path :- [s/Any]
    actual-key :- s/Any]
   (unexpected-detail report-kind path actual-key {}))
  ([report-kind :- s/Keyword
    path :- [s/Any]
    actual-key :- s/Any
    opts :- s/Any]
   (let [path-text (render-visible-path path)
         key-text (key-description actual-key opts)
         suffix (if (= report-kind :output)
                  "not allowed by the declared type"
                  "not allowed by the expected type")]
     (cond
       path-text (str path-text " is " suffix)
       :else (str "disallowed key matching " key-text " is provided")))))

(s/defn nullable-detail :- s/Str
  ([path :- [s/Any]
    actual-key :- s/Any
    expected-key :- s/Any]
   (nullable-detail path actual-key expected-key {}))
  ([path :- [s/Any]
    actual-key :- s/Any
    expected-key :- s/Any
    opts :- s/Any]
   (let [path-text (render-visible-path path)
         exact-key (or (disp/exact-key-form actual-key)
                       (disp/exact-key-form expected-key))
         key-text (key-description (or actual-key expected-key) opts)]
     (cond
       (and path-text exact-key)
       (str path-text " is potentially nullable, but the type doesn't allow that")

       path-text
       (str path-text " has key matching " key-text
            " that is potentially nullable, but the type doesn't allow that")

       :else
       (str "key matching " key-text
            " is potentially nullable, but the type doesn't allow that")))))

(s/defn mismatch-detail :- s/Str
  ([path :- [s/Any]
    source-type :- ats/SemanticType
    target-type :- ats/SemanticType]
   (mismatch-detail path source-type target-type {}))
  ([path :- [s/Any]
    source-type :- ats/SemanticType
    target-type :- ats/SemanticType
    opts :- s/Any]
   (let [path-text (render-visible-path path)
         source-text (disp/describe-type source-type opts)
         target-text (disp/describe-type target-type opts)]
     (if (or (> (count source-text) pretty-type-threshold)
             (> (count target-text) pretty-type-threshold))
       (str (if path-text
              (str path-text " has:")
              "has:")
            "\n\n"
            (disp/describe-type-block source-type opts)
            "\n\nbut expected:\n\n"
            (disp/describe-type-block target-type opts))
       (if path-text
         (str path-text " has "
              source-text
              " but expected "
              target-text)
         (str source-text
              " but expected "
              target-text))))))

(s/defn with-path-detail :- s/Str
  [message :- s/Str
   diagnostic :- s/Any]
  (if-let [path-text (render-visible-path (:path diagnostic))]
    (str message "\n\nPath:\n\n" (colours/yellow path-text))
    message))

(s/defn detail-line :- (s/maybe s/Str)
  ([report-kind :- s/Keyword
    diagnostic :- s/Any]
   (detail-line report-kind diagnostic {}))
  ([report-kind :- s/Keyword
    diagnostic :- s/Any
    opts :- s/Any]
   (let [{:keys [reason path actual-type expected-type actual-key expected-key]} diagnostic]
     (case reason
       :missing-key
       (missing-detail path expected-key opts)

       :nullable-key
       (nullable-detail path actual-key expected-key opts)

       :unexpected-key
       (unexpected-detail report-kind path actual-key opts)

       :nullable-source
       (if-let [path-text (render-visible-path path)]
         (str path-text " is nullable, but expected is not")
         "a nullable value was provided where the type requires a non-null value")

       (mismatch-detail path actual-type expected-type opts)))))

(s/defn union-alternatives-line :- (s/maybe s/Str)
  ([cast-results :- [s/Any]]
   (union-alternatives-line cast-results {}))
  ([cast-results :- [s/Any]
    opts :- s/Any]
   (let [without-visible-path (->> cast-results
                                   (remove #(some-> (:path %)
                                                    visible-path
                                                    seq))
                                   vec)
         actual-types (->> without-visible-path
                           (map :actual-type)
                           distinct
                           vec)
         expected-types (->> without-visible-path
                             (map :expected-type)
                             distinct
                             vec)]
     (when (and (seq without-visible-path)
                (= 1 (count actual-types))
                (> (count expected-types) 1))
       (str (disp/describe-type (first actual-types) opts)
            " does not match any of: "
            (str/join ", " (map #(disp/describe-type % opts) expected-types)))))))

(s/defn missing-key-message :- (s/maybe s/Str)
  [{:keys [expr arg]} :- isch/ReportCtx
   missing :- #{s/Any}]
  (when (seq missing)
    (format "%s\n\tin\n\n%s\nhas missing keys:\n\n\t- %s"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) missing)))))

(s/defn nullable-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- isch/ReportCtx
   nullables :- #{s/Any}]
  (when (seq nullables)
    (format "%s\n\tin\n\n%s\nin potentially nullable, but the type doesn't allow that:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map disp/describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) nullables)))))

(s/defn superfluous-key-message :- (s/maybe s/Str)
  [{:keys [expr arg expected-keys]} :- isch/ReportCtx
   actual-keys :- #{s/Any}]
  (when (seq actual-keys)
    (format "%s\n\tin\n\n%s\nhas disallowed keys:\n\n%s\nexpected, but\n\n\t- %s\nprovided\n"
            (colours/magenta (disp/ppr-str arg) true) (colours/magenta (disp/ppr-str expr))
            (colours/yellow (str "[" (str/join ", " (map disp/describe-item expected-keys)) "]"))
            (str/join "\n\t- " (mapv #(colours/yellow (disp/describe-item %)) actual-keys)))))
