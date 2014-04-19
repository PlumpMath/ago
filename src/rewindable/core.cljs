(ns rewindable.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [rewindable.ago-macros :refer [ago]])
  (:require [cljs.core.async :refer [chan <! !> alts! put!]]
            [rewindable.ago :refer [make-ago-world ago-chan ago-snapshot]]
            [goog.dom :as gdom]
            [goog.events :as gevents]))

(enable-console-print!)

(defn acopy [asrc adst]
  (loop [idx 0]
    (when (< idx (alength asrc))
      (aset adst idx (aget asrc idx))
      (recur (inc idx)))))

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
          agos-ss (:agos ss)
          agos-cur (:agos @agw)
          nxt-agos
          (loop [ss-buf-ids (sort (keys agos-ss))
                 cur-buf-ids (sort (keys agos-cur))
                 acc-nxt-agos {}]
            (let [ss-buf-id (first ss-buf-ids)
                  cur-buf-id (first cur-buf-ids)]
              (cond
               (= nil ss-buf-id cur-buf-id) acc-nxt-agos

               (and ss-buf-id ; In ss but not in cur.
                    (or (nil? cur-buf-id) (< ss-buf-id cur-buf-id)))
               (recur (rest ss-buf-ids)
                      cur-buf-ids
                      (assoc acc-nxt-agos ss-buf-id (get agos-ss ss-buf-id)))

               (and cur-buf-id ; In cur but not in ss.
                    (or (nil? ss-buf-id) (< cur-buf-id ss-buf-id)))
               (recur ss-buf-ids
                      (rest cur-buf-ids)
                      acc-nxt-agos)

               (= ss-buf-id cur-buf-id) ; In both cur and ss.
               (let [sma-ss (get agos-ss ss-buf-id)
                     sma-cur (get agos-cur cur-buf-id)]
                 (acopy sma-ss sma-cur)
                 (recur (rest ss-buf-ids)
                        (rest cur-buf-ids)
                        (assoc acc-nxt-agos cur-buf-id sma-cur)))

               :else (println "UNEXPECTED case in snapshot revive"))))]
      (swap! agw #(-> %
                      (assoc :bufs (:bufs ss))
                      (assoc :agos nxt-agos))))
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

