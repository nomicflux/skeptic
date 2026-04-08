(ns skeptic.local-resolution-fixtures
  "Fixtures for local / shadowing provenance in user-visible check output."
  (:require [schema.core :as s :include-macros true]
            [skeptic.test-examples :as te]))

(s/defn shadow-provenance :- s/Int
  "Param `x` is shadowed by inner `let`. The final `te/int-add` uses the **param** `x`, not the inner binding."
  [x :- s/Int]
  (let [x (te/int-add 9 9)]
    x)
  (let [y (te/int-add 1 nil)
        z (te/int-add 2 3)]
    (te/int-add x y z)))

(s/defn import-named-local :- s/Int
  "Local named `import` should not be confused with `clojure.core/import`."
  [import :- s/Int]
  (te/int-add import 0))
