(ns ago.test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [ago.macros :refer [ago]])
  (:require [cljs.core.async.impl.dispatch]
            [cljs.core.async :refer [chan close! <! >! alts! put! take!]]
            [ago.core :refer [make-ago-world ago-chan ago-snapshot ago-restore
                              seqv+ compare-seqvs seqv-alive? ago-timeout]]
            [goog.dom :as gdom]
            [goog.events :as gevents]))

(enable-console-print!)

(defn listen-el [el type]
  (let [out (chan)]
    (gevents/listen el type #(put! out %))
    out))

; ----------------------------------------------------------

; Hooked up to buttons to allow interactive tests / demos.

(defn child [agw in-ch]
  (ago agw
       (let [msg (<! in-ch)]
         msg)))

(defn child2 [agw in-ch]
  (ago agw
       (loop [yas 0]
         (println "ya0" yas (<! in-ch))
         (println "ya1" yas (<! in-ch))
         (recur (inc yas)))))

(let [hi-ch (listen-el (gdom/getElement "hi") "click")
      bye-ch (listen-el (gdom/getElement "bye") "click")
      fie-ch (listen-el (gdom/getElement "fie") "click")
      stw-ch (listen-el (gdom/getElement "stw") "click") ; save-the-world button
      rtw-ch (listen-el (gdom/getElement "rtw") "click") ; restore-the-world button
      last-snapshot (atom nil)
      agw (make-ago-world nil)
      ch1 (ago-chan agw 1)
      ch2 (ago-chan agw 2)]
  (go-loop []
    (<! stw-ch)
    (reset! last-snapshot (ago-snapshot agw))
    (recur))
  (go-loop []
    (<! rtw-ch)
    (ago-restore agw @last-snapshot)
    (recur))
  (ago agw
       (loop [num-hi 0 num-bye 0]
         (println "num-hi" num-hi "num-bye" num-bye)
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
         (>! ch2 [:fie num-fie])
         (>! ch2 [:foe num-fie])
         (let [x (<! fie-ch)]
           (>! ch1 [num-fie x])
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
               (recur (inc num-fie)))))))
  (child2 agw ch2))

; ----------------------------------------------------------

(def success 0)

(defn test-constructors []
  (println "test-constructors")
  (assert (not= nil (make-ago-world nil)))
  (assert (= (:app-data @(make-ago-world :my-app-data)) :my-app-data))
  (assert (= (:logical-ms @(make-ago-world nil)) 0))
  (let [agw (make-ago-world nil)]
    (assert (= (count (:bufs @agw)) 0))
    (let [ch1 (ago-chan agw)]
      (assert (not= nil ch1))
      (assert (= (count (:bufs @agw)) 0)))))

(defn test-seqvs []
  (println "test-seqvs")
  (let [agw (make-ago-world nil)
        sq0 (:seqv @agw)]
    (take! (ago agw 9)
           (fn [should-be-9]
             (assert (= 9 should-be-9))
             (let [sq1 (:seqv @agw)
                   agw2 (atom (seqv+ @agw))
                   sq2 (:seqv @agw2)]
               (assert (= (compare-seqvs sq0 sq0) 0))
               (assert (= (compare-seqvs sq1 sq1) 0))
               (assert (> (compare-seqvs sq1 sq0) 0))
               (assert (< (compare-seqvs sq0 sq1) 0))
               (assert (= (compare-seqvs sq2 sq2) 0))
               (assert (> (compare-seqvs sq2 sq0) 0))
               (assert (> (compare-seqvs sq2 sq1) 0))
               (assert (< (compare-seqvs sq0 sq2) 0))
               (assert (< (compare-seqvs sq1 sq2) 0))
               (assert (seqv-alive? agw2 sq0))
               (assert (seqv-alive? agw2 sq1))
               (assert (seqv-alive? agw2 sq2))
               (assert (not (seqv-alive? agw sq2)))
               (assert (seqv-alive? agw nil))
               (assert (seqv-alive? agw2 nil)))))))

