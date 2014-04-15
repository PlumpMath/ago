(ns rewindable.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [chan <! >! close! timeout merge
                                     map< filter< sliding-buffer]]
            [goog.dom :as gdom]))

(enable-console-print!)

(defn hello []
  (println "Hello, World!"))

(hello)
