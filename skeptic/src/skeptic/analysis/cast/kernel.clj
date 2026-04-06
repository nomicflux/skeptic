(ns skeptic.analysis.cast.kernel
  (:require [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.types :as at]))

(defn cast-request
  ([source-type target-type opts]
   (cast-request source-type target-type opts nil))
  ([source-type target-type opts path-segment]
   {:source-type source-type
    :target-type target-type
    :opts opts
    :path-segment path-segment}))

(defn run-cast-request
  [check-cast {:keys [source-type target-type opts path-segment]}]
  (cond-> (check-cast source-type target-type opts)
    path-segment (ascs/with-cast-path path-segment)))

(defn run-cast-requests
  [check-cast requests]
  (mapv #(run-cast-request check-cast %) requests))

(defn indexed-cast-requests
  [segment-kind build-request xs]
  (mapv (fn [idx x]
          (assoc (build-request x)
                 :path-segment {:kind segment-kind
                                :index idx}))
        (range)
        xs))

(defn aggregate-all-children
  [source-type target-type rule polarity reason children]
  (if (ascs/all-ok? children)
    (ascs/cast-ok source-type target-type rule children)
    (ascs/cast-fail source-type target-type rule polarity reason children)))

(defn collection-cast-children
  [check-cast segment-kind source-items target-items opts]
  (->> (map (fn [idx source-item target-item]
              (cast-request source-item
                            target-item
                            opts
                            {:kind segment-kind
                             :index idx}))
            (range)
            source-items
            target-items)
       vec
       (run-cast-requests check-cast)))

(defn expand-vector-items
  [type slot-count]
  (let [items (:items type)]
    (if (:homogeneous? type)
      (vec (repeat slot-count (or (first items) at/Dyn)))
      items)))

(defn vector-cast-slot-count
  [source-type target-type]
  (let [source-count (count (:items source-type))
        target-count (count (:items target-type))
        source-homogeneous? (:homogeneous? source-type)
        target-homogeneous? (:homogeneous? target-type)]
    (cond
      (and source-homogeneous? target-homogeneous?) 1
      target-homogeneous? source-count
      source-homogeneous? target-count
      (= source-count target-count) source-count
      :else nil)))

(defn set-cast-children
  [check-cast source-members target-members opts]
  (reduce (fn [acc source-member]
            (let [requests (mapv #(cast-request source-member % opts) target-members)
                  results (run-cast-requests check-cast requests)]
              (if-let [match (some #(when (:ok? %) %) results)]
                (conj acc match)
                (conj acc
                      (ascs/with-cast-path
                        (ascs/cast-fail source-member
                                        (or (first target-members) at/Dyn)
                                        :set-element
                                        (:polarity opts)
                                        :element-mismatch)
                        {:kind :set-member
                         :member source-member})))))
          []
          source-members))

(defn check-quantified-cast
  [check-cast source-type target-type polarity opts]
  (cond
    (at/forall-type? target-type)
    (if (contains? (ata/type-free-vars source-type) (:binder target-type))
      (ascs/cast-fail source-type
                      target-type
                      :generalize
                      polarity
                      :forall-capture
                      []
                      {:binder (:binder target-type)
                       :cast-state (ascs/cast-state opts)})
      (let [child (check-cast source-type
                              (:body target-type)
                              (ascs/with-abstract-var opts (:binder target-type)))]
        (if (:ok? child)
          (ascs/cast-ok source-type
                        target-type
                        :generalize
                        [child]
                        {:binder (:binder target-type)
                         :cast-state (ascs/cast-state opts)})
          (ascs/cast-fail source-type
                          target-type
                          :generalize
                          polarity
                          :generalize-failed
                          [child]
                          {:binder (:binder target-type)
                           :cast-state (ascs/cast-state opts)}))))

    :else
    (let [instantiated (ata/type-substitute (:body source-type)
                                            (:binder source-type)
                                            at/Dyn)
          child (check-cast instantiated
                            target-type
                            (ascs/with-nu-binding opts (:binder source-type) at/Dyn))]
      (if (:ok? child)
        (ascs/cast-ok source-type
                      target-type
                      :instantiate
                      [child]
                      {:binder (:binder source-type)
                       :instantiated-type instantiated
                       :cast-state (ascs/cast-state opts)})
        (ascs/cast-fail source-type
                        target-type
                        :instantiate
                        polarity
                        :instantiate-failed
                        [child]
                        {:binder (:binder source-type)
                         :instantiated-type instantiated
                         :cast-state (ascs/cast-state opts)})))))

(defn check-abstract-type-cast
  [source-type target-type polarity opts]
  (cond
    (and (at/type-var-type? source-type)
         (at/dyn-type? target-type))
    (let [sealed-type (at/->SealedDynT source-type)]
      (ascs/cast-ok source-type
                    target-type
                    :seal
                    []
                    {:sealed-type sealed-type
                     :cast-state (:cast-state (ascs/register-seal opts sealed-type))}))

    (at/type-var-type? target-type)
    (cond
      (at/sealed-dyn-type? source-type)
      (if (= (ascs/sealed-ground-name source-type) (ata/type-var-name target-type))
        (ascs/cast-ok source-type
                      target-type
                      :sealed-collapse
                      []
                      {:cast-state (ascs/cast-state opts)})
        (ascs/cast-fail source-type
                        target-type
                        :sealed-collapse
                        polarity
                        :sealed-ground-mismatch
                        []
                        {:cast-state (ascs/cast-state opts)}))

      (or (at/dyn-type? source-type)
          (at/placeholder-type? source-type))
      (ascs/cast-ok source-type
                    target-type
                    :type-var-target
                    []
                    {:cast-state (ascs/cast-state opts)})

      :else
      (ascs/cast-fail source-type
                      target-type
                      :type-var-target
                      polarity
                      :abstract-target-mismatch
                      []
                      {:cast-state (ascs/cast-state opts)}))

    (at/type-var-type? source-type)
    (ascs/cast-fail source-type
                    target-type
                    :type-var-source
                    polarity
                    :abstract-source-mismatch
                    []
                    {:cast-state (ascs/cast-state opts)})

    :else
    (ascs/cast-fail source-type
                    target-type
                    :sealed-conflict
                    polarity
                    :sealed-mismatch
                    []
                    {:cast-state (ascs/cast-state opts)})))

(defn check-union-cast
  [check-cast source-type target-type polarity opts]
  (cond
    (and (at/union-type? source-type) (at/union-type? target-type))
    (let [children (->> (:members source-type)
                        (indexed-cast-requests :source-union-branch
                                               #(cast-request % target-type opts))
                        (run-cast-requests check-cast))]
      (aggregate-all-children source-type target-type
                              :source-union polarity
                              :source-branch-failed children))

    (at/union-type? target-type)
    (let [children (->> (:members target-type)
                        (indexed-cast-requests :target-union-branch
                                               #(cast-request source-type % opts))
                        (run-cast-requests check-cast))]
      (if-let [success (some #(when (:ok? %) %) children)]
        (ascs/cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
        (ascs/cast-fail source-type target-type :target-union polarity :no-union-branch children)))

    :else
    (let [children (->> (:members source-type)
                        (indexed-cast-requests :source-union-branch
                                               #(cast-request % target-type opts))
                        (run-cast-requests check-cast))]
      (aggregate-all-children source-type
                              target-type
                              :source-union
                              polarity
                              :source-branch-failed
                              children))))

(defn check-intersection-cast
  [check-cast source-type target-type polarity opts]
  (if (at/intersection-type? target-type)
    (let [children (->> (:members target-type)
                        (indexed-cast-requests :target-intersection-branch
                                               #(cast-request source-type % opts))
                        (run-cast-requests check-cast))]
      (aggregate-all-children source-type
                              target-type
                              :target-intersection
                              polarity
                              :target-component-failed
                              children))
    (let [children (->> (:members source-type)
                        (indexed-cast-requests :source-intersection-branch
                                               #(cast-request % target-type opts))
                        (run-cast-requests check-cast))]
      (aggregate-all-children source-type
                              target-type
                              :source-intersection
                              polarity
                              :source-component-failed
                              children))))

(defn check-maybe-cast
  [check-cast source-type target-type polarity opts]
  (letfn [(maybe-child [child-source child-target]
            (run-cast-request check-cast
                              (cast-request child-source
                                            child-target
                                            opts
                                            {:kind :maybe-value})))]
    (cond
      (and (at/maybe-type? source-type) (at/maybe-type? target-type))
      (let [child (maybe-child (:inner source-type) (:inner target-type))]
        (if (:ok? child)
          (ascs/cast-ok source-type target-type :maybe-both [child])
          (ascs/cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

      (at/maybe-type? target-type)
      (let [child (maybe-child source-type (:inner target-type))]
        (if (:ok? child)
          (ascs/cast-ok source-type target-type :maybe-target [child])
          (ascs/cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

      :else
      (ascs/cast-fail source-type target-type :maybe-source polarity :nullable-source))))

(defn check-wrapper-cast
  [check-cast source-type target-type opts]
  (cond
    (at/optional-key-type? source-type)
    (check-cast (:inner source-type) target-type opts)

    (at/optional-key-type? target-type)
    (check-cast source-type (:inner target-type) opts)

    (at/var-type? source-type)
    (check-cast (:inner source-type) target-type opts)

    :else
    (check-cast source-type (:inner target-type) opts)))

(defn function-domain-requests
  [source-method target-method opts]
  (mapv (fn [idx target-input source-input]
          (cast-request target-input
                        source-input
                        (update opts :polarity abr/flip-polarity)
                        {:kind :function-domain
                         :index idx}))
        (range)
        (:inputs target-method)
        (:inputs source-method)))

(defn check-function-method-cast
  [check-cast source-type target-type target-method polarity opts]
  (if-let [source-method (ascs/matching-source-method source-type target-method)]
    (let [domain-results (->> (function-domain-requests source-method target-method opts)
                              (run-cast-requests check-cast))
          range-result (run-cast-request check-cast
                                         (cast-request (:output source-method)
                                                       (:output target-method)
                                                       opts
                                                       {:kind :function-range}))
          method-children (conj domain-results range-result)]
      (if (ascs/all-ok? method-children)
        (ascs/cast-ok source-method target-method :function-method method-children)
        (ascs/cast-fail source-method
                        target-method
                        :function-method
                        polarity
                        :function-component-failed
                        method-children)))
    (ascs/cast-fail source-type
                    target-type
                    :function-arity
                    polarity
                    :arity-mismatch
                    []
                    {:target-method target-method})))

(defn check-function-cast
  [check-cast source-type target-type polarity opts]
  (let [children (mapv #(check-function-method-cast check-cast
                                                    source-type
                                                    target-type
                                                    %
                                                    polarity
                                                    opts)
                       (:methods target-type))]
    (aggregate-all-children source-type
                            target-type
                            :function
                            polarity
                            :function-cast-failed
                            children)))

(defn check-vector-cast
  [check-cast source-type target-type polarity opts]
  (if-let [slot-count (vector-cast-slot-count source-type target-type)]
    (let [source-items (expand-vector-items source-type slot-count)
          target-items (expand-vector-items target-type slot-count)
          children (collection-cast-children check-cast
                                             :vector-index
                                             source-items
                                             target-items
                                             opts)]
      (aggregate-all-children source-type
                              target-type
                              :vector
                              polarity
                              :vector-element-failed
                              children))
    (ascs/cast-fail source-type target-type :vector polarity :vector-arity-mismatch)))

(defn check-seq-cast
  [check-cast source-type target-type polarity opts]
  (let [source-items (:items source-type)
        target-items (:items target-type)]
    (if (= (count source-items) (count target-items))
      (let [children (collection-cast-children check-cast
                                               :seq-index
                                               source-items
                                               target-items
                                               opts)]
        (aggregate-all-children source-type
                                target-type
                                :seq
                                polarity
                                :seq-element-failed
                                children))
      (ascs/cast-fail source-type target-type :seq polarity :seq-arity-mismatch))))

(defn check-set-cast
  [check-cast source-type target-type polarity opts]
  (let [source-members (:members source-type)
        target-members (:members target-type)]
    (if (= (count source-members) (count target-members))
      (let [children (set-cast-children check-cast source-members target-members opts)]
        (aggregate-all-children source-type
                                target-type
                                :set
                                polarity
                                :set-element-failed
                                children))
      (ascs/cast-fail source-type target-type :set polarity :set-cardinality-mismatch))))

(defn check-leaf-cast
  [source-type target-type polarity]
  (cond
    (at/value-type? source-type)
    (if (avc/value-satisfies-type? (:value source-type) target-type)
      (ascs/cast-ok source-type target-type :value-exact)
      (ascs/cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

    (at/value-type? target-type)
    (if (avc/value-satisfies-type? (:value target-type) source-type)
      (ascs/cast-ok source-type target-type :target-value)
      (ascs/cast-fail source-type target-type :target-value polarity :target-value-mismatch))

    (or (at/dyn-type? source-type)
        (at/placeholder-type? source-type))
    (ascs/cast-ok source-type target-type :residual-dynamic)

    (or (at/ground-type? source-type)
        (at/refinement-type? source-type)
        (at/adapter-leaf-type? source-type))
    (if (avc/leaf-overlap? source-type target-type)
      (ascs/cast-ok source-type target-type :leaf-overlap)
      (ascs/cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

    :else
    (ascs/cast-fail source-type target-type :mismatch polarity :mismatch)))
