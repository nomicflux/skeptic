(ns skeptic.analysis.origin
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]))

(defn typed-entry
  [entry]
  (cond
    (nil? entry) nil
    (at/semantic-type-value? entry)
    {:type (ato/normalize-type entry)}

    (and (map? entry)
         (or (contains? entry :type)
             (contains? entry :output-type)
             (contains? entry :arglists)))
    (cond-> (merge (dissoc entry :type :output-type :arglists)
                   {:type (ato/normalize-type (or (:type entry) at/Dyn))})
      (contains? entry :output-type)
      (assoc :output-type (ato/normalize-type (:output-type entry)))

      (contains? entry :arglists)
      (assoc :arglists (:arglists entry)))

    (map? entry)
    (throw (IllegalArgumentException.
            (format "Expected typed entry, got %s" (pr-str entry))))

    :else
    {:type (ato/normalize-type entry)}))

(defn root-origin
  [sym type]
  {:kind :root
   :sym sym
   :type (ato/normalize-type type)})

(defn opaque-origin
  [type]
  {:kind :opaque
   :type (ato/normalize-type type)})

(defn entry-origin
  [sym entry]
  (or (:origin entry)
      (when-let [type (:type entry)]
        (root-origin sym type))))

(defn node-origin
  [node]
  (or (:origin node)
      (when-let [type (:type node)]
        (opaque-origin type))))

(defn opposite-polarity
  [assumption]
  (update assumption :polarity not))

(defn same-assumption?
  [left right]
  (and (= (:kind left) (:kind right))
       (= (get-in left [:root :sym]) (get-in right [:root :sym]))
       (= (:key left) (:key right))
       (= (:polarity left) (:polarity right))))

(defn opposite-assumption?
  [left right]
  (same-assumption? left (opposite-polarity right)))

(defn assumption-root?
  [assumption root]
  (= (get-in assumption [:root :sym]) (:sym root)))

(defn apply-assumption-to-root-type
  [type assumption]
  (case (:kind assumption)
    :truthy-local
    (if (:polarity assumption)
      (ato/de-maybe-type type)
      type)

    :contains-key
    (avc/refine-type-by-contains-key type (:key assumption) (:polarity assumption))

    type))

(defn refine-root-type
  [root assumptions]
  (reduce (fn [type assumption]
            (if (assumption-root? assumption root)
              (apply-assumption-to-root-type type assumption)
              type))
          (:type root)
          assumptions))

(defn assumption-base-type
  [assumption assumptions]
  (let [same-proposition? (fn [candidate]
                            (and (= (:kind candidate) (:kind assumption))
                                 (= (get-in candidate [:root :sym]) (get-in assumption [:root :sym]))
                                 (= (:key candidate) (:key assumption))))]
    (refine-root-type (:root assumption)
                      (remove same-proposition? assumptions))))

(defn assumption-truth
  [assumption assumptions]
  (cond
    (some #(same-assumption? assumption %) assumptions) :true
    (some #(opposite-assumption? assumption %) assumptions) :false

    :else
    (case (:kind assumption)
      :contains-key
      (case (avc/contains-key-type-classification (assumption-base-type assumption assumptions)
                                                  (:key assumption))
        :always (if (:polarity assumption) :true :false)
        :never (if (:polarity assumption) :false :true)
        :unknown :unknown)

      :truthy-local
      :unknown

      :unknown)))

(defn origin-type
  [origin assumptions]
  (case (:kind origin)
    :root (refine-root-type origin assumptions)
    :opaque (:type origin)
    :branch (case (assumption-truth (:test origin) assumptions)
              :true (origin-type (:then-origin origin) assumptions)
              :false (origin-type (:else-origin origin) assumptions)
              (av/type-join* [(origin-type (:then-origin origin) assumptions)
                              (origin-type (:else-origin origin) assumptions)]))
    (:type origin)))

(defn effective-entry
  [sym entry assumptions]
  (let [entry (typed-entry entry)
        origin (entry-origin sym entry)
        type (or (some-> origin (origin-type assumptions))
                 (:type entry)
                 at/Dyn)]
    (cond-> (or entry {:type at/Dyn})
      true (assoc :type (ato/normalize-type type))
      origin (assoc :origin origin))))

(defn local-root-origin
  [node]
  (let [origin (node-origin node)]
    (when (= :root (:kind origin))
      origin)))

(defn contains-key-test-assumption
  [target-node key]
  (when-let [root (local-root-origin target-node)]
    {:kind :contains-key
     :root root
     :key key
     :polarity true}))

(defn test->assumption
  [test-node]
  (cond
    (= :local (:op test-node))
    (when-let [root (local-root-origin test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (and (= :invoke (:op test-node))
         (ac/contains-call? (:fn test-node)))
    (let [[target-node key-node] (:args test-node)]
      (when (keyword? (:form key-node))
        (contains-key-test-assumption target-node (:form key-node))))

    (and (= :static-call (:op test-node))
         (ac/static-contains-call? test-node))
    (let [[target-node key-node] (:args test-node)]
      (when (keyword? (:form key-node))
        (contains-key-test-assumption target-node (:form key-node))))

    :else
    nil))

(defn refine-locals-for-assumption
  [locals assumptions]
  (into {}
        (map (fn [[sym entry]]
               [sym (effective-entry sym entry assumptions)]))
        locals))

(defn branch-local-envs
  [locals assumptions assumption]
  (let [then-assumptions (cond-> (vec assumptions)
                          assumption (conj assumption))
        else-assumption (some-> assumption opposite-polarity)
        else-assumptions (cond-> (vec assumptions)
                          else-assumption (conj else-assumption))]
    {:then-locals (refine-locals-for-assumption locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption locals else-assumptions)
     :else-assumptions else-assumptions}))
