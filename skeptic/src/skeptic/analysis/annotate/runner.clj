(ns skeptic.analysis.annotate.runner
  "Trampoline for the annotate helpers.

  Helpers return steps instead of values so AST depth no longer maps
  to JVM stack depth:

      [:done annotated-node]
      [:call helper-fn ctx node k]

  `run` is a loop over a heap-allocated stack of continuations. A
  helper that returns a plain value (non-step) is auto-wrapped as
  `[:done value]`, which lets helpers migrate one file at a time.")

(defn done
  [node]
  [:done node])

(defn call
  [helper-fn ctx node k]
  [:call helper-fn ctx node k])

(defn- step?
  [v]
  (and (vector? v)
       (let [t (first v)]
         (or (identical? :done t)
             (identical? :call t)))))

(defn- normalize
  [v]
  (if (step? v) v [:done v]))

(defn run
  [helper-fn ctx node]
  (loop [step (normalize (helper-fn ctx node))
         stack []]
    (case (first step)
      :done
      (let [v (second step)]
        (if (zero? (count stack))
          v
          (recur (normalize ((peek stack) v))
                 (pop stack))))
      :call
      (let [[_ next-fn next-ctx next-node k] step]
        (recur (normalize (next-fn next-ctx next-node))
               (conj stack k))))))

(defn sequence-children
  [ctx children final-k]
  (let [recur-fn (:recurse-step ctx)]
    (letfn [(walk [acc remaining]
              (if (empty? remaining)
                (final-k acc)
                (call recur-fn ctx (first remaining)
                      (fn [annotated]
                        (walk (conj acc annotated) (rest remaining))))))]
      (walk [] children))))

(defn reduce-children
  [ctx state children step-fn final-k]
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
