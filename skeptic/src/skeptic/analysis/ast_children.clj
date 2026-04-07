(ns skeptic.analysis.ast-children
  "Shared tools.analyzer child listing and preorder traversal.
  Delegates to clojure.tools.analyzer.ast so all callers stay aligned."
  (:require [clojure.tools.analyzer.ast :as ana.ast]))

(def child-nodes
  "Flattened child nodes in :children key order."
  ana.ast/children)

(defn ast-nodes
  "Depth-first preorder of all AST nodes."
  [ast]
  (ana.ast/nodes ast))
