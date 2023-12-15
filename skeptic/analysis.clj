[{:children [:init],
  :init
  {:val {:a 1, :b 2},
   :type :map,
   :op :const,
   :env
   {:context :ctx/expr,
    :locals {},
    :ns skeptic.analysis,
    :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
    :column 24,
    :line 3},
   :o-tag clojure.lang.PersistentArrayMap,
   :literal? true,
   :form {:a 1, :b 2},
   :tag clojure.lang.PersistentArrayMap,
   :idx 2},
  :name x__#0,
  :op :binding,
  :env
  {:context :ctx/expr,
   :locals {},
   :ns skeptic.analysis,
   :column 24,
   :line 3,
   :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*"},
  :o-tag clojure.lang.PersistentArrayMap,
  :form x,
  :tag clojure.lang.PersistentArrayMap,
  :idx 1,
  :atom #<Atom@65d7a3ad: {:tag clojure.lang.PersistentArrayMap}>,
  :local :let}
 {:children [:init],
  :init
  {:children [],
   :name x__#0,
   :op :local,
   :env
   {:context :ctx/expr,
    :locals
    {x
     {:op :binding,
      :name x,
      :init
      {:op :map,
       :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
       :keys
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :a,
         :form :a}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :b,
         :form :b}],
       :vals
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 1,
         :form 1}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 2,
         :form 2}],
       :form {:a 1, :b 2},
       :children [:keys :vals]},
      :form x,
      :local :let,
      :children [:init]}},
    :ns skeptic.analysis,
    :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
    :column 24,
    :line 3},
   :o-tag clojure.lang.PersistentArrayMap,
   :form x,
   :tag clojure.lang.PersistentArrayMap,
   :idx 4,
   :atom #<Atom@65d7a3ad: {:tag clojure.lang.PersistentArrayMap}>,
   :local :let,
   :assignable? false},
  :name map__25833__#0,
  :op :binding,
  :env
  {:context :ctx/expr,
   :locals
   {x
    {:op :binding,
     :name x,
     :init
     {:op :map,
      :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
      :keys
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :a,
        :form :a}
       {:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :b,
        :form :b}],
      :vals
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 1,
        :form 1}
       {:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 2,
        :form 2}],
      :form {:a 1, :b 2},
      :children [:keys :vals]},
     :form x,
     :local :let,
     :children [:init]}},
   :ns skeptic.analysis,
   :column 24,
   :line 3,
   :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*"},
  :o-tag clojure.lang.PersistentArrayMap,
  :form map__25833,
  :tag clojure.lang.PersistentArrayMap,
  :idx 3,
  :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
  :local :let}
 {:children [:init],
  :init
  {:children [:test :then :else],
   :else
   {:children [],
    :name map__25833__#0,
    :op :local,
    :env
    {:context :ctx/expr,
     :locals
     {x
      {:op :binding,
       :name x,
       :init
       {:op :map,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :keys
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :a,
          :form :a}
         {:op :;; => const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :b,
          :form :b}],
        :vals
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 1,
          :form 1}
         {:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 2,
          :form 2}],
        :form {:a 1, :b 2},
        :children [:keys :vals]},
       :form x,
       :local :let,
       :children [:init]},
      map__25833
      {:op :binding,
       :name map__25833,
       :init
       {:op :local,
        :name x,
        :form x,
        :local :let,
        :children [],
        :assignable? false,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis}},
       :form map__25833,
       :local :let,
       :children [:init]}},
     :ns skeptic.analysis,
     :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
     :column 24,
     :line 3},
    :o-tag clojure.lang.PersistentArrayMap,
    :form map__25833,
    :tag clojure.lang.PersistentArrayMap,
    :idx 26,
    :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
    :local :let,
    :assignable? false},
   :op :if,
   :env
   {:context :ctx/expr,
    :locals
    {x
     {:op :binding,
      :name x,
      :init
      {:op :map,
       :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
       :keys
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :a,
         :form :a}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :b,
         :form :b}],
       :vals
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 1,
         :form 1}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 2,
         :form 2}],
       :form {:a 1, :b 2},
       :children [:keys :vals]},
      :form x,
      :local :let,
      :children [:init]},
     map__25833
     {:op :binding,
      :name map__25833,
      :init
      {:op :local,
       :name x,
       :form x,
       :local :let,
       :children [],
       :assignable? false,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env ;; => {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis}},
      :form map__25833,
      :local :let,
      :children [:init]}},
    :ns skeptic.analysis,
    :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
    :column 24,
    :line 3},
   :o-tag nil,
   :then
   {:children [:test :then :else],
    :else
    {:children [:test :then :else],
     :else
     {:field EMPTY,
      :op :static-field,
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         ;; => :children [:init]}},
       :ns skeptic.analysis,
       :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
       :column 24,
       :line 3},
      :o-tag clojure.lang.PersistentArrayMap,
      :class clojure.lang.PersistentArrayMap,
      :form (. clojure.lang.PersistentArrayMap -EMPTY),
      :tag clojure.lang.PersistentArrayMap,
      :idx 25,
      :assignable? false,
      :raw-forms (clojure.lang.PersistentArrayMap/EMPTY)},
     :op :if,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :local,
         :name x,
         :form x,
         :local :let,
         :children [],
         :assignable? false,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis}},
        :form map__25833,
        :local :let,
        :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag nil,
     :then
     {:op :invoke,
      :form (clojure.core/first map__25833),
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :;; => locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis,
       :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
       :column 24,
       :line 3},
      :fn
      {:meta
       {:added "1.0",
        :ns #namespace[clojure.core],
        :name first,
        :file "clojure/core.clj",
        :static true,
        :column 1,
        :line 49,
        :arglists ([coll]),
        :doc
        "Returns the first item in the collection. Calls seq on its\n    argument. If coll is nil, returns nil."},
       :op :var,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
  ;; =>           :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis,
        :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
        :column 24,
        :line 3},
       :o-tag java.lang.Object,
       :var #'clojure.core/first,
       :form clojure.core/first,
       :idx 23,
       :arglists ([coll]),
       :assignable? false},
      :args
      [{:children [],
        :name map__25833__#0,
        :op :local,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :co;; => nst,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis,
         :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
         :column 24,
         :line 3},
        :o-tag clojure.lang.PersistentArrayMap,
        :form map__25833,
        :tag clojure.lang.ISeq,
        :idx 24,
        :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
        :local :let,
        :assignable? false}],
      :children [:fn :args],
      :o-tag java.lang.Object,
      :idx 22},
     :form
     (if
      (clojure.core/seq map__25833)
      (clojure.core/first map__25833)
      clojure.lang.PersistentArrayMap/EMPTY),
     :idx 18,
     :test
     {:args
      [{:children [],
        :name map__25833__#0,
        :op :local,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
    ;; =>               :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis,
         :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
         :column 24,
         :line 3},
        :o-tag clojure.lang.PersistentArrayMap,
        :form map__25833,
        :tag clojure.lang.ISeq,
        :idx 21,
        :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
        :local :let,
        :assignable? false}],
      :children [:fn :args],
      :fn
      {:meta
       {:added "1.0",
        :ns #namespace[clojure.core],
        :name seq,
        :file "clojure/core.clj",
        :static true,
        :column 1,
        :line 128,
        :tag clojure.lang.ISeq,
        :arglists ([coll]),
        :doc
        "Returns a seq on the collection. If the collection is\n    empty, returns nil.  (seq nil) returns nil. seq also works on\n    Strings, native Java arrays (of reference types) and any objects\n    that implement Iterable. Note that seqs cache values, thus seq\n    should not be used on any Iterable whose iterator repeatedly\n    returns the same mutable object."},
       :return-tag clojure.lang.ISeq,
       :op :var,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               ;; => :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis,
        :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
        :column 24,
        :line 3},
       :o-tag java.lang.Object,
       :var #'clojure.core/seq,
       :form clojure.core/seq,
       :tag clojure.lang.AFunction,
       :idx 20,
       :arglists ([coll]),
       :assignable? false},
      :op :invoke,
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis,
       :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
       :column 24,
       :line 3},
      :o-tag java.lang.Object,
      :form (clojure.core/seq map__25833),
      :tag clojure.lang.ISeq,
      :idx 19}},
    :op :if,
    :env
    {:context :ctx/expr,
     :locals
     {x
      {:op :binding,
       :name x,
       :init
       {:op :map,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :keys
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :a,
          :form :a}
       ;; =>   {:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :b,
          :form :b}],
        :vals
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 1,
          :form 1}
         {:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 2,
          :form 2}],
        :form {:a 1, :b 2},
        :children [:keys :vals]},
       :form x,
       :local :let,
       :children [:init]},
      map__25833
      {:op :binding,
       :name map__25833,
       :init
       {:op :local,
        :name x,
        :form x,
        :local :let,
        :children [],
        :assignable? false,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis}},
       :form map__25833,
       :local :let,
       :children [:init]}},
     :ns skeptic.analysis,
     :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
     :column 24,
     :line 3},
    :o-tag nil,
    :then
    {:args
     [{:args
       [{:children [],
         :name map__25833__#0,
         :op :local,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {;; => x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis,
          :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
          :column 24,
          :line 3},
         :o-tag clojure.lang.PersistentArrayMap,
         :form map__25833,
         :tag clojure.lang.ISeq,
         :idx 17,
         :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
         :local :let,
         :assignable? false}],
       :children [:fn :args],
       :fn
       {:meta
        {:added "1.0",
         :ns #namespace[clojure.core],
         :name to-array,
         :file "clojure/core.clj",
         :static true,
         :column 1,
         :line 340,
         :tag "[Ljava.lang.Object;",
         :arglists ([coll]),
         :doc
         "Returns an array of Objects containing the contents of coll, which\n  can be any Collection.  Maps to java.util.Collection.toArray()."},
        :return-tag [Ljava.lang.Object;,
        :op :var,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context;; =>  :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis,
         :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
         :column 24,
         :line 3},
        :o-tag java.lang.Object,
        :var #'clojure.core/to-array,
        :form clojure.core/to-array,
        :tag clojure.lang.AFunction,
        :idx 16,
        :arglists ([coll]),
        :assignable? false},
       :op :invoke,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
         ;; =>        {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis,
        :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
        :column 24,
        :line 3},
       :o-tag java.lang.Object,
       :form (clojure.core/to-array map__25833),
       :tag [Ljava.lang.Object;,
       :idx 15}],
     :children [:args],
     :method createAsIfByAssoc,
     :op :static-call,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :local,
         :name x,
         :form x,
         :local :let,
         :children [],
         :assignable? false,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis}},
        :form map__25833,
        :local :let,
        :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag clojure.lang.PersistentArrayMap,
     :class clojure.lang.PersistentArrayMap,
     :form
     (.
      clojure.lang.PersistentArrayMap
      (createAsIfByAssoc (clojure.core/to-array map__25833))),
     :tag clojure.lang.PersistentArrayMap,
     :idx 14,
     :validated? true,
     :raw-forms
     ((clojure.lang.PersistentArrayMap/createAsIfByAssoc
       (clojure.core/to-array map__25833)))},;; => 
    :form
    (if
     (clojure.core/next map__25833)
     (clojure.lang.PersistentArrayMap/createAsIfByAssoc
      (clojure.core/to-array map__25833))
     (if
      (clojure.core/seq map__25833)
      (clojure.core/first map__25833)
      clojure.lang.PersistentArrayMap/EMPTY)),
    :idx 10,
    :test
    {:args
     [{:children [],
       :name map__25833__#0,
       :op :local,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis,
        :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
        :column 24,
        :line 3},
       :o-tag clojure.lang.PersistentArrayMap,
       :form map__25833,
       :tag clojure.lang.ISeq,
       :idx 13,
       :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
       :local :let,
       :assignable? false}],
     :children [:fn :args],
     :fn
     {:meta
      {:added "1.0",
       :ns #namespace[clojure.core],
       :name next,
       :file "clojure/core.clj",
       :static true,
       :column 1,
       :line 57,
       :tag clojure.lang.ISeq,
       :arglists ([coll]),
       :doc
       "Returns a seq of the items after the first. Calls seq on its\n  argument.  If there are no more items, returns nil."},
      :return-ta;; => g clojure.lang.ISeq,
      :op :var,
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis,
       :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
       :column 24,
       :line 3},
      :o-tag java.lang.Object,
      :var #'clojure.core/next,
      :form clojure.core/next,
      :tag clojure.lang.AFunction,
      :idx 12,
      :arglists ([coll]),
      :assignable? false},
     :op :invoke,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
    ;; =>        :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :local,
         :name x,
         :form x,
         :local :let,
         :children [],
         :assignable? false,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis}},
        :form map__25833,
        :local :let,
        :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag java.lang.Object,
     :form (clojure.core/next map__25833),
     :tag clojure.lang.ISeq,
     :idx 11}},
   :form
   (if
    (clojure.core/seq? map__25833)
    (if
     (clojure.core/next map__25833)
     (clojure.lang.PersistentArrayMap/createAsIfByAssoc
      (clojure.core/to-array map__25833))
     (if
      (clojure.core/seq map__25833)
      (clojure.core/first map__25833)
      clojure.lang.PersistentArrayMap/EMPTY))
    map__25833),
   :idx 6,
   :test
   {:op :invoke,
    :form (clojure.core/seq? map__25833),
    :env
    {:context :ctx/expr,
     :locals
     {x
      {:op :binding,
       :name x,
       :init
       {:op :map,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :keys
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :a,
          :form :a}
         {:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :keyword,
          :literal? true,
          :val :b,
          :form :b}],
        :vals
        [{:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 1,
          :form 1}
         {:op :const,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :type :number,
          :literal? true,
          :val 2,
          :form 2}],
        :form {:a 1, :b 2},
        :children [:keys :vals]},
       :form x,
       :local :let,
       :children [:init]},
      map__25833
      {:op :binding,
       :name map__25833,
       :init
       {:op :local,
        :name x,
        :form x,
        :local :let,
        :children [],
        :assignable? false,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skep;; => tic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis}},
       :form map__25833,
       :local :let,
       :children [:init]}},
     :ns skeptic.analysis,
     :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
     :column 24,
     :line 3},
    :fn
    {:meta
     {:added "1.0",
      :ns #namespace[clojure.core],
      :name seq?,
      :file "clojure/core.clj",
      :static true,
      :column 1,
      :line 148,
      :arglists ([x]),
      :doc "Return true if x implements ISeq"},
     :op :var,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :local,
         :name x,
         :form x,
         :local :let,
         :children [],
         :assignable? false,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis}},
        :form map__25833,
        :local :let,
   ;; =>      :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag java.lang.Object,
     :var #'clojure.core/seq?,
     :form clojure.core/seq?,
     :idx 8,
     :arglists ([x]),
     :assignable? false},
    :args
    [{:children [],
      :name map__25833__#0,
      :op :local,
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis,
       :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
       :column 24,
       :line 3},
      :o-tag clojure.lang.PersistentArrayMap,
      :form map__25833,
      :tag clojure.lang.PersistentArrayMap,
      :idx 9,
      :atom #<Atom@1b9926ad: {:tag clojure.lang.PersistentArrayMap}>,
      :local :let,
      :assignable? false}],
    :children [:fn :args],
    :o-tag java.lang.Object,
    :idx 7}},
  :name map__25833__#1,
  :op :binding,
  :env
  {:context :ctx/expr,
   :locals
   {x
    {:op :binding,
     :name x,
     :init
     {:op :map,
      :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
      :keys
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :a,
        :form :a}
       {:op :const,
        :env {:context :ctx/expr, :lo;; => cals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :b,
        :form :b}],
      :vals
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 1,
        :form 1}
       {:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 2,
        :form 2}],
      :form {:a 1, :b 2},
      :children [:keys :vals]},
     :form x,
     :local :let,
     :children [:init]},
    map__25833
    {:op :binding,
     :name map__25833,
     :init
     {:op :local,
      :name x,
      :form x,
      :local :let,
      :children [],
      :assignable? false,
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis}},
     :form map__25833,
     :local :let,
     :children [:init]}},
   :ns skeptic.analysis,
   :column 24,
   :line 3,
   :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*"},
  :o-tag java.lang.Object,
  :form map__25833,
  :tag java.lang.Object,
  :idx 5,
  :atom #<Atom@189bfb82: {:tag java.lang.Object}>,
  :local :let}
 {:children [:init],
  :init
  {:args
   [{:children [],
     :name map__25833__#1,
     :op :local,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :if,
         :form
         (if
          (clojure.core/seq? map__25833)
          (if
           (clojure.core/next map__25833)
           (clojure.lang.PersistentArrayMap/createAsIfByAssoc
            (clojure.core/to-array map__25833))
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY))
          map__25833),
         :env
         {:context :ctx/expr,
          :locals
          {x
      ;; =>      {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :test
         {:op :invoke,
          :form (clojure.core/seq? map__25833),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:;; => op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :fn
          {:op :var,
           :assignable? false,
           :var #'clojure.core/seq?,
           :meta
           {:added "1.0",
            :ns #namespace[clojure.core],
            :name seq?,
            :file "clojure/core.clj",
            :static true,
            :column 1,
            :line 148,
            :arglists ([x]),
            :doc "Return true if x implements ISeq"},
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children ;; => [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :form clojure.core/seq?},
          :args
          [{:op :local,
            :name map__25833,
            :form map__25833,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
      ;; =>              :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}}],
          :children [:fn :args]},
         :then
         {:op :if,
          :form
          (if
           (clojure.core/next map__25833)
           (clojure.lang.PersistentArrayMap/createAsIfByAssoc
            (clojure.core/to-array map__25833))
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY)),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :local;; => s {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :test
          {:op :invoke,
           :form (clojure.core/next map__25833),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
      ;; =>                :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :fn
           {:op :var,
            :assignable? false,
            :var #'clojure.core/next,
            :meta
            {:added "1.0",
             :ns #namespace[clojure.core],
             :name next,
             :file "clojure/core.clj",
             :static true,
             :column 1,
             :line 57,
             :tag clojure.lang.ISeq,
             :arglists ([coll]),
             :doc
             "Returns a seq of the items after the first. Calls seq on its\n  argument.  If there are no more items, returns nil."},
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
        ;; =>               :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :form clojure.core/next},
           :args
           [{:op :local,
             :name map__25833,
             :form map__25833,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :litera;; => l? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}}],
           :children [:fn :args]},
          :then
          {:form
           (.
            clojure.lang.PersistentArrayMap
            (createAsIfByAssoc (clojure.core/to-array map__25833))),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :target
           {:op :const,
         ;; =>    :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :type :class,
            :literal? true,
            :val clojure.lang.PersistentArrayMap,
            :form clojure.lang.PersistentArrayMap},
           :op :host-call,
           :method createAsIfByAssoc,
           :args
           [{:op :invoke,
             :form (clojure.core/to-array map__25833),
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.an;; => alysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :fn
             {:op :var,
              :assignable? false,
              :var #'clojure.core/to-array,
              :meta
              {:added "1.0",
               :ns #namespace[clojure.core],
               :name to-array,
               :file "clojure/core.clj",
               :static true,
               :column 1,
               :line 340,
               :tag "[Ljava.lang.Object;",
               :arglists ([coll]),
               :doc
               "Returns an array of Objects containing the contents of coll, which\n  can be any Collection.  Maps to java.util.Collection.toArray()."},
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :bind;; => ing,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis},
              :form clojure.core/to-array},
             :args
             [{:op :local,
               :name map__25833,
               :form map__25833,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
         ;; =>          :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]},
                 map__25833
                 {:op :binding,
                  :name map__25833,
                  :init
                  {:op :local,
                   :name x,
                   :form x,
                   :local :let,
                   :children [],
                   :assignable? false,
                   :env
                   {:context :ctx/expr,
                    :locals
                    {x
                     {:op :binding,
                      :name x,
                      :init
                      {:op :map,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :keys
                       [{:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :keyword,
                         :literal? true,
                         :val :a,
                         :form :a}
                        {:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :keyword,
                         :literal? true,
                         :val :b,
                         :form :b}],
                       :vals
                       [{:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :number,
                         :literal? true,
                         :val 1,
                         :form 1}
                        {:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :number,
                         :literal? true,
                         :val 2,
                         :form 2}],
                       :form {:a 1, :b 2},
                       :children [:keys :vals]},
                      :form x,
                      :local :let,
                      :children [:init]}},
                    :ns skeptic.analysis}},
                  :form map__25833,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}}],
             :children [:fn :args]}],
           :children [:target :args],
           :raw-forms
           ((clojure.lang.PersistentArrayMap/crea;; => teAsIfByAssoc
             (clojure.core/to-array map__25833)))},
          :else
          {:op :if,
           :form
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :test
           {:op :invoke,
            :form (clojure.core/seq map__25833),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
              ;; =>     :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :fn
            {:op :var,
             :assignable? false,
             :var #'clojure.core/seq,
             :meta
             {:added "1.0",
              :ns #namespace[clojure.core],
              :name seq,
              :file "clojure/core.clj",
              :static true,
              :column 1,
              :line 128,
              :tag clojure.lang.ISeq,
              :arglists ([coll]),
              :doc
              "Returns a seq on the collection. If the collection is\n    empty, returns nil.  (seq nil) returns nil. seq also works on\n    Strings, native Java arrays (of reference types) and any objects\n    that implement Iterable. Note that seqs cache values, thus seq\n    should not be used on any Iterable whose iterator repeatedly\n    returns the same mutable object."},
             :env
             {:context :ctx/expr,
              :locals
              ;; => {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :form clojure.core/seq},
            :args
            [{:op :local,
              :name map__25833,
              :form map__25833,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:;; => op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}}],
            :children [:fn :args]},
           :then
           {:op :invoke,
            :form (clojure.core/first map__25833),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
;; =>                   :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :fn
            {:op :var,
             :assignable? false,
             :var #'clojure.core/first,
             :meta
             {:added "1.0",
              :ns #namespace[clojure.core],
              :name first,
              :file "clojure/core.clj",
              :static true,
              :column 1,
              :line 49,
              :arglists ([coll]),
              :doc
              "Returns the first item in the collection. Calls seq on its\n    argument. If coll is nil, returns nil."},
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
         ;; =>          {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :form clojure.core/first},
            :args
            [{:op :local,
              :name map__25833,
              :form map__25833,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
     ;; =>                :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}}],
            :children [:fn :args]},
           :else
           {:form (. clojure.lang.PersistentArrayMap -EMPTY),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
       ;; =>            :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :target
            {:op :const,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :;; => binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :type :class,
             :literal? true,
             :val clojure.lang.PersistentArrayMap,
             :form clojure.lang.PersistentArrayMap},
            :op :host-field,
            :assignable? true,
            :field EMPTY,
            :children [:target],
            :raw-forms (clojure.lang.PersistentArrayMap/EMPTY)},
           :children [:test :then :else]},
          :children [:test :then :else]},
         :else
         {:op :local,
          :name map__25833,
          :form map__25833,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :b;; => inding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :children [:test :then :else]},
        :form map__25833,
        :local :let,
        :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag java.lang.Object,
     :form map__25833,
     :tag java.lang.Object,
     :idx 29,
     :atom #<Atom@189bfb82: {:tag java.lang.Object}>,
     :local :let,
     :assignable? false}
    {:val :a,
     :type :keyword,
     :op :const,
     :env
     {:context :ctx/expr,
      :locals
      {x
       {:op :binding,
        :name x,
        :init
        {:op :map,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :keys
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :a,
           :form :a}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :keyword,
           :literal? true,
           :val :b,
           :form :b}],
         :vals
         [{:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 1,
           :form 1}
          {:op :const,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :type :number,
           :literal? true,
           :val 2,
           :form 2}],
         :form {:a 1, :b 2},
         :children [:keys :vals]},
        :form x,
        :local :let,
        :children [:init]},
       map__25833
       {:op :binding,
        :name map__25833,
        :init
        {:op :if,
         :form
         (if
          (clojure.core/seq? map__25833)
          (if
           (clojure.core/next map__25833)
           (clojure.lang.PersistentArrayMap/createAsIfByAssoc
            (clojure.core/to-array map__25833))
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY))
          map__25833),
         :env
         {:context :ct;; => x/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :test
         {:op :invoke,
          :form (clojure.core/seq? map__25833),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val ;; => 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :fn
          {:op :var,
           :assignable? false,
           :var #'clojure.core/seq?,
           :meta
           {:added "1.0",
            :ns #namespace[clojure.core],
            :name seq?,
            :file "clojure/core.clj",
            :static true,
            :column 1,
            :line 148,
            :arglists ([x]),
            :doc "Return true if x implements ISeq"},
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
      ;; =>         :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :form clojure.core/seq?},
          :args
          [{:op :local,
            :name map__25833,
            :form map__25833,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
              ;; =>    {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}}],
          :children [:fn :args]},
         :then
         {:op :if,
          :form
          (if
           (clojure.core/next map__25833)
           (clojure.lang.PersistentArrayMap/createAsIfByAssoc
            (clojure.core/to-array map__25833))
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY)),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
  ;; =>                   {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :test
          {:op :invoke,
           :form (clojure.core/next map__25833),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis;; => },
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :fn
           {:op :var,
            :assignable? false,
            :var #'clojure.core/next,
            :meta
            {:added "1.0",
             :ns #namespace[clojure.core],
             :name next,
             :file "clojure/core.clj",
             :static true,
             :column 1,
             :line 57,
             :tag clojure.lang.ISeq,
             :arglists ([coll]),
             :doc
             "Returns a seq of the items after the first. Calls seq on its\n  argument.  If there are no more items, returns nil."},
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
 ;; =>                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :form clojure.core/next},
           :args
           [{:op :local,
             :name map__25833,
             :form map__25833,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       ;; => :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}}],
           :children [:fn :args]},
          :then
          {:form
           (.
            clojure.lang.PersistentArrayMap
            (createAsIfByAssoc (clojure.core/to-array map__25833))),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
       ;; =>     :target
           {:op :const,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :type :class,
            :literal? true,
            :val clojure.lang.PersistentArrayMap,
            :form clojure.lang.PersistentArrayMap},
           :op :host-call,
           :method createAsIfByAssoc,
           :args
           [{:op :invoke,
             :form (clojure.core/to-array map__25833),
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:;; => context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :fn
             {:op :var,
              :assignable? false,
              :var #'clojure.core/to-array,
              :meta
              {:added "1.0",
               :ns #namespace[clojure.core],
               :name to-array,
               :file "clojure/core.clj",
               :static true,
               :column 1,
               :line 340,
               :tag "[Ljava.lang.Object;",
               :arglists ([coll]),
               :doc
               "Returns an array of Objects containing the contents of coll, which\n  can be any Collection.  Maps to java.util.Collection.toArray()."},
              :env
              {:context :ctx/expr,
               :locals;; => 
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis},
              :form clojure.core/to-array},
             :args
             [{:op :local,
               :name map__25833,
               :form map__25833,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :;; => binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]},
                 map__25833
                 {:op :binding,
                  :name map__25833,
                  :init
                  {:op :local,
                   :name x,
                   :form x,
                   :local :let,
                   :children [],
                   :assignable? false,
                   :env
                   {:context :ctx/expr,
                    :locals
                    {x
                     {:op :binding,
                      :name x,
                      :init
                      {:op :map,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :keys
                       [{:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :keyword,
                         :literal? true,
                         :val :a,
                         :form :a}
                        {:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :keyword,
                         :literal? true,
                         :val :b,
                         :form :b}],
                       :vals
                       [{:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :number,
                         :literal? true,
                         :val 1,
                         :form 1}
                        {:op :const,
                         :env
                         {:context :ctx/expr,
                          :locals {},
                          :ns skeptic.analysis},
                         :type :number,
                         :literal? true,
                         :val 2,
                         :form 2}],
                       :form {:a 1, :b 2},
                       :children [:keys :vals]},
                      :form x,
                      :local :let,
                      :children [:init]}},
                    :ns skeptic.analysis}},
                  :form map__25833,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}}],
             :children [:fn :args]}],
           :children [:target :args],
           :raw-forms
    ;; =>        ((clojure.lang.PersistentArrayMap/createAsIfByAssoc
             (clojure.core/to-array map__25833)))},
          :else
          {:op :if,
           :form
           (if
            (clojure.core/seq map__25833)
            (clojure.core/first map__25833)
            clojure.lang.PersistentArrayMap/EMPTY),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :test
           {:op :invoke,
            :form (clojure.core/seq map__25833),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys;; => 
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :fn
            {:op :var,
             :assignable? false,
             :var #'clojure.core/seq,
             :meta
             {:added "1.0",
              :ns #namespace[clojure.core],
              :name seq,
              :file "clojure/core.clj",
              :static true,
              :column 1,
              :line 128,
              :tag clojure.lang.ISeq,
              :arglists ([coll]),
              :doc
              "Returns a seq on the collection. If the collection is\n    empty, returns nil.  (seq nil) returns nil. seq also works on\n    Strings, native Java arrays (of reference types) and any objects\n    that implement Iterable. Note that seqs cache values, thus seq\n    should not be used on any Iterable whose iterator repeatedly\n    returns the same mutable object."},
             :env
             {:context :c;; => tx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :form clojure.core/seq},
            :args
            [{:op :local,
              :name map__25833,
              :form map__25833,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
;; =>                   :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}}],
            :children [:fn :args]},
           :then
           {:op :invoke,
            :form (clojure.core/first map__25833),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                ;; =>   :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :fn
            {:op :var,
             :assignable? false,
             :var #'clojure.core/first,
             :meta
             {:added "1.0",
              :ns #namespace[clojure.core],
              :name first,
              :file "clojure/core.clj",
              :static true,
              :column 1,
              :line 49,
              :arglists ([coll]),
              :doc
              "Returns the first item in the collection. Calls seq on its\n    argument. If coll is nil, returns nil."},
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :v;; => al :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :form clojure.core/first},
            :args
            [{:op :local,
              :name map__25833,
              :form map__25833,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keywo;; => rd,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]},
                map__25833
                {:op :binding,
                 :name map__25833,
                 :init
                 {:op :local,
                  :name x,
                  :form x,
                  :local :let,
                  :children [],
                  :assignable? false,
                  :env
                  {:context :ctx/expr,
                   :locals
                   {x
                    {:op :binding,
                     :name x,
                     :init
                     {:op :map,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :keys
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :a,
                        :form :a}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :keyword,
                        :literal? true,
                        :val :b,
                        :form :b}],
                      :vals
                      [{:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 1,
                        :form 1}
                       {:op :const,
                        :env
                        {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                        :type :number,
                        :literal? true,
                        :val 2,
                        :form 2}],
                      :form {:a 1, :b 2},
                      :children [:keys :vals]},
                     :form x,
                     :local :let,
                     :children [:init]}},
                   :ns skeptic.analysis}},
                 :form map__25833,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}}],
            :children [:fn :args]},
           :else
           {:form (. clojure.lang.PersistentArrayMap -EMPTY),
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :numb;; => er,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :target
            {:op :const,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
  ;; =>              map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis},
             :type :class,
             :literal? true,
             :val clojure.lang.PersistentArrayMap,
             :form clojure.lang.PersistentArrayMap},
            :op :host-field,
            :assignable? true,
            :field EMPTY,
            :children [:target],
            :raw-forms (clojure.lang.PersistentArrayMap/EMPTY)},
           :children [:test :then :else]},
          :children [:test :then :else]},
         :else
         {:op :local,
          :name map__25833,
          :form map__25833,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init];; => },
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :children [:test :then :else]},
        :form map__25833,
        :local :let,
        :children [:init]}},
      :ns skeptic.analysis,
      :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
      :column 24,
      :line 3},
     :o-tag clojure.lang.Keyword,
     :literal? true,
     :form :a,
     :tag java.lang.Object,
     :idx 30}],
   :children [:args],
   :method get,
   :op :static-call,
   :env
   {:context :ctx/expr,
    :locals
    {x
     {:op :binding,
      :name x,
      :init
      {:op :map,
       :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
       :keys
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :a,
         :form :a}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :keyword,
         :literal? true,
         :val :b,
         :form :b}],
       :vals
       [{:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 1,
         :form 1}
        {:op :const,
         :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         :type :number,
         :literal? true,
         :val 2,
         :form 2}],
       :form {:a 1, :b 2},
       :children [:keys :vals]},
      :form x,
      :local :let,
      :children [:init]},
     map__25833
     {:op :binding,
      :name map__25833,
      :init
      {:op :if,
       :form
       (if
        (clojure.core/seq? map__25833)
        (if
         (clojure.core/next map__25833)
         (clojure.lang.PersistentArrayMap/createAsIfByAssoc
          (clojure.core/to-array map__25833))
         (if
          (clojure.core/seq map__25833)
          (clojure.core/first map__25833)
          clojure.lang.PersistentArrayMap/EMPTY))
        map__25833),
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ct;; => x/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis},
       :test
       {:op :invoke,
        :form (clojure.core/seq? map__25833),
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map_;; => _25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :fn
        {:op :var,
         :assignable? false,
         :var #'clojure.core/seq?,
         :meta
         {:added "1.0",
          :ns #namespace[clojure.core],
          :name seq?,
          :file "clojure/core.clj",
          :static true,
          :column 1,
          :line 148,
          :arglists ([x]),
          :doc "Return true if x implements ISeq"},
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
 ;; =>                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :form clojure.core/seq?},
        :args
        [{:op :local,
          :name map__25833,
          :form map__25833,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
;; =>                    {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}}],
        :children [:fn :args]},
       :then
       {:op :if,
        :form
        (if
         (clojure.core/next map__25833)
         (clojure.lang.PersistentArrayMap/createAsIfByAssoc
          (clojure.core/to-array map__25833))
         (if
          (clojure.core/seq map__25833)
          (clojure.core/first map__25833)
          clojure.lang.PersistentArrayMap/EMPTY)),
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :test
        {:op :invoke,
         :form (clojure.core/next map__25;; => 833),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :fn
         {:op :var,
          :assignable? false,
          :var #'clojure.core/next,
          :meta
          {:added "1.0",
           :ns #namespace[clojure.core],
           :name next,
           :file "clojure/core.clj",
           :static true,
           :column 1,
           :line 57,
           :tag clojure.lang.ISeq,
           :arglists ([coll]),
           :doc
           "Returns a seq of the items after the first. Calls seq on its\n  argument.  If there are no more items, returns nil."},
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
    ;; =>             :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :form clojure.core/next},
         :args
         [{:op :local,
           :name map__25833,
           :form map__25833,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                ;; =>  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}}],
         :children [:fn :args]},
        :then
        {:form
         (.
          clojure.lang.PersistentArrayMap
          (createAsIfByAssoc (clojure.core/to-array map__25833))),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? fals;; => e,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :target
         {:op :const,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
               ;; =>    [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :type :class,
          :literal? true,
          :val clojure.lang.PersistentArrayMap,
          :form clojure.lang.PersistentArrayMap},
         :op :host-call,
         :method createAsIfByAssoc,
         :args
         [{:op :invoke,
           :form (clojure.core/to-array map__25833),
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type ;; => :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :fn
           {:op :var,
            :assignable? false,
            :var #'clojure.core/to-array,
            :meta
            {:added "1.0",
             :ns #namespace[clojure.core],
             :name to-array,
             :file "clojure/core.clj",
             :static true,
             :column 1,
             :line 340,
             :tag "[Ljava.lang.Object;",
             :arglists ([coll]),
             :doc
             "Returns an array of Objects containing the contents of coll, which\n  can be any Collection.  Maps to java.util.Collection.toArray()."},
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :n;; => umber,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis},
            :form clojure.core/to-array},
           :args
           [{:op :local,
             :name map__25833,
             :form map__25833,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]},
               map__25833
               {:op :binding,
                :name map__25833,
                :init
                {:op :local,
                 :name x,
                 :form x,
                 :local :let,
                 :children [],
                 :assignable? false,
                 :env
                 {:context :ctx/expr,
                  :locals
                  {x
                   {:op :binding,
                    :name x,
                    :init
                    {:op :map,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :keys
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :a,
                       :form :a}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :keyword,
                       :literal? true,
                       :val :b,
                       :form :b}],
                     :vals
                     [{:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 1,
                       :form 1}
                      {:op :const,
                       :env
                       {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                       :type :number,
                       :literal? true,
                       :val 2,
                       :form 2}],
                     :form {:a 1, :b 2},
                     :children [:keys :vals]},
                    :form x,
                    :local ;; => :let,
                    :children [:init]}},
                  :ns skeptic.analysis}},
                :form map__25833,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}}],
           :children [:fn :args]}],
         :children [:target :args],
         :raw-forms
         ((clojure.lang.PersistentArrayMap/createAsIfByAssoc
           (clojure.core/to-array map__25833)))},
        :else
        {:op :if,
         :form
         (if
          (clojure.core/seq map__25833)
          (clojure.core/first map__25833)
          clojure.lang.PersistentArrayMap/EMPTY),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :test
         {:op :invoke,
          :form (clojure.core/seq map__25833),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             ;; =>  :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :fn
          {:op :var,
           :assignable? false,
           :var #'clojure.core/seq,
           :meta
           {:added "1.0",
            :ns #namespace[clojure.core],
            :name seq,
            :file "clojure/core.clj",
            :static true,
            :column 1,
            :line 128,
            :tag clojure.lang.ISeq,
            :arglists ([coll]),
            :doc
            "Returns a seq on the collection. If the collection is\n    empty, returns nil.  (seq nil) returns nil. seq also works on\n    Strings, native Java arrays (of reference types) and any objects\n    that implement Iterable. Note that seqs cache values, thus seq\n    should not be used on any Iterable whose iterator repeatedly\n    returns the same mutable object."},
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            ;; =>    :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :form clojure.core/seq},
          :args
          [{:op :local,
            :name map__25833,
            :form map__25833,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :;; => form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}}],
          :children [:fn :args]},
         :then
         {:op :invoke,
          :form (clojure.core/first map__25833),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :;; => b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :fn
          {:op :var,
           :assignable? false,
           :var #'clojure.core/first,
           :meta
           {:added "1.0",
            :ns #namespace[clojure.core],
            :name first,
            :file "clojure/core.clj",
            :static true,
            :column 1,
            :line 49,
            :arglists ([coll]),
            :doc
            "Returns the first item in the collection. Calls seq on its\n    argument. If coll is nil, returns nil."},
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
   ;; =>             :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :form clojure.core/first},
          :args
          [{:op :local,
            :name map__25833,
            :form map__25833,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, ;; => :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}}],
          :children [:fn :args]},
         :else
         {:form (. clojure.lang.PersistentArrayMap -EMPTY),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
            ;; =>       :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :target
          {:op :const,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
               ;; =>    :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :type :class,
           :literal? true,
           :val clojure.lang.PersistentArrayMap,
           :form clojure.lang.PersistentArrayMap},
          :op :host-field,
          :assignable? true,
          :field EMPTY,
          :children [:target],
          :raw-forms (clojure.lang.PersistentArrayMap/EMPTY)},
         :children [:test :then :else]},
        :children [:test :then :else]},
       :else
       {:op :local,
        :name map__25833,
        :form map__25833,
        :local :let,
        :children [],
        :assignable? false,
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis}},
       :children [:test :then :else]},
      :form map__25833,
      :local :let,
      :children [:init]}},
    :ns skeptic.analysis,
    :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*",
    :column 24,
    :line 3},
   :o-tag java.lang.Object,
   :class clojure.;; => lang.RT,
   :form (. clojure.lang.RT (clojure.core/get map__25833 :a)),
   :tag java.lang.Object,
   :idx 28,
   :validated? true,
   :raw-forms ((clojure.core/get map__25833 :a))},
  :name a__#0,
  :op :binding,
  :env
  {:context :ctx/expr,
   :locals
   {x
    {:op :binding,
     :name x,
     :init
     {:op :map,
      :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
      :keys
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :a,
        :form :a}
       {:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :keyword,
        :literal? true,
        :val :b,
        :form :b}],
      :vals
      [{:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 1,
        :form 1}
       {:op :const,
        :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
        :type :number,
        :literal? true,
        :val 2,
        :form 2}],
      :form {:a 1, :b 2},
      :children [:keys :vals]},
     :form x,
     :local :let,
     :children [:init]},
    map__25833
    {:op :binding,
     :name map__25833,
     :init
     {:op :if,
      :form
      (if
       (clojure.core/seq? map__25833)
       (if
        (clojure.core/next map__25833)
        (clojure.lang.PersistentArrayMap/createAsIfByAssoc
         (clojure.core/to-array map__25833))
        (if
         (clojure.core/seq map__25833)
         (clojure.core/first map__25833)
         clojure.lang.PersistentArrayMap/EMPTY))
       map__25833),
      :env
      {:context :ctx/expr,
       :locals
       {x
        {:op :binding,
         :name x,
         :init
         {:op :map,
          :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
          :keys
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :a,
            :form :a}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :keyword,
            :literal? true,
            :val :b,
            :form :b}],
          :vals
          [{:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 1,
            :form 1}
           {:op :const,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :type :number,
            :literal? true,
            :val 2,
            :form 2}],
          :form {:a 1, :b 2},
          :children [:keys :vals]},
         :form x,
         :local :let,
         :children [:init]},
        map__25833
        {:op :binding,
         :name map__25833,
         :init
         {:op :local,
          :name x,
          :form x,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysi;; => s},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}},
         :form map__25833,
         :local :let,
         :children [:init]}},
       :ns skeptic.analysis},
      :test
      {:op :invoke,
       :form (clojure.core/seq? map__25833),
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis},
       :fn
       {:op :var,
        :assignable? false,
        :var #'clojure.core/seq?,
        :meta
        {:added "1.0",
         :ns #namespace[clojure.core],
         :name seq?,
         :file "clojure/core.clj",
         :static true,
         :column 1,
         :line 148,
         :arglists ([x]),
         :doc "Return true if x implements ISeq"},
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:contex;; => t :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :form clojure.core/seq?},
       :args
       [{:op :local,
         :name map__25833,
         :form map__25833,
         :local :let,
         :children [],
         :assignable? false,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
   ;; =>           :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis}}],
       :children [:fn :args]},
      :then
      {:op :if,
       :form
       (if
        (clojure.core/next map__25833)
        (clojure.lang.PersistentArrayMap/createAsIfByAssoc
         (clojure.core/to-array map__25833))
        (if
         (clojure.core/seq map__25833)
         (clojure.core/first map__25833)
         clojure.lang.PersistentArrayMap/EMPTY)),
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
           :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:con;; => text :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis},
       :test
       {:op :invoke,
        :form (clojure.core/next map__25833),
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :;; => let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :fn
        {:op :var,
         :assignable? false,
         :var #'clojure.core/next,
         :meta
         {:added "1.0",
          :ns #namespace[clojure.core],
          :name next,
          :file "clojure/core.clj",
          :static true,
          :column 1,
          :line 57,
          :tag clojure.lang.ISeq,
          :arglists ([coll]),
          :doc
          "Returns a seq of the items after the first. Calls seq on its\n  argument.  If there are no more items, returns nil."},
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :form clojure.core/next},
        :args
        [{:op :local,
          :name map__25833,
          :form map__25833,
          :local :let,
          :children [],
          :assignable? false,
          :env
          {:context :ctx/expr,
           :loca;; => ls
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis}}],
        :children [:fn :args]},
       :then
       {:form
        (.
         clojure.lang.PersistentArrayMap
         (createAsIfByAssoc (clojure.core/to-array map__25833))),
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
           ;; =>  :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :target
        {:op :const,
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
  ;; =>               {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :type :class,
         :literal? true,
         :val clojure.lang.PersistentArrayMap,
         :form clojure.lang.PersistentArrayMap},
        :op :host-call,
        :method createAsIfByAssoc,
        :args
        [{:op :invoke,
          :form (clojure.core/to-array map__25833),
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    ;; => :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :fn
          {:op :var,
           :assignable? false,
           :var #'clojure.core/to-array,
           :meta
           {:added "1.0",
            :ns #namespace[clojure.core],
            :name to-array,
            :file "clojure/core.clj",
            :static true,
            :column 1,
            :line 340,
            :tag "[Ljava.lang.Object;",
            :arglists ([coll]),
            :doc
            "Returns an array of Objects containing the contents of coll, which\n  can be any Collection.  Maps to java.util.Collection.toArray()."},
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:c;; => ontext :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis},
           :form clojure.core/to-array},
          :args
          [{:op :local,
            :name map__25833,
            :form map__25833,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]},
              map__25833
              {:op :binding,
               :name map__25833,
               :init
               {:op :local,
                :name x,
                :form x,
                :local :let,
                :children [],
                :assignable? false,
                :env
                {:context :ctx/expr,
                 :locals
                 {x
                  {:op :binding,
                   :name x,
                   :init
                   {:op :map,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :keys
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :a,
                      :form :a}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :keyword,
                      :literal? true,
                      :val :b,
                      :form :b}],
                    :vals
                    [{:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
                      :literal? true,
                      :val 1,
                      :form 1}
                     {:op :const,
                      :env
                      {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                      :type :number,
      ;; =>                 :literal? true,
                      :val 2,
                      :form 2}],
                    :form {:a 1, :b 2},
                    :children [:keys :vals]},
                   :form x,
                   :local :let,
                   :children [:init]}},
                 :ns skeptic.analysis}},
               :form map__25833,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}}],
          :children [:fn :args]}],
        :children [:target :args],
        :raw-forms
        ((clojure.lang.PersistentArrayMap/createAsIfByAssoc
          (clojure.core/to-array map__25833)))},
       :else
       {:op :if,
        :form
        (if
         (clojure.core/seq map__25833)
         (clojure.core/first map__25833)
         clojure.lang.PersistentArrayMap/EMPTY),
        :env
        {:context :ctx/expr,
         :locals
         {x
          {:op :binding,
           :name x,
           :init
           {:op :map,
            :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
            :keys
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :a,
              :form :a}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :keyword,
              :literal? true,
              :val :b,
              :form :b}],
            :vals
            [{:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 1,
              :form 1}
             {:op :const,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :type :number,
              :literal? true,
              :val 2,
              :form 2}],
            :form {:a 1, :b 2},
            :children [:keys :vals]},
           :form x,
           :local :let,
           :children [:init]},
          map__25833
          {:op :binding,
           :name map__25833,
           :init
           {:op :local,
            :name x,
            :form x,
            :local :let,
            :children [],
            :assignable? false,
            :env
            {:context :ctx/expr,
             :locals
             {x
              {:op :binding,
               :name x,
               :init
               {:op :map,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :keys
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :a,
                  :form :a}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :keyword,
                  :literal? true,
                  :val :b,
                  :form :b}],
                :vals
                [{:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 1,
                  :form 1}
                 {:op :const,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :type :number,
                  :literal? true,
                  :val 2,
                  :form 2}],
                :form {:a 1, :b 2},
                :children [:keys :vals]},
               :form x,
               :local :let,
               :children [:init]}},
             :ns skeptic.analysis}},
           :form map__25833,
           :local :let,
           :children [:init]}},
         :ns skeptic.analysis},
        :test
        {:op :invoke,
         :form (clojure.core/seq map__25833),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
;; =>             {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :fn
         {:op :var,
          :assignable? false,
          :var #'clojure.core/seq,
          :meta
          {:added "1.0",
           :ns #namespace[clojure.core],
           :name seq,
           :file "clojure/core.clj",
           :static true,
           :column 1,
           :line 128,
           :tag clojure.lang.ISeq,
           :arglists ([coll]),
           :doc
           "Returns a seq on the collection. If the collection is\n    empty, returns nil.  (seq nil) returns nil. seq also works on\n    Strings, native Java arrays (of reference types) and any objects\n    that implement Iterable. Note that seqs cache values, thus seq\n    should not be used on any Iterable whose iterator repeatedly\n    returns the same mutable object."},
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :lo;; => cals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :form clojure.core/seq},
         :args
         [{:op :local,
           :name map__25833,
           :form map__25833,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? tru;; => e,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}}],
         :children [:fn :args]},
        :then
        {:op :invoke,
         :form (clojure.core/first map__25833),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [;; => ],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :fn
         {:op :var,
          :assignable? false,
          :var #'clojure.core/first,
          :meta
          {:added "1.0",
           :ns #namespace[clojure.core],
           :name first,
           :file "clojure/core.clj",
           :static true,
           :column 1,
           :line 49,
           :arglists ([coll]),
           :doc
           "Returns the first item in the collection. Calls seq on its\n    argument. If coll is nil, returns nil."},
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :;; => locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :form clojure.core/first},
         :args
         [{:op :local,
           :name map__25833,
           :form map__25833,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]},
             map__25833
             {:op :binding,
              :name map__25833,
              :init
              {:op :local,
               :name x,
               :form x,
               :local :let,
               :children [],
               :assignable? false,
               :env
               {:context :ctx/expr,
                :locals
                {x
                 {:op :binding,
                  :name x,
                  :init
                  {:op :map,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :keys
                   [{:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :a,
                     :form :a}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :keyword,
                     :literal? true,
                     :val :b,
                     :form :b}],
                   :vals
                   [{:op :const,
                ;; =>      :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 1,
                     :form 1}
                    {:op :const,
                     :env
                     {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                     :type :number,
                     :literal? true,
                     :val 2,
                     :form 2}],
                   :form {:a 1, :b 2},
                   :children [:keys :vals]},
                  :form x,
                  :local :let,
                  :children [:init]}},
                :ns skeptic.analysis}},
              :form map__25833,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}}],
         :children [:fn :args]},
        :else
        {:form (. clojure.lang.PersistentArrayMap -EMPTY),
         :env
         {:context :ctx/expr,
          :locals
          {x
           {:op :binding,
            :name x,
            :init
            {:op :map,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :keys
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :a,
               :form :a}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :keyword,
               :literal? true,
               :val :b,
               :form :b}],
             :vals
             [{:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 1,
               :form 1}
              {:op :const,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :type :number,
               :literal? true,
               :val 2,
               :form 2}],
             :form {:a 1, :b 2},
             :children [:keys :vals]},
            :form x,
            :local :let,
            :children [:init]},
           map__25833
           {:op :binding,
            :name map__25833,
            :init
            {:op :local,
             :name x,
             :form x,
             :local :let,
             :children [],
             :assignable? false,
             :env
             {:context :ctx/expr,
              :locals
              {x
               {:op :binding,
                :name x,
                :init
                {:op :map,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :keys
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :a,
                   :form :a}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :keyword,
                   :literal? true,
                   :val :b,
                   :form :b}],
                 :vals
                 [{:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 1,
                   :form 1}
                  {:op :const,
                   :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                   :type :number,
                   :literal? true,
                   :val 2,
                   :form 2}],
                 :form {:a 1, :b 2},
                 :children [:keys :vals]},
                :form x,
                :local :let,
                :children [:init]}},
              :ns skeptic.analysis}},
            :form map__25833,
            :local :let,
            :children [:init]}},
          :ns skeptic.analysis},
         :target
   ;; =>       {:op :const,
          :env
          {:context :ctx/expr,
           :locals
           {x
            {:op :binding,
             :name x,
             :init
             {:op :map,
              :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
              :keys
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :a,
                :form :a}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :keyword,
                :literal? true,
                :val :b,
                :form :b}],
              :vals
              [{:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 1,
                :form 1}
               {:op :const,
                :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                :type :number,
                :literal? true,
                :val 2,
                :form 2}],
              :form {:a 1, :b 2},
              :children [:keys :vals]},
             :form x,
             :local :let,
             :children [:init]},
            map__25833
            {:op :binding,
             :name map__25833,
             :init
             {:op :local,
              :name x,
              :form x,
              :local :let,
              :children [],
              :assignable? false,
              :env
              {:context :ctx/expr,
               :locals
               {x
                {:op :binding,
                 :name x,
                 :init
                 {:op :map,
                  :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                  :keys
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :a,
                    :form :a}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :keyword,
                    :literal? true,
                    :val :b,
                    :form :b}],
                  :vals
                  [{:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 1,
                    :form 1}
                   {:op :const,
                    :env
                    {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                    :type :number,
                    :literal? true,
                    :val 2,
                    :form 2}],
                  :form {:a 1, :b 2},
                  :children [:keys :vals]},
                 :form x,
                 :local :let,
                 :children [:init]}},
               :ns skeptic.analysis}},
             :form map__25833,
             :local :let,
             :children [:init]}},
           :ns skeptic.analysis},
          :type :class,
          :literal? true,
          :val clojure.lang.PersistentArrayMap,
          :form clojure.lang.PersistentArrayMap},
         :op :host-field,
         :assignable? true,
         :field EMPTY,
         :children [:target],
         :raw-forms (clojure.lang.PersistentArrayMap/EMPTY)},
        :children [:test :then :else]},
       :children [:test :then :else]},
      :else
      {:op :local,
       :name map__25833,
       :form map__25833,
       :local :let,
       :children [],
       :assignable? false,
       :env
       {:context :ctx/expr,
        :locals
        {x
         {:op :binding,
          :name x,
          :init
          {:op :map,
           :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
         ;; =>   :keys
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :a,
             :form :a}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :keyword,
             :literal? true,
             :val :b,
             :form :b}],
           :vals
           [{:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 1,
             :form 1}
            {:op :const,
             :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
             :type :number,
             :literal? true,
             :val 2,
             :form 2}],
           :form {:a 1, :b 2},
           :children [:keys :vals]},
          :form x,
          :local :let,
          :children [:init]},
         map__25833
         {:op :binding,
          :name map__25833,
          :init
          {:op :local,
           :name x,
           :form x,
           :local :let,
           :children [],
           :assignable? false,
           :env
           {:context :ctx/expr,
            :locals
            {x
             {:op :binding,
              :name x,
              :init
              {:op :map,
               :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
               :keys
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :a,
                 :form :a}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :keyword,
                 :literal? true,
                 :val :b,
                 :form :b}],
               :vals
               [{:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 1,
                 :form 1}
                {:op :const,
                 :env {:context :ctx/expr, :locals {}, :ns skeptic.analysis},
                 :type :number,
                 :literal? true,
                 :val 2,
                 :form 2}],
               :form {:a 1, :b 2},
               :children [:keys :vals]},
              :form x,
              :local :let,
              :children [:init]}},
            :ns skeptic.analysis}},
          :form map__25833,
          :local :let,
          :children [:init]}},
        :ns skeptic.analysis}},
      :children [:test :then :else]},
     :form map__25833,
     :local :let,
     :children [:init]}},
   :ns skeptic.analysis,
   :column 24,
   :line 3,
   :file "*cider-repl skeptic/skeptic:localhost:44277(clj)*"},
  :o-tag java.lang.Object,
  :form a,
  :tag java.lang.Object,
  :idx 27,
  :atom #<Atom@1dc131c6: {:tag java.lang.Object}>,
  :local :let}]
