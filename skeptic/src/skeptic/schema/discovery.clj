(ns skeptic.schema.discovery
  "Plumatic source-form discovery, hermetic from the project JVM. Classifies
  each top-level form's head symbol by alias-resolving it against the
  namespace's `(ns …)` / top-level `(require …)` alias map, then tags matches
  with a producer role.

  The forms arrive as inert `:source-form` data shipped by the worker (the
  project is never loaded on the host), so heads like `s/defn` /
  `schema.core/defn` / `schemy/defn` are resolved to the canonical
  `schema.core/defn` symbol via the alias map rather than by binding `*ns*` and
  consulting live project Vars.")

(def ^:private role-by-resolved-sym
  {'schema.core/defn        :s/defn
   'schema.core/def         :s/def
   'schema.core/defschema   :s/defschema
   'schema.core/defprotocol :s/defprotocol
   'schema.core/defrecord   :s/defrecord})

;; --- alias map from (ns …) / (require …) forms ---

(defn- libspec-alias
  "An `[alias-sym target-ns-sym]` pair from a require libspec
  `[ns.target :as alias]`, or nil."
  [libspec]
  (when (and (vector? libspec) (symbol? (first libspec)))
    (let [as-idx (.indexOf ^java.util.List libspec :as)]
      (when (and (nat-int? as-idx) (>= as-idx 0))
        (when-let [alias-sym (get libspec (inc as-idx))]
          (when (symbol? alias-sym)
            [alias-sym (first libspec)]))))))

(defn- require-clause-aliases
  "Alias pairs from the libspecs following a `:require`/`require` head."
  [libspecs]
  (keep (fn [libspec]
          (libspec-alias (if (and (seq? libspec) (= 'quote (first libspec)))
                           (second libspec)
                           libspec)))
        libspecs))

(defn- form-aliases
  "Alias pairs contributed by one top-level form: the `(ns … (:require …))`
  form's `:require` clauses, or a top-level `(require '[… :as …] …)` form."
  [form]
  (cond
    (and (seq? form) (= 'ns (first form)))
    (mapcat (fn [clause]
              (when (and (seq? clause) (= :require (first clause)))
                (require-clause-aliases (rest clause))))
            (filter seq? form))

    (and (seq? form) (= 'require (first form)))
    (require-clause-aliases (rest form))

    :else nil))

(defn source-form-aliases
  "Build `{alias-sym → target-ns-sym}` for a namespace from its top-level
  source-forms (the `(ns …)` form's `:require` clauses plus any top-level
  `(require …)` forms). Hermetic: no live namespace is consulted."
  [forms]
  (into {} (mapcat form-aliases) forms))

;; --- head classification ---

(defn- resolve-head-sym
  "Canonicalize a head symbol against the alias map: `s/defn` with `s →
  schema.core` becomes `schema.core/defn`. Unqualified or unknown-alias heads
  pass through unchanged."
  [aliases head]
  (if-let [ns-part (some-> head namespace symbol)]
    (if-let [target (get aliases ns-part)]
      (symbol (name target) (name head))
      head)
    head))

(defn- form-head-role
  "Role for `(first form)` resolved against `aliases`, or nil."
  [aliases form]
  (when (seq? form)
    (let [head (first form)]
      (when (symbol? head)
        (role-by-resolved-sym (resolve-head-sym aliases head))))))

(defn- declaration
  [ns-sym role declared-sym form]
  (let [qsym (symbol (name ns-sym) (name declared-sym))]
    [qsym {:role role :form form :declared-sym declared-sym :ns ns-sym}]))

(defn- protocol-method-decls
  [ns-sym form]
  (->> (drop 2 form)
       (filter seq?)
       (keep (fn [spec]
               (let [method-sym (first spec)]
                 (when (symbol? method-sym)
                   (declaration ns-sym :s/defprotocol-method method-sym form)))))))

(defn- record-factory-decls
  [ns-sym record-sym form]
  (let [base (name record-sym)]
    (for [prefix ["->" "map->" "strict-map->"]]
      (declaration ns-sym :s/defrecord-factory (symbol (str prefix base)) form))))

(defn- form-decls
  [aliases ns-sym form]
  (when-let [role (form-head-role aliases form)]
    (let [declared-sym (second form)]
      (cond
        (not (symbol? declared-sym))
        nil

        (= role :s/defprotocol)
        (cons (declaration ns-sym :s/defprotocol declared-sym form)
              (protocol-method-decls ns-sym form))

        (= role :s/defrecord)
        (cons (declaration ns-sym :s/defrecord-class declared-sym form)
              (record-factory-decls ns-sym declared-sym form))

        :else
        [(declaration ns-sym role declared-sym form)]))))

(defn discover
  "Classify the namespace's top-level `forms` (shipped `:source-form` data) by
  Plumatic producer role. Returns
  {:declarations {qualified-sym {:role :form :declared-sym :ns}}
   :source-forms {qualified-sym <raw-form>}
   :errors [...]}.

  `forms` is the seq of raw top-level source-forms for the namespace; the alias
  map for head resolution is derived from those same forms via `source-form-aliases`."
  [ns-sym forms]
  (let [aliases (source-form-aliases forms)
        entries (mapcat #(form-decls aliases ns-sym %) forms)
        decls (into {} entries)
        srcs  (into {} (map (fn [[q m]] [q (:form m)]) entries))]
    {:declarations decls
     :source-forms srcs
     :aliases aliases
     :errors []}))
