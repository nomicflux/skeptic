(let*
 [ufv__
  schema.utils/use-fn-validation
  output-schema27610
  schema.core/Int
  input-schema27611
  [(schema.core/one schema.core/Str 'not-an-int)]
  input-checker27612
  (new
   clojure.lang.Delay
   (fn* [] (schema.core/checker input-schema27611)))
  output-checker27613
  (new
   clojure.lang.Delay
   (fn* [] (schema.core/checker output-schema27610)))]
 (let*
  [ret__5397__auto__
   (clojure.core/defn
    skeptic.test-examples/sample-bad-schema-fn
    {:schema
     (schema.core/->FnSchema output-schema27610 [input-schema27611]),
     :doc "Inputs: [not-an-int :- s/Str]\n  Returns: s/Int",
     :raw-arglists '([not-an-int :- schema.core/Str]),
     :arglists '([not-an-int])}
    ([G__27614]
     (let*
      [validate__3810__auto__ (. ufv__ clojure.core/get)]
      (if
       validate__3810__auto__
       (do
        (let*
         [args__3811__auto__ [G__27614]]
         (if
          schema.core/fn-validator
          (schema.core/fn-validator
           :input
           'skeptic.test-examples/sample-bad-schema-fn
           input-schema27611
           @input-checker27612
           args__3811__auto__)
          (let*
           [temp__5804__auto__
            (@input-checker27612 args__3811__auto__)]
           (if
            temp__5804__auto__
            (do
             (let*
              [error__3812__auto__ temp__5804__auto__]
              (throw
               (new
                clojure.lang.ExceptionInfo
                (schema.utils/format*
                 "Input to %s does not match schema: \n\n\t   %s  \n\n"
                 'skeptic.test-examples/sample-bad-schema-fn
                 (clojure.core/pr-str error__3812__auto__))
                {:type :schema.core/error,
                 :schema input-schema27611,
                 :value args__3811__auto__,
                 :error error__3812__auto__}))))))))))
      (let*
       [o__3813__auto__
        (loop*
         [not-an-int G__27614]
         (skeptic.test-examples/int-add not-an-int 2))]
       (if
        validate__3810__auto__
        (do
         (if
          schema.core/fn-validator
          (schema.core/fn-validator
           :output
           'skeptic.test-examples/sample-bad-schema-fn
           output-schema27610
           @output-checker27613
           o__3813__auto__)
          (let*
           [temp__5804__auto__ (@output-checker27613 o__3813__auto__)]
           (if
            temp__5804__auto__
            (do
             (let*
              [error__3812__auto__ temp__5804__auto__]
              (throw
               (new
                clojure.lang.ExceptionInfo
                (schema.utils/format*
                 "Output of %s does not match schema: \n\n\t   %s  \n\n"
                 'skeptic.test-examples/sample-bad-schema-fn
                 (clojure.core/pr-str error__3812__auto__))
                {:type :schema.core/error,
                 :schema output-schema27610,
                 :value o__3813__auto__,
                 :error error__3812__auto__})))))))))
       o__3813__auto__))))]
  (schema.utils/declare-class-schema!
   (schema.utils/fn-schema-bearer
    skeptic.test-examples/sample-bad-schema-fn)
   (schema.core/->FnSchema output-schema27610 [input-schema27611]))
  ret__5397__auto__))