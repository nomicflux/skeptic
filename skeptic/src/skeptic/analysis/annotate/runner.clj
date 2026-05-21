(ns skeptic.analysis.annotate.runner
  "Trampoline for the annotate helpers.

  End state (Phase 7): every annotate helper has signature
  `(Ctx, AnnotatedNode) -> Step`, where `Step` is the sum type below.

      [:done annotated-node]
      [:call helper-fn ctx node k]

  `run` is a loop over a heap-allocated stack of continuations. During
  the Phase 2-6 migration window, some helpers still return plain
  AnnotatedNode values; `step?` / `normalize` plus the [:done v]
  auto-wrap branch inside `run` carry those through. All three are
  migration-window cruft and are deleted in Phase 7 contraction."
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]))

(def DoneStep
  "Terminal step. The carried value is structurally polymorphic — it
  is whatever the next continuation expects. The OUTERMOST [:done v]
  popped by `run` carries an AnnotatedNode (enforced by `run`'s return
  schema)."
  [(s/one (s/eq :done) "tag")
   (s/one s/Any "value")])

(def CallStep
  "Dispatch step. Run `(helper-fn ctx node)`; the trampoline pushes `k`
  onto the continuation stack and resumes with `(k v)` once that
  invocation terminates with `[:done v]`."
  [(s/one (s/eq :call) "tag")
   (s/one (s/pred fn?) "helper-fn")
   (s/one s/Any "ctx")
   (s/one aas/AnnotatedNode "node")
   (s/one (s/pred fn?) "k")])

(def Step
  "Sum type returned by every step-shaped annotate helper at end
  state. Exactly two variants: DoneStep | CallStep."
  (s/conditional
   #(and (vector? %) (= :done (first %))) DoneStep
   #(and (vector? %) (= :call (first %))) CallStep))

(deftype ContinuationFrame [helper ctx node k])

(s/defn done :- Step
  [node :- aas/AnnotatedNode]
  [:done node])

(s/defn call :- Step
  [helper-fn :- (s/pred fn?)
   ctx       :- s/Any
   node      :- aas/AnnotatedNode
   k         :- (s/pred fn?)]
  [:call helper-fn ctx node k])

;; ---- Migration-window scaffolding (deleted in Phase 7) ----
(defn- step?
  [v]
  (and (vector? v)
       (let [t (first v)]
         (or (identical? :done t)
             (identical? :call t)))))

(defn- normalize
  [v]
  (if (step? v) v [:done v]))
;; ---- end migration-window scaffolding ----

(s/defn run-with-finalizer :- aas/AnnotatedNode
  "Run helper-fn through the trampoline, applying finalize-fn when each helper
  invocation completes. finalize-fn receives the helper function, ctx, node, and
  completed value for that invocation."
  [helper-fn   :- (s/pred fn?)
   ctx         :- s/Any
   node        :- aas/AnnotatedNode
   finalize-fn :- (s/pred fn?)]
  (loop [step (normalize (helper-fn ctx node))
         current-helper helper-fn
         current-ctx ctx
         current-node node
         stack []]
    (case (first step)
      :done
      (let [v (finalize-fn current-helper current-ctx current-node (second step))]
        (if (zero? (count stack))
          v
          (let [^ContinuationFrame frame (peek stack)]
            (recur (normalize ((.-k frame) v))
                   (.-helper frame)
                   (.-ctx frame)
                   (.-node frame)
                   (pop stack)))))
      :call
      (let [[_ next-fn next-ctx next-node k] step]
        (recur (normalize (next-fn next-ctx next-node))
               next-fn
               next-ctx
               next-node
               (conj stack (ContinuationFrame. current-helper current-ctx current-node k)))))))

(s/defn run :- aas/AnnotatedNode
  [helper-fn :- (s/pred fn?)
   ctx       :- s/Any
   node      :- aas/AnnotatedNode]
  (run-with-finalizer helper-fn ctx node (fn [_helper-fn _ctx _node v] v)))

(s/defn sequence-children :- Step
  [ctx      :- s/Any
   children :- [aas/AnnotatedNode]
   final-k  :- (s/pred fn?)]
  (let [recur-fn (:recurse-step ctx)]
    (letfn [(walk [acc remaining]
              (if (empty? remaining)
                (final-k acc)
                (call recur-fn ctx (first remaining)
                      (fn [annotated]
                        (walk (conj acc annotated) (rest remaining))))))]
      (walk [] children))))

(s/defn reduce-children :- Step
  [ctx      :- s/Any
   state    :- s/Any
   children :- [aas/AnnotatedNode]
   step-fn  :- (s/pred fn?)
   final-k  :- (s/pred fn?)]
  (let [recur-fn (:recurse-step ctx)]
    (letfn [(walk [cur-state cur-ctx acc remaining]
              (if (empty? remaining)
                (final-k cur-state acc)
                (let [child (first remaining)]
                  (call recur-fn cur-ctx child
                        (fn [annotated]
                          (let [[next-state next-ctx]
                                (step-fn cur-state cur-ctx child annotated)]
                            (walk next-state next-ctx
                                  (conj acc annotated)
                                  (rest remaining))))))))]
      (walk state ctx [] children))))