(defn test-put-take [chan-size]
  (println "test-put-take" chan-size)
  (let [agw (make-ago-world nil)
        ch0 (ago-chan agw chan-size)
        ch1 (ago-chan agw chan-size)
        echoer (ago agw
                    (loop [acc []]
                      (if-let [msg (<! ch0)]
                        (do (>! ch1 {:msg msg})
                            (recur (conj acc msg)))
                        (do (close! ch1)
                            acc))))
        sender (ago agw
                    (>! ch0 :hi)
                    (>! ch0 :world)
                    (close! ch0)
                    :sender-done)
        all-done (atom false)]
    (go (assert (= {:msg :hi} (<! ch1)))
        (assert (= {:msg :world} (<! ch1)))
        (assert (= nil (<! ch1)))
        (assert (= [:hi :world] (<! echoer)))
        (assert (= nil (<! echoer)))
        (assert (= :sender-done (<! sender)))
        (assert (= nil (<! sender)))
        (reset! all-done true))
    (loop []
      (when (not @all-done)
        (cljs.core.async.impl.dispatch/process-messages)
        (recur)))
    @all-done))

(defn test-alts [chan-size]
  (println "test-alts" chan-size)
  (let [agw (make-ago-world nil)
        chx (ago-chan agw chan-size)
        chy (ago-chan agw chan-size)
        chz (ago-chan agw chan-size)
        collector (ago agw
                       (loop [chs #{chx chy chz}
                              acc #{}]
                         (if (seq chs)
                           (let [[v ch] (alts! (vec chs))]
                             (if v
                               (recur chs (conj acc v))
                               (recur (disj chs ch) acc)))
                           acc)))
        sender0 (ago agw
                     (>! chx :hi)
                     (>! chx :world)
                     (close! chx)
                     :sender0-done)
        sender1 (ago agw
                     (loop [msgs {chy :fee chz :fie}]
                       (when (seq msgs)
                         (let [[v ch] (alts! (vec msgs))]
                           (recur (dissoc msgs ch)))))
                     (close! chy)
                     (close! chz)
                     :sender1-done)
        all-done (atom false)]
    (go (let [msgs (<! collector)]
          (assert (= msgs #{:hi :world :fee :fie}))
          (assert (= (<! collector) nil))
          (assert (= (<! sender0) :sender0-done))
          (assert (= (<! sender0) nil))
          (assert (= (<! sender1) :sender1-done))
          (assert (= (<! sender1) nil))
          (reset! all-done true)))
    (loop []
      (when (not @all-done)
        (cljs.core.async.impl.dispatch/process-messages)
        (recur)))
    @all-done))

(defn test-snapshot-restore [chan-size]
  (println "test-snapshot-restore" chan-size)
  (let [agw (make-ago-world nil)
        cc0 (ago-chan agw chan-size) ; A control channel.
        cc1 (ago-chan agw chan-size) ; A control channel.
        ch0 (ago-chan agw chan-size)
        ch1 (ago-chan agw chan-size)
        echoer0 (ago agw
                     (loop [n 0]
                       (if (<! cc0)
                         (do (>! ch0 n)
                             (recur (inc n)))
                         (close! ch0))))
        echoer1 (ago agw
                     (loop [n 0]
                       (if (<! cc1)
                         (do (>! ch1 n)
                             (recur (inc n)))
                         (close! ch1))))
        all-done (atom false)]
    (go (>! cc0 true)
        (assert (= (<! ch0) 0))
        (>! cc0 true)
        (assert (= (<! ch0) 1))
        (>! cc1 true)
        (assert (= (<! ch1) 0))
        (let [ss (ago-snapshot agw)]
          (>! cc0 true)
          (assert (= (<! ch0) 2))
          (>! cc0 true)
          (assert (= (<! ch0) 3))
          (>! cc0 true)
          (assert (= (<! ch0) 4))
          (>! cc1 true)
          (assert (= (<! ch1) 1))

          (ago-restore agw ss) ; Restore to see msgs roll back.
          (>! cc0 true)
          (assert (= (<! ch0) 2))
          (>! cc0 true)
          (assert (= (<! ch0) 3))
          (>! cc0 true)
          (assert (= (<! ch0) 4))
          (>! cc1 true)
          (assert (= (<! ch1) 1))

          (ago-restore agw ss) ; Restore to see msgs roll back, again.
          (>! cc0 true)
          (assert (= (<! ch0) 2))
          (>! cc0 true)
          (assert (= (<! ch0) 3))
          (>! cc0 true)
          (assert (= (<! ch0) 4))
          (>! cc1 true)
          (assert (= (<! ch1) 1))

          (ago-restore agw ss) ; Restore to see msgs roll back, again.
          (>! cc0 true)
          (assert (= (<! ch0) 2))
          (>! cc0 true)
          (assert (= (<! ch0) 3))
          (>! cc0 true)
          (assert (= (<! ch0) 4))
          (>! cc1 true)
          (assert (= (<! ch1) 1))

          (close! cc0) ; Now close the channels.
          (close! cc1)
          (assert (= (<! ch0) nil))
          (assert (= (<! ch1) nil))

          (ago-restore agw ss) ; Restore after channel closures.
          (>! cc0 true)
          (assert (= (<! ch0) 2))
          (>! cc1 true)
          (assert (= (<! ch1) 1))
          (close! cc0)
          (close! cc1)
          (assert (= (<! ch0) nil))
          (assert (= (<! ch1) nil))

          (reset! all-done true)))
    (loop []
      (when (not @all-done)
        (cljs.core.async.impl.dispatch/process-messages)
        (recur)))
    @all-done))

(defn test-timeout [chan-size]
  (println "test-timeout" chan-size)
  (let [agw (make-ago-world nil)
        ch0 (ago-chan agw chan-size)
        timer0 (ago agw
                    (<! (ago-timeout agw 500))
                    (println "timer aa fired")
                    (>! ch0 :aa)
                    (<! (ago-timeout agw 400))
                    (println "timer bb fired")
                    (>! ch0 :bb)
                    (close! ch0)
                    :timer0-done)
        all-done (atom false)]
    (go (assert (= (<! ch0) :aa))
        (let [ss1 (ago-snapshot agw)]
          (assert (= (<! ch0) :bb))
          (assert (= (<! ch0) nil))
          (assert (= (<! timer0) :timer0-done))
          (assert (= (<! timer0) nil))

          (println "restoring ss1...")
          (ago-restore agw ss1)
          (println "restoring ss1 done")
          (assert (= (<! ch0) :bb))
          (assert (= (<! ch0) nil))
          (assert (= (<! timer0) :timer0-done))
          (assert (= (<! timer0) nil))

          (reset! all-done true)))
    (loop []
      (when (not @all-done)
        (if (> (.-length cljs.core.async.impl.dispatch/tasks) 0)
          (do (cljs.core.async.impl.dispatch/process-messages)
              (recur))
          (if @ago.core/curr-js-timeout-id
            (if-let [[soonest-ms chs] (first (:timeouts @agw))]
              (do (swap! agw #(assoc % :logical-ms soonest-ms))
                  (ago.core/timeout-handler agw)
                  (recur))
              (println :UNEXPECTED "no tasks and no timeouts" @all-done))
            (println :UNEXPECTED "no tasks and no timeout" @all-done)))))
    (assert (= @all-done true))))

(defn ^:export run []
  (println "ago test run started.")
  (test-constructors)
  (test-seqvs)
  (test-put-take 0)
  (test-put-take 1)
  (test-put-take 10)
  (test-alts 0)
  (test-alts 1)
  (test-alts 10)
  (test-snapshot-restore 0)
  (test-snapshot-restore 1)
  (test-snapshot-restore 10)
  (test-timeout 0)
  (test-timeout 1)
  (test-timeout 10)
  (println "ago test run PASS.")
  success)
