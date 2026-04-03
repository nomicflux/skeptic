 (ns skeptic.analysis.schema.map-cast
   (:require [skeptic.analysis.bridge :as ab]
             [skeptic.analysis.schema.cast-kernel :as ask]
             [skeptic.analysis.schema.cast-support :as ascs]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema.value-check :as asv]
             [skeptic.analysis.types :as at]))

 (defn map-path-segment
   [key]
   (when-let [path-key (asv/path-key key)]
     {:kind :map-key
      :key path-key}))

 (defn map-entry-failure
   [source-type target-type rule polarity reason path-key details]
   (asv/with-map-path
     (ascs/cast-fail source-type
                     target-type
                     rule
                     polarity
                     reason
                     []
                     details)
     path-key))

 (defn run-candidate-casts
   [check-cast requests]
   (let [results (ask/run-cast-requests check-cast requests)]
     (if-let [success (some #(when (:ok? %) %) results)]
       [success]
       results)))

 (defn candidate-value-cast-results
   [check-cast source-value target-entries path-key opts]
   (->> target-entries
        (mapv (fn [target-entry]
                (ask/cast-request source-value
                                  (:value target-entry)
                                  opts
                                  (map-path-segment path-key))))
        (run-candidate-casts check-cast)))

 (defn plan-exact-target-entry-casts
   [source-descriptor target-entry opts]
   (let [exact-value (:exact-value target-entry)
         source-candidates (asm/exact-key-candidates source-descriptor exact-value)]
     {:source-candidates source-candidates
      :source-exact-entry (asm/exact-key-entry source-descriptor exact-value)
      :requests (mapv (fn [source-entry]
                        (ask/cast-request (:value source-entry)
                                          (:value target-entry)
                                          opts
                                          (map-path-segment (:key target-entry))))
                      source-candidates)}))

 (defn exact-target-entry-cast-results
   [check-cast source-type target-type source-descriptor target-entry opts]
   (let [{:keys [source-candidates source-exact-entry requests]}
         (plan-exact-target-entry-casts source-descriptor target-entry opts)
         value-results (ask/run-cast-requests check-cast requests)
         nullable-result (when (and (= :required-explicit (:kind target-entry))
                                    (= :optional-explicit (:kind source-exact-entry)))
                           (map-entry-failure source-type
                                              target-type
                                              :map-nullable-key
                                              (:polarity opts)
                                              :nullable-key
                                              (:key target-entry)
                                              {:actual-key (:key source-exact-entry)
                                               :expected-key (:key target-entry)}))]
     (cond
       (empty? source-candidates)
       (if (= :required-explicit (:kind target-entry))
         [(map-entry-failure source-type
                             target-type
                             :map-missing-key
                             (:polarity opts)
                             :missing-key
                             (:key target-entry)
                             {:expected-key (:key target-entry)})]
         [])

       :else
       (cond-> value-results
         nullable-result (conj nullable-result)))))

 (defn plan-exact-source-entry-casts
   [source-entry target-schema-entries opts]
   (let [target-candidates (->> target-schema-entries
                                (filter #(asm/key-domain-covered? (ab/exact-value-import-type (:exact-value source-entry))
                                                                  (:inner-key-type %)))
                                vec)]
     {:target-candidates target-candidates
      :requests (mapv (fn [target-entry]
                        (ask/cast-request (:value source-entry)
                                          (:value target-entry)
                                          opts))
                      target-candidates)}))

 (defn exact-source-entry-cast-results
   [check-cast source-type target-type source-entry target-schema-entries opts]
   (let [{:keys [target-candidates requests]}
         (plan-exact-source-entry-casts source-entry target-schema-entries opts)]
     (if (empty? target-candidates)
       [(map-entry-failure source-type
                           target-type
                           :map-unexpected-key
                           (:polarity opts)
                           :unexpected-key
                           (:key source-entry)
                           {:actual-key (:key source-entry)})]
       (run-candidate-casts check-cast requests))))

 (defn expand-schema-domain-entry
   [source-entry]
   (let [source-key-type (:inner-key-type source-entry)]
     (if (at/union-type? source-key-type)
       (mapcat (fn [member]
                 (expand-schema-domain-entry
                   (assoc source-entry
                          :key member
                          :key-type member
                          :inner-key-type member
                          :exact-value nil)))
               (:members source-key-type))
       [source-entry])))

 (defn plan-schema-domain-entry-casts
   [source-entry target-schema-entries opts]
   (let [source-key-type (:inner-key-type source-entry)
         target-candidates (->> target-schema-entries
                                (filter #(asm/key-domain-covered? source-key-type
                                                                  (:inner-key-type %)))
                                vec)]
     {:target-candidates target-candidates
      :requests (mapv (fn [target-entry]
                        (ask/cast-request (:value source-entry)
                                          (:value target-entry)
                                          opts))
                      target-candidates)}))

 (defn schema-domain-entry-cast-results
   [check-cast source-type target-type source-entry target-schema-entries opts]
   (mapcat (fn [entry]
             (let [source-key-type (:inner-key-type entry)
                   {:keys [target-candidates requests]}
                   (plan-schema-domain-entry-casts entry target-schema-entries opts)]
               (if (empty? target-candidates)
                 [(ascs/cast-fail source-type
                                  target-type
                                  :map-key-domain
                                  (:polarity opts)
                                  :map-key-domain-not-covered
                                  []
                                  {:actual-key (:key entry)
                                   :source-key-domain source-key-type})]
                 (run-candidate-casts check-cast requests))))
           (expand-schema-domain-entry source-entry)))

 (defn map-cast-children
   [check-cast source-type target-type opts]
   (let [source-descriptor (asm/map-entry-descriptor (:entries (ab/schema->type source-type)))
         target-descriptor (asm/map-entry-descriptor (:entries (ab/schema->type target-type)))
         target-exact-entries (vec (asm/effective-exact-entries target-descriptor))
         target-exact-values (set (map :exact-value target-exact-entries))
         target-schema-entries (vec (:schema-entries target-descriptor))
         source-exact-entries (vec (asm/effective-exact-entries source-descriptor))
         source-extra-exact-entries (->> source-exact-entries
                                         (remove #(contains? target-exact-values
                                                             (:exact-value %)))
                                         vec)
         source-schema-entries (vec (:schema-entries source-descriptor))]
     (vec
       (concat
         (mapcat #(exact-target-entry-cast-results check-cast
                                                   source-type
                                                   target-type
                                                   source-descriptor
                                                   %
                                                   opts)
                 target-exact-entries)
         (mapcat #(exact-source-entry-cast-results check-cast
                                                   source-type
                                                   target-type
                                                   %
                                                   target-schema-entries
                                                   opts)
                 source-extra-exact-entries)
         (mapcat #(schema-domain-entry-cast-results check-cast
                                                    source-type
                                                    target-type
                                                    %
                                                    target-schema-entries
                                                    opts)
                 source-schema-entries)))))

 (defn check-map-cast
   [check-cast source-type target-type polarity opts]
   (let [children (map-cast-children check-cast source-type target-type opts)]
     (ask/aggregate-all-children source-type
                                 target-type
                                 :map
                                 polarity
                                 :map-cast-failed
                                 children)))
