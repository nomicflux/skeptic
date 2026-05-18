(ns p6.core
  (:require [cljs.test :refer-macros [run-tests]]
            [p6.tests]))

(run-tests 'p6.tests)
