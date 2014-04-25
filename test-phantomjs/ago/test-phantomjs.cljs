; This ago.unit-test script is meant to run headless, not in a browser,
; but instead in something like phantomjs.

(ns ago.test-phantomjs
  (:require [ago.core]))

(def success 0)

(defn ^:export run []
  (.log js/console "ago unit test started.")
  (.log js/console (+ 1 2))
  success)
