; This ago.unit-test script is meant to run headless in phantomjs.

(ns ago.test-phantomjs
  (:require [ago.core :refer [make-ago-world]]))

(def success 0)

(defn test-app-data []
  (assert (not= nil (make-ago-world nil)))
  (assert (= (:app-data @(make-ago-world :my-app-data)) :my-app-data)))

(defn ^:export run []
  (.log js/console "ago test-phantomjs started.")
  (test-app-data)
  success)
