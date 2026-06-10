(ns demo.a
  (:require [demo.b :as b]
            ["react-dom/client" :as rdom]))

(defn render [el] (rdom/createRoot el))

(defn page [] (b/el))
