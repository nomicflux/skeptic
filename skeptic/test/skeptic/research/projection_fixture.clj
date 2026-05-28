(ns skeptic.research.projection-fixture
  "Fixture exercising the projection contract: every top-level form contains a
   value that is non-EDN in a raw analyzer AST (regex Pattern, fn #object,
   var-meta Namespace/Class, Class literal). The worker reads THIS file itself
   and ships projected ASTs; no form crosses the wire.")

(defn find-ab [s] (re-find #"ab+c" s))

(defn inc-all [xs] (map (fn [x] (inc x)) xs))

(defn ^Long tagged-inc [x] (+ x 1))

(defn safe-msg [^Exception e] (try (.getMessage e) (catch Exception _ nil)))

(defn pick [m] (let [n {:a 1}] (:a (merge m n))))
