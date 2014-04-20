(ns rewindable.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [rewindable.ago-macros :refer [ago]])
  (:require [cljs.core.async :refer [chan <! !> alts! put!]]
            [rewindable.ago :refer [make-ago-world ago-chan ago-snapshot
                                    ago-judge-state-machines
                                    ago-revive-state-machine]]
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
         (println "yo" msg)
         msg)))

(let [hi-ch (listen-el (gdom/getElement "hi") "click")
      stw-ch (listen-el (gdom/getElement "stw") "click") ; save-the-world button
      rtw-ch (listen-el (gdom/getElement "rtw") "click") ; restore-the-world button
      last-snapshot (atom nil)
      agw (make-ago-world)
      ch1 (ago-chan agw 1)]
  (go-loop []
    (<! stw-ch)
    (reset! last-snapshot (ago-snapshot @agw))
    (recur))
  (go-loop []
    (<! rtw-ch)
    (let [ss (ago-snapshot @last-snapshot)
          bufs-ss (:bufs ss)
          [recycled-smas reborn-smas] (ago-judge-state-machines bufs-ss
                                                                (:smas ss)
                                                                (:smas @agw))
          [recycled-smasN reborn-smasN] (ago-judge-state-machines bufs-ss
                                                                  (:smas-new ss)
                                                                  (:smas-new @agw))]
      (swap! agw #(-> %
                      (assoc :bufs bufs-ss)
                      (assoc :smas recycled-smas)
                      (assoc :smas-new recycled-smasN)))
      (doseq [[sma-old ss-buf] reborn-smas]
        (ago-revive-state-machine agw sma-old ss-buf))
      (doseq [[sma-old ss-buf] reborn-smasN]
        (ago-revive-state-machine agw sma-old ss-buf)))
    (recur))
  (ago agw
       (loop [num-hi 0]
         (println "num-hi" num-hi)
         (println :agw @agw)
         (println :lss @last-snapshot)
         (let [x (<! hi-ch)]
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
               (recur (inc num-hi))))))))

