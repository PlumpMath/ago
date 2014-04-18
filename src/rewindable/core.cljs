(ns rewindable.core
  (:require-macros [rewindable.ago-macros :refer [my-go]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [rewindable.ago]
            [goog.dom :as gdom]
            [goog.events :as gevents]))

(enable-console-print!)

(defn listen-el [el type]
  (let [out (chan)]
    (gevents/listen el type #(put! out %))
    out))

(defn hello []
  (println "Hello, World!"))

(hello)

(let [hi-ch (listen-el (gdom/getElement "hi") "click")]
  (my-go (loop [num-hi 0]
           (<! hi-ch)
           (println "num-hi" num-hi)
           (recur (inc num-hi)))))
