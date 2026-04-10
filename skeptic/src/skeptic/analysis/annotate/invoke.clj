(ns skeptic.analysis.annotate.invoke
  (:require [skeptic.analysis.annotate.fn :as aaf]
            [skeptic.analysis.annotate.invoke-output :as aaio]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]))

(defn unary-fn-invoke-with-arg-type-hint?
  [ctx fn-ast node]
  (or (and (= :fn (:op fn-ast))
           (= 1 (count (:methods fn-ast)))
           (= 1 (count (:args node))))
      (and (= :local (:op fn-ast))
           (let [e (get (:locals ctx) (:form fn-ast))
                 fnode (:fn-binding-node e)]
             (and fnode
                  (= :fn (:op fnode))
                  (= 1 (count (:methods fnode)))
                  (= 1 (count (:args node))))))))

(defn annotate-unary-fn-invoke-with-arg-type-hint
  [ctx fn-ast node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        [src-fn pform]
        (if (= :fn (:op fn-ast))
          [fn-ast (:form (first (:params (first (:methods fn-ast)))))]
          (let [e (get (:locals ctx) (:form fn-ast))
                fnode (:fn-binding-node e)]
            [fnode (:form (first (:params (first (:methods fnode)))))]))
        ovs {pform (or (:type (first args)) at/Dyn)}
        fn-node (aaf/annotate-fn ctx src-fn {:param-type-overrides ovs})]
    [fn-node args]))

(defn annotate-invoke
  [ctx node]
  (let [fn-ast (:fn node)
        hint? (unary-fn-invoke-with-arg-type-hint? ctx fn-ast node)
        [fn-node args]
        (if hint?
          (annotate-unary-fn-invoke-with-arg-type-hint ctx fn-ast node)
          [((:recurse ctx) ctx fn-ast)
           (mapv #((:recurse ctx) ctx %) (:args node))])
        {:keys [expected-argtypes output-type fn-type]} (ac/call-info fn-node args)
        output-type (aaio/invoke-output-type fn-node args output-type)
        narrow-t (aan/invoke-integral-math-narrow-type fn-node args (mapv :type args))
        output-type (or narrow-t output-type)]
    (assoc node
           :fn fn-node
           :args args
           :actual-argtypes (mapv :type args)
           :expected-argtypes (mapv ato/normalize-type expected-argtypes)
           :type (ato/normalize-type output-type)
           :fn-type (ato/normalize-type fn-type))))
