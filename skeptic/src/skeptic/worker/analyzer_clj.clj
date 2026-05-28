(ns skeptic.worker.analyzer-clj
  "Worker-side clj analyzer execution. Mirrors the env-construction and
   `analyze-form` body that live in `skeptic.analysis.annotate`, with no
   Skeptic / Schema / Malli dependency. The worker reads the project's own
   source files with the real Clojure reader and analyzes them in bulk; no
   form ever crosses host->worker (the host sends only a source-file path)."
  (:require [clojure.java.io :as io]
            [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.tools.analyzer.env :as ana.env]
            [clojure.tools.analyzer.jvm :as ana.jvm])
  (:import [java.io PushbackReader]))

(def ^:private skeptic-passes-opts
  (assoc ana.jvm/default-passes-opts
         :validate/wrong-tag-handler (fn [t _ast] {t Object})))

(defn- synthetic-binding-node
  [idx sym]
  {:op :binding :form sym :name sym :local :let
   :atom (atom {}) :env {} :children [] :skeptic.synthetic/index idx})

(defn- target-ns
  [ns-sym]
  (or (some-> ns-sym find-ns)
      (some-> ns-sym create-ns)
      *ns*))

(defn- analyze-env
  [target-ns locals source-file]
  (cond-> (assoc (ta/empty-env)
                 :ns (ns-name target-ns)
                 :locals (into {}
                               (map-indexed (fn [idx sym]
                                              [sym (synthetic-binding-node idx sym)]))
                               (keys locals)))
    source-file
    (assoc :file source-file)))

(defn- normalize-raw-ast
  [ast]
  (ana.ast/prewalk ast (fn [node]
                         (cond-> node
                           (= :const (:op node)) (dissoc :type)))))

(defn- loaded-namespace-analyzer-env
  []
  (doto (ana.jvm/global-env)
    (swap! assoc :update-ns-map! (fn [] nil))))

(defn- with-loaded-namespace-analyzer-env
  [f]
  (if ana.env/*env*
    (f)
    (ana.env/with-env (loaded-namespace-analyzer-env)
      (f))))

(defn analyze
  "Analyze `form` in `ns` against the project classpath. `opts` is a map with
   optional `:locals` (map of sym→anything; only keys are used to seed the
   analyzer's `:locals`) and `:source-file` (string). Returns a raw
   tools.analyzer.jvm AST with `:const` `:type` slots stripped."
  [form opts]
  (let [{:keys [locals ns source-file]} opts
        tn (target-ns ns)
        env (binding [*ns* tn]
              (analyze-env tn locals source-file))]
    (with-loaded-namespace-analyzer-env
      (fn []
        (binding [*ns* tn]
          (normalize-raw-ast
           (ana.jvm/analyze form env {:passes-opts skeptic-passes-opts})))))))

(defn- read-top-forms
  "Read every top-level form of `source-file` with the real Clojure reader
   (reader conditionals on the :clj branch). Regex/fn/quote literals read
   natively here, unlike clojure.edn."
  [source-file]
  (with-open [reader (PushbackReader. (io/reader source-file))]
    (->> (repeatedly #(read {:read-cond :allow :features #{:clj} :eof ::eof} reader))
         (take-while #(not= ::eof %))
         doall)))

(defn analyze-source-file
  "Analyze every top-level form of `source-file` in namespace `ns-sym`. Loads
   the namespace first so its refers/aliases/imports resolve (matching the host
   pipeline's require-before-analyze contract). The worker reads its own source;
   no form crosses the wire. Returns `{:asts [...]}` of raw tools.analyzer.jvm
   ASTs (`:const` `:type` stripped)."
  [ns-sym source-file]
  (require ns-sym)
  (let [opts {:locals {} :ns ns-sym :source-file (str source-file)}]
    {:asts (mapv #(analyze % opts) (read-top-forms source-file))}))
