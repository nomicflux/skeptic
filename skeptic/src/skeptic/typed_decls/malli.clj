(ns skeptic.typed-decls.malli
  (:require [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.analysis.types :as at]
            [skeptic.malli-spec.collect :as mcollect]
            [skeptic.schema.collect :as scollect]))

(defn- synthetic-arg
  [idx input-type]
  {:name (symbol (str "arg" idx))
   :type input-type
   :optional? false})

(defn- method->arglist-entry
  [method]
  (let [inputs (at/fn-method-inputs method)
        types (vec (map-indexed synthetic-arg inputs))
        count (count inputs)
        arglist (mapv :name types)
        key (if (:variadic? method) :varargs count)]
    [key {:arglist arglist
          :count count
          :types types}]))

(defn- fun-type->entry-fields
  [t]
  (let [methods (at/fun-methods t)]
    {:output-type (at/fn-method-output (first methods))
     :arglists (into {} (map method->arglist-entry) methods)}))

(defn desc->typed-entry
  [{:keys [name malli-spec]}]
  (let [t (amb/malli-spec->type malli-spec)]
    (if (at/fun-type? t)
      (merge {:name name :typings [t]} (fun-type->entry-fields t))
      {:name name :typings [t]})))

(defn- convert-descs
  [ns descs initial-errors]
  (reduce (fn [{:keys [entries errors]} [qualified-sym desc]]
            (try
              {:entries (assoc entries qualified-sym (desc->typed-entry desc))
               :errors errors}
              (catch Exception e
                {:entries entries
                 :errors (conj errors (scollect/declaration-error-result
                                       :malli-declaration
                                       (symbol (ns-name ns))
                                       qualified-sym
                                       (resolve qualified-sym)
                                       e))})))
          {:entries {} :errors (vec initial-errors)}
          descs))

(defn typed-ns-malli-results
  [opts ns]
  (let [{:keys [entries errors]} (mcollect/ns-malli-spec-results opts ns)]
    (convert-descs ns entries errors)))

(defn typed-ns-malli-entries
  [opts ns]
  (:entries (typed-ns-malli-results opts ns)))
