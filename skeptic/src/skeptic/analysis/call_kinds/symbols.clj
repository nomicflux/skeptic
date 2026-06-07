(ns skeptic.analysis.call-kinds.symbols
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang Util]))

(def ^:private seq-call-syms '#{clojure.core/seq seq})
(def ^:private merge-call-syms '#{clojure.core/merge merge})
(def ^:private contains-call-syms '#{clojure.core/contains? contains? contains})
(def ^:private get-call-syms '#{clojure.core/get get})
(def ^:private assoc-call-syms '#{clojure.core/assoc assoc})
(def ^:private dissoc-call-syms '#{clojure.core/dissoc dissoc})
(def ^:private update-call-syms '#{clojure.core/update update})
(def ^:private first-call-syms '#{clojure.core/first first})
(def ^:private second-call-syms '#{clojure.core/second second})
(def ^:private last-call-syms '#{clojure.core/last last})
(def ^:private nth-call-syms '#{clojure.core/nth nth})
(def ^:private rest-call-syms '#{clojure.core/rest rest})
(def ^:private butlast-call-syms '#{clojure.core/butlast butlast})
(def ^:private drop-last-call-syms '#{clojure.core/drop-last drop-last})
(def ^:private take-call-syms '#{clojure.core/take take})
(def ^:private drop-call-syms '#{clojure.core/drop drop})
(def ^:private take-while-call-syms '#{clojure.core/take-while take-while})
(def ^:private drop-while-call-syms '#{clojure.core/drop-while drop-while})
(def ^:private concat-call-syms '#{clojure.core/concat concat})
(def ^:private into-call-syms '#{clojure.core/into into})
(def ^:private chunk-first-call-syms '#{clojure.core/chunk-first chunk-first})
(def ^:private plus-invoke-syms '#{clojure.core/+ +})
(def ^:private multiply-invoke-syms '#{clojure.core/* *})
(def ^:private minus-invoke-syms '#{clojure.core/- -})
(def ^:private inc-invoke-syms '#{clojure.core/inc inc})
(def ^:private not-call-syms '#{clojure.core/not not})
(def ^:private equality-call-syms '#{clojure.core/= =})
(def ^:private blank-call-syms '#{clojure.string/blank? blank?})

(s/defn seq-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? seq-call-syms (ac/resolved-call-sym fn-node)))

(s/defn merge? :- s/Bool
  [fn-node :- s/Any]
  (contains? merge-call-syms (ac/resolved-call-sym fn-node)))

(s/defn contains-call? :- s/Bool
  [fn-node :- s/Any]
  (contains? contains-call-syms (ac/resolved-call-sym fn-node)))

(s/defn get? :- s/Bool
  [fn-node :- s/Any]
  (contains? get-call-syms (ac/resolved-call-sym fn-node)))

(s/defn assoc? :- s/Bool
  [fn-node :- s/Any]
  (contains? assoc-call-syms (ac/resolved-call-sym fn-node)))

(s/defn dissoc? :- s/Bool
  [fn-node :- s/Any]
  (contains? dissoc-call-syms (ac/resolved-call-sym fn-node)))

(s/defn update? :- s/Bool
  [fn-node :- s/Any]
  (contains? update-call-syms (ac/resolved-call-sym fn-node)))

(s/defn first? :- s/Bool
  [fn-node :- s/Any]
  (contains? first-call-syms (ac/resolved-call-sym fn-node)))

(s/defn second? :- s/Bool
  [fn-node :- s/Any]
  (contains? second-call-syms (ac/resolved-call-sym fn-node)))

(s/defn last? :- s/Bool
  [fn-node :- s/Any]
  (contains? last-call-syms (ac/resolved-call-sym fn-node)))

(s/defn nth? :- s/Bool
  [fn-node :- s/Any]
  (contains? nth-call-syms (ac/resolved-call-sym fn-node)))

(s/defn rest? :- s/Bool
  [fn-node :- s/Any]
  (contains? rest-call-syms (ac/resolved-call-sym fn-node)))

(s/defn butlast? :- s/Bool
  [fn-node :- s/Any]
  (contains? butlast-call-syms (ac/resolved-call-sym fn-node)))

(s/defn drop-last? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-last-call-syms (ac/resolved-call-sym fn-node)))

(s/defn take? :- s/Bool
  [fn-node :- s/Any]
  (contains? take-call-syms (ac/resolved-call-sym fn-node)))

(s/defn drop? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-call-syms (ac/resolved-call-sym fn-node)))

(s/defn take-while? :- s/Bool
  [fn-node :- s/Any]
  (contains? take-while-call-syms (ac/resolved-call-sym fn-node)))

(s/defn drop-while? :- s/Bool
  [fn-node :- s/Any]
  (contains? drop-while-call-syms (ac/resolved-call-sym fn-node)))

(s/defn concat? :- s/Bool
  [fn-node :- s/Any]
  (contains? concat-call-syms (ac/resolved-call-sym fn-node)))

(s/defn into? :- s/Bool
  [fn-node :- s/Any]
  (contains? into-call-syms (ac/resolved-call-sym fn-node)))

(s/defn chunk-first? :- s/Bool
  [fn-node :- s/Any]
  (contains? chunk-first-call-syms (ac/resolved-call-sym fn-node)))

(s/defn plus? :- s/Bool
  [fn-node :- s/Any]
  (contains? plus-invoke-syms (ac/resolved-call-sym fn-node)))

(s/defn multiply? :- s/Bool
  [fn-node :- s/Any]
  (contains? multiply-invoke-syms (ac/resolved-call-sym fn-node)))

(s/defn minus? :- s/Bool
  [fn-node :- s/Any]
  (contains? minus-invoke-syms (ac/resolved-call-sym fn-node)))

(s/defn inc? :- s/Bool
  [fn-node :- s/Any]
  (contains? inc-invoke-syms (ac/resolved-call-sym fn-node)))

(s/defn not? :- s/Bool
  [fn-node :- s/Any]
  (contains? not-call-syms (ac/resolved-call-sym fn-node)))

(s/defn equality? :- s/Bool
  [fn-node :- s/Any]
  (contains? equality-call-syms (ac/resolved-call-sym fn-node)))

(s/defn blank? :- s/Bool
  [fn-node :- s/Any]
  (contains? blank-call-syms (ac/resolved-call-sym fn-node)))

(s/defn static-get? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/get 'get} (aapi/node-method node))))

(s/defn static-merge? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/merge 'merge} (aapi/node-method node))))

(s/defn static-contains? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/contains? 'contains? 'contains} (aapi/node-method node))))

(s/defn static-assoc? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/assoc 'assoc} (aapi/node-method node))))

(s/defn static-dissoc? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/dissoc 'dissoc} (aapi/node-method node))))

(s/defn static-update? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class node))
       (contains? #{'clojure.core/update 'update} (aapi/node-method node))))

(s/defn static-equality? :- s/Bool
  [node :- s/Any]
  (and (at/class-equals? (oracle/host-handle Util) (aapi/node-class node))
       (= 'equiv (aapi/node-method node))))
