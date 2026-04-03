(ns skeptic.core-fns)

;; (def core-fn-schemas
;;   {#'clojure.core/when (let [var-a (vars/type-var 'a)]
;;                          (s/=> (s/maybe var-a) var-a))
;;    #'clojure.core/if (let [var-a (vars/type-var 'a)
;;                            var-b (vars/type-var 'b)]
;;                        (s/=> (s/=> (s/either var-a var-b) s/Any var-a var-b)))
;;    #'clojure.string (s/=> s/Str s/Any)})
