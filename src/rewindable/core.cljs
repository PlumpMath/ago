(ns rewindable.core
  (:require-macros [rewindable.ago-macros :refer [my-go]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [rewindable.ago :refer [make-ago-world ago-world-chan]]
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

(defn child [agw in-ch]
  (my-go agw
         (let [msg (<! in-ch)]
           (println "yo" msg)
           msg)))

(let [hi-ch (listen-el (gdom/getElement "hi") "click")
      agw (make-ago-world)
      ch1 (ago-world-chan agw 1)]
  (my-go agw
         (loop [num-hi 0]
           (let [x (<! hi-ch)]
             (println "num-hi" num-hi)
             (>! ch1 [num-hi x])
             (println :agw @agw)
             (let [child-ch (child agw ch1)
                   [num-hi2 x2] (<! child-ch)]
               (when (not= nil (<! child-ch))
                 (println "ERROR expected closed child-ch"))
               (when (not= nil (<! child-ch))
                 (println "ERROR expected closed child-ch"))
               (if (or (not= x x2)
                       (not= num-hi num-hi2))
                 (println "ERROR"
                          "x" x "num-hi" num-hi
                          "x2" x2 "num-hi2" num-hi2)
                 (recur (inc num-hi))))))))
