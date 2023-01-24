(ns skeptic.colours)

(def black-code "\u001B[30")
(def red-code "\u001B[31")
(def green-code "\u001B[32")
(def yellow-code "\u001B[33")
(def blue-code "\u001B[34")
(def magenta-code "\u001B[35")
(def cyan-code "\u001B[36")
(def white-code "\u001B[37")

(def reset-code "\u001B[0m")

(def bright-flag ";1")
(def end-flag "m")

(defn add-colour
  [s colour bright?]
  (format "%s%s%s%s%s"
          colour
          (if bright? bright-flag "")
          end-flag
          s
          reset-code))

(defn red
  ([s]
   (red s false))
  ([s bright?]
   (add-colour s red-code bright?)))

(defn green
  ([s]
   (green s false))
  ([s bright?]
   (add-colour s green-code bright?)))

(defn yellow
  ([s]
   (yellow s false))
  ([s bright?]
   (add-colour s yellow-code bright?)))

(defn blue
  ([s]
   (blue s false))
  ([s bright?]
   (add-colour s blue-code bright?)))

(defn magenta
  ([s]
   (magenta s false))
  ([s bright?]
   (add-colour s magenta-code bright?)))

(defn cyan
  ([s]
   (cyan s false))
  ([s bright?]
   (add-colour s cyan-code bright?)))

(defn white
  ([s]
   (white s false))
  ([s bright?]
   (add-colour s white-code bright?)))

(defn black
  ([s]
   (black s false))
  ([s bright?]
   (add-colour s black-code bright?)))
