(ns skeptic.analysis.resolvers
  (:require [schema.core :as s]
            [skeptic.analysis.schema :as analysis-schema]
            [plumbing.core :as p]))

(defn arglist->input-schema
  [{:keys [schema name] :as s}]
  (s/one (or schema s s/Any) name))

(defn convert-arglists
  [args {:keys [arglists output]}]
  (let [arity (count args)
        direct-res (get arglists arity)
        {:keys [count] :as varargs-res} (get arglists :varargs)]
    (if (or (and count varargs-res)
            direct-res)
      (let [res (if (and count (>= arity count)) varargs-res direct-res)
            schemas (mapv arglist->input-schema
                          (or (:schema res)
                              (vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))))
            arglist (mapv :schema schemas)]
        {:schema (if (and output (seq schemas))
                   (s/make-fn-schema output [schemas])
                   (analysis-schema/dynamic-fn-schema arity output))
         :output (or output s/Any)
         :arglist arglist})
      (let [schema (analysis-schema/dynamic-fn-schema arity output)]
        {:schema schema
         :output (or output s/Any)}))))

(defn resolve-map-schema
  [schema-fn]
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (let [key-idxs (::key-placeholders v)
                        val-idxs (::val-placeholders v)]
                    (schema-fn (->> key-idxs (map (comp :schema (partial get results))))
                               (->> val-idxs (map (comp :schema (partial get results))))) )))
        (assoc :finished? true))))

(defn resolve-coll-schema
  [schema-fn]
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idxs (::placeholders v)]
                    (schema-fn (->> idxs (map (comp :schema (partial get results)))))
                    v)))
        (assoc :finished? true))))

(def resolve-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (:schema (get results idx))
                    v)))
        (assoc :finished? true))))

(def resolve-local-vars
  (fn [results el]
    (if (contains? el :local-vars)
      (update el
              :local-vars
              (fn [lvs]
                (p/map-vals (fn [v]
                              (if-let [idx (::placeholder v)]
                                (select-keys (get results idx) [:schema :output :arglists])
                                v))
                            lvs)))
      el)))

(def resolve-def-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (analysis-schema/variable (:schema (get results idx)))
                    v)))
        (assoc :finished? true))))

(def resolve-if-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [[t-idx f-idx] (::placeholders v)]
                    (let [t-el (get results t-idx)
                          f-el (get results f-idx)]
                      (analysis-schema/schema-join (set [(:schema t-el) (:schema f-el)])))
                    v)))
        (assoc :finished? true))))

(def resolve-application-schema
  (fn [results el]
    (-> el
        (update :schema
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (:output (get results idx))
                    v)))
        (update :actual-arglist
                (fn [v]
                  (if-let [idxs (::placeholders v)]
                    (->> idxs
                         (map (partial get results))
                         (map :schema))
                    v)))
        (update :expected-arglist
                (fn [v]
                  (if-let [idx (::placeholder v)]
                    (->> idx
                         (get results idx)
                         :arglist)
                    v)))
        (assoc :finished? true))))

(def resolve-fn-outputs
  (fn [results el]
    (let [output-schemas (->> el :output ::placeholders
                              (map #(get results %))
                              (map :schema)
                              analysis-schema/schema-join)]
      (-> el
          (assoc :output output-schemas)
          (update :schema
                  (fn [v]
                    (if-let [arglists (::arglists v)]
                      (s/make-fn-schema output-schemas (->> arglists
                                                            vals
                                                            (mapv :schema)
                                                            (mapv (partial mapv arglist->input-schema))))
                      v)))
          (assoc :finished? true)))))

(def resolve-fn-once-outputs
  (fn [results el]
    (let [output-schema (->> el :output ::placeholder (get results) :schema)]
      (-> el
          (assoc :output output-schema)
          (update :schema
                  (fn [v]
                    (if-let [arglists (::arglists v)]
                      (s/make-fn-schema output-schema (->> arglists
                                                           vals
                                                           (mapv :schema)
                                                           (mapv (partial mapv arglist->input-schema))))
                      v)))
          (assoc :finished? true)))))

(def resolve-fn-position
  (fn [results el]
    (if-let [[idx args] (::placeholder (:schema el))]
      (let [ref (get results idx)
            with-arglists (convert-arglists (->> args (map (partial get results))) ref)]
        (-> el
            (merge with-arglists)
            (assoc :arglist (->> with-arglists :schema :input-schemas first (map :schema)))
            (assoc :finished? true)))
      el)))
