(ns skeptic.worker.analyzer-clj
  "Worker-side clj analyzer execution. Mirrors the env-construction and
   `analyze-form` body that live in `skeptic.analysis.annotate`, with no
   Skeptic / Schema / Malli dependency. The host-side wrapper in
   `skeptic.analysis.annotate/analyze-form` is rewired in Phase 8 to issue
   an `analyze-form-clj` RPC instead of calling the analyzer locally."
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.tools.analyzer.env :as ana.env]
            [clojure.tools.analyzer.jvm :as ana.jvm]))

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
