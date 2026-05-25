(ns skeptic.cljs-fixtures.p16-reitit-like.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            skeptic.cljs-fixtures.p16-reitit-like.dep-test
            skeptic.cljs-fixtures.p16-reitit-like.core
            skeptic.cljs-fixtures.p16-reitit-like.spec-test))

(enable-console-print!)

(doo-tests 'skeptic.cljs-fixtures.p16-reitit-like.dep-test
           'skeptic.cljs-fixtures.p16-reitit-like.core
           'skeptic.cljs-fixtures.p16-reitit-like.spec-test)
