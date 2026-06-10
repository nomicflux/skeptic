(ns skeptic.cljs-fixtures.uses-npm
  (:require-macros [schema.core :as s])
  (:require ["react" :as react]
            ["react-dom/client" :as rdom]
            [schema.core :as s :include-macros true]))

(s/defn add-int :- s/Int [x :- s/Int] (+ x 1))

(defn render []
  (react/createElement "div" nil (add-int "not-int")))

(defn mount [el]
  (rdom/createRoot el))
