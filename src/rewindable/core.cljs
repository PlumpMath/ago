(ns rewindable.core
  (:require-macros [rewindable.ago-macros :refer [ago]])
  (:require [cljs.core.async :refer [chan <! !> alts! put!]]
            [rewindable.ago :refer [make-ago-world ago-chan ago-snapshot]]
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
  (ago agw
       (let [msg (<! in-ch)]
         (println :agw @agw)
         (println "yo" msg)
         msg)))

(let [hi-ch (listen-el (gdom/getElement "hi") "click")
      stw-ch (listen-el (gdom/getElement "stw") "click") ; save-the-world button
      agw (make-ago-world)
      ch1 (ago-chan agw 1)]
  (ago agw
       (loop [num-hi 0
              snapshot nil]
         (let [[x ch] (alts! [hi-ch stw-ch])]
           (cond
            (= ch hi-ch)
            (do (println "num-hi" num-hi)
                (>! ch1 [num-hi x])
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
                    (recur (inc num-hi) snapshot))))
            (= ch stw-ch)
            (do (recur num-hi
                       (ago-snapshot agw))))))))
