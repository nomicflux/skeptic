(ns skeptic.analysis.annotate.data
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

(defn annotate-def
  [{:keys [locals] :as ctx} node]
  (let [meta-node (when-some [meta-node (:meta node)]
                    ((:recurse ctx) ctx meta-node))
        init-node (when-some [init-node (:init node)]
                    ((:recurse ctx) (assoc ctx
                                           :locals locals
                                           :name (:name node))
                     init-node))]
    (cond-> (assoc node
                   :type (at/->VarT (or (:type init-node) at/Dyn)))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))
        item-types (mapv #(ato/normalize-type (or (:type %) at/Dyn)) items)]
    (assoc node
           :items items
           :type (at/->VectorT item-types (aac/vec-homogeneous-items? item-types)))))

(defn annotate-set
  [ctx node]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))]
    (assoc node
           :items items
           :type (ato/normalize-type
                  #{(if (seq items)
                      (av/type-join* (map :type items))
                      at/Dyn)}))))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #((:recurse ctx) ctx %) (:keys node))
        vals (mapv #((:recurse ctx) ctx %) (:vals node))]
    (assoc node
           :keys keys
           :vals vals
           :type (ato/normalize-type
                  (into {}
                        (map (fn [k v]
                               [(ac/map-literal-key-type k)
                                (:type v)])
                             keys
                             vals))))))

(defn annotate-new
  [ctx node]
  (let [class-node ((:recurse ctx) ctx (:class node))
        args (mapv #((:recurse ctx) ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :type (or (aac/lazy-seq-new-type class-node args)
                     (some-> (:val class-node) av/class->type)
                     at/Dyn))))

(defn annotate-with-meta
  [ctx node]
  (let [meta-node ((:recurse ctx) ctx (:meta node))
        expr-node ((:recurse ctx) ctx (:expr node))]
    (merge node
           {:meta meta-node
            :expr expr-node}
           (ac/node-info expr-node))))

(defn annotate-throw
  [ctx node]
  (let [exception ((:recurse ctx) ctx (:exception node))]
    (assoc node
           :exception exception
           :type at/BottomType)))

(defn annotate-catch
  [{:keys [locals] :as ctx} node]
  (let [class-node ((:recurse ctx) ctx (:class node))
        caught-type (or (some-> (:val class-node) av/class->type)
                        at/Dyn)
        local-node (merge (:local node)
                          {:type caught-type})
        body ((:recurse ctx) (assoc ctx
                                    :locals (assoc locals (:form (:local node))
                                                   {:type caught-type}))
              (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :type (:type body))))

(defn annotate-try
  [ctx node]
  (let [body ((:recurse ctx) ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       ((:recurse ctx) ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :type (av/type-join* (cons (:type body)
                                              (map :type catches))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr ((:recurse ctx) ctx (:expr node))]
    (assoc node
           :expr expr
           :type (av/type-of-value (-> node :form second)))))
