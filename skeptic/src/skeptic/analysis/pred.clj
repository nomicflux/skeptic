(ns skeptic.analysis.pred)

(defn fn-once?
  "(fn* [x] (...) (...) ...)"
  [x]
  (and (seq? x)
       (-> x first :expr (= 'fn*))
       (-> x second :expr vector?)))

(defn fn-expr?
  "(fn* ([x] ...) ([x y] ...) ...)"
  [x]
  (and (seq? x)
       (-> x first :expr (= 'fn*))
       (-> x second :expr seq?)
       (-> x second :expr first :expr vector?)))

(defn let?
  [x]
  (and (seq? x)
       (or (-> x first :expr (= 'let))
           (-> x first :expr (= 'let*)))))

(defn loop?
  [x]
  (and (seq? x)
       (or (-> x first :expr (= 'loop))
           (-> x first :expr (= 'loop*)))))

(defn if?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'if))))

(defn do?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'do))))

(defn try?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'try))))

(defn throw?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'throw))))

(defn catch?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'catch))))

(defn finally?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'finally))))

(defn def?
  [x]
  (and (seq? x)
       (-> x first :expr (= 'def))))
