(ns rewindable.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [rewindable.ago-macros :refer [ago]])
  (:require [cljs.core.async :refer [chan <! !> alts! put!]]
            [rewindable.ago :refer [acopy make-ago-world ago-chan ago-snapshot
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

(defn revive-smas [bufs-ss smas-ss smas-cur]
  (loop [ss-buf-ids (sort (keys smas-ss))
         cur-buf-ids (sort (keys smas-cur))
         acc-recycled-smas {} ; State machines shared by ss and cur.
         acc-reborn-smas []]  ; State machines that need rebirth (were in ss only).
    (let [ss-buf-id (first ss-buf-ids)
          cur-buf-id (first cur-buf-ids)]
      (cond
       (= nil ss-buf-id cur-buf-id)
       [acc-recycled-smas acc-reborn-smas]

       (and ss-buf-id ; In ss but not in cur.
            (or (nil? cur-buf-id) (< ss-buf-id cur-buf-id)))
       (recur (rest ss-buf-ids) cur-buf-ids
              acc-recycled-smas
              (let [ss-buf (get bufs-ss ss-buf-id)
                    sma-old (get smas-ss ss-buf-id)]
                ; Later, can invoke (ago-revive-state-machine agw sma-old ss-buf).
                (conj acc-reborn-smas [sma-old ss-buf])))

       (and cur-buf-id ; In cur but not in ss, so drop cur's sma.
            (or (nil? ss-buf-id) (< cur-buf-id ss-buf-id)))
       (recur ss-buf-ids (rest cur-buf-ids)
              acc-recycled-smas
              acc-reborn-smas)

       (= ss-buf-id cur-buf-id) ; In both cur and ss.
       (let [sma-ss (get smas-ss ss-buf-id)
             sma-cur (get smas-cur cur-buf-id)]
         (acopy sma-ss sma-cur) ; Recycle the cur's sma.
         (recur (rest ss-buf-ids)
                (rest cur-buf-ids)
                (assoc acc-recycled-smas cur-buf-id sma-cur)
                acc-reborn-smas))

       :else (println "UNEXPECTED case in snapshot revive")))))

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
          [recycled-smas reborn-smas] (revive-smas (:bufs ss)
                                                   (:smas ss) (:smas @agw))
          [recycled-smas2 reborn-smas2] (revive-smas (:bufs ss)
                                                     (:smas-new ss) (:smas-new @agw))]
      (swap! agw #(-> %
                      (assoc :bufs (:bufs ss))
                      (assoc :smas recycled-smas)
                      (assoc :smas-new recycled-smas2)))
      (doseq [[sma-old ss-buf] reborn-smas]
        (ago-revive-state-machine agw sma-old ss-buf))
      (doseq [[sma-old ss-buf] reborn-smas2]
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

