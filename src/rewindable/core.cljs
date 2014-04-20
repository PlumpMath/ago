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
      bye-ch (listen-el (gdom/getElement "bye") "click")
      fie-ch (listen-el (gdom/getElement "fie") "click")
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
       (loop [num-hi 0 num-bye 0]
         (println "num-hi" num-hi "num-bye" num-bye)
         (println :agw @agw)
         (println :lss @last-snapshot)
         (let [[x ch] (alts! [hi-ch bye-ch])]
           (cond
            (= ch hi-ch)
            (do (>! ch1 [num-hi x])
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
                    (recur (inc num-hi) num-bye))))
            (= ch bye-ch)
            (let [child-ch (child agw ch1)]
              (>! ch1 [num-bye x])
              (let [[num-bye2 x2] (<! child-ch)]
                (when (not= nil (<! child-ch))
                  (println "ERROR expected closed child-ch bye"))
                (when (not= nil (<! child-ch))
                  (println "ERROR expected closed child-ch bye"))
                (if (or (not= x x2)
                        (not= num-bye num-bye2))
                    (println "ERROR"
                             "x" x "num-bye" num-bye
                             "x2" x2 "num-bye2" num-bye2)
                    (recur num-hi (inc num-bye)))))))))
  (ago agw
       (loop [num-fie 0]
         (println "num-fie" num-fie)
         (println :agw @agw)
         (println :lss @last-snapshot)
         (let [[x ch] (alts! [fie-ch])]
           (cond
            (= ch fie-ch)
            (do (>! ch1 [num-fie x])
                (let [child-ch (child agw ch1)
                      [num-fie2 x2] (<! child-ch)]
                  (when (not= nil (<! child-ch))
                    (println "ERROR expected closed child-ch"))
                  (when (not= nil (<! child-ch))
                    (println "ERROR expected closed child-ch"))
                  (if (or (not= x x2)
                          (not= num-fie num-fie2))
                    (println "ERROR"
                             "x" x "num-fie" num-fie
                             "x2" x2 "num-fie2" num-fie2)
                    (recur (inc num-fie))))))))))

