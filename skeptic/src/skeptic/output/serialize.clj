(ns skeptic.output.serialize
  "Coerce a Clojure value to a JSON-safe form for the --debug wire-tap.
  The only job is to make the value printable through clojure.data.json.
  No type dispatch, no projection, no filtering. The value is printed by
  Clojure's own printer under locked bindings — the string it produces is
  what ships.")

(defn json-safe
  [v]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-namespace-maps* false
            *print-meta*           false
            *print-readably*       true]
    (pr-str v)))
