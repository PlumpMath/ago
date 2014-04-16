;; Custom SSA terminators to CLJS core async.

(ns rewindable.agos
  (:require [cljs.core.async.impl.ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(defn box [val]
  (reify cljs.core/IDeref
    (-deref [_] val)))

(deftype PutBox [handler val])

(defn put-active? [box]
  (protocols/active? (.-handler box)))

(def ^:const MAX_DIRTY 64)

(deftype MyManyToManyChannel [takes ^:mutable dirty-takes
                              puts ^:mutable dirty-puts
                              ^not-native buf ^:mutable closed]
  protocols/WritePort
  (put! [this val ^not-native handler]
    (println :MMMChannel :put this val handler)
    (assert (not (nil? val)) "Can't put nil in on a channel")
    ;; bug in CLJS compiler boolean inference - David
    (let [^boolean closed closed]
      (if (or closed
              (not ^boolean (protocols/active? handler)))
        (box (not closed))
        (loop []
          (let [^not-native taker (.pop takes)]
            (if-not (nil? taker)
              (if ^boolean (protocols/active? taker)
                  (let [take-cb (protocols/commit taker)
                        _ (protocols/commit handler)]
                    (dispatch/run (fn [] (take-cb val)))
                    (box true))
                  (recur))

              (if (not (or (nil? buf)
                           ^boolean (protocols/full? buf)))
                (let [_ (protocols/commit handler)]
                  (do (protocols/add! buf val)
                      (box true)))
                (do
                  (if (> dirty-puts MAX_DIRTY)
                    (do (set! dirty-puts 0)
                        (.cleanup puts put-active?))
                    (set! dirty-puts (inc dirty-puts)))
                  (assert (< (.-length puts) protocols/MAX-QUEUE-SIZE)
                          (str "No more than " protocols/MAX-QUEUE-SIZE
                               " pending puts are allowed on a single channel."
                               " Consider using a windowed buffer."))
                  (.unbounded-unshift puts (PutBox. handler val))
                  nil))))))))

  protocols/ReadPort
  (take! [this ^not-native handler]
    (println :MMMChannel :take this handler)
    (if (not ^boolean (protocols/active? handler))
      nil
      (if (and (not (nil? buf)) (pos? (count buf)))
        (let [_ (protocols/commit handler)
              retval (box (protocols/remove! buf))]
          (loop []
            (let [putter (.pop puts)]
              (if-not (nil? putter)
                (let [^not-native put-handler (.-handler putter)
                      val (.-val putter)]
                  (if ^boolean (protocols/active? put-handler)
                      (let [put-cb (protocols/commit put-handler)
                            _ (protocols/commit handler)]
                        (dispatch/run #(put-cb true))
                        (protocols/add! buf val))
                      (recur))))))
          retval)
        (loop []
          (let [putter (.pop puts)]
            (if-not (nil? putter)
              (let [^not-native put-handler (.-handler putter)
                    val (.-val putter)]
                (if ^boolean (protocols/active? put-handler)
                    (let [put-cb (protocols/commit put-handler)
                          _ (protocols/commit handler)]
                      (dispatch/run #(put-cb true))
                      (box val))
                    (recur)))
              (if ^boolean closed
                  (let [_ (protocols/commit handler)]
                    (box nil))
                  (do
                    (if (> dirty-takes MAX_DIRTY)
                      (do (set! dirty-takes 0)
                          (.cleanup takes protocols/active?))
                      (set! dirty-takes (inc dirty-takes)))

                    (assert (< (.-length takes) protocols/MAX-QUEUE-SIZE)
                            (str "No more than " protocols/MAX-QUEUE-SIZE
                                 " pending takes are allowed on a single channel."))
                    (.unbounded-unshift takes handler)
                    nil))))))))

  protocols/Channel
  (closed? [_] closed)
  (close! [this]
    (println :MMMChannel :close this)
    (if ^boolean closed
        nil
        (do (set! closed true)
            (loop []
              (let [^not-native taker (.pop takes)]
                (when-not (nil? taker)
                  (when ^boolean (protocols/active? taker)
                        (let [take-cb (protocols/commit taker)]
                          (dispatch/run (fn [] (take-cb nil)))))
                  (recur))))
            nil))))

(defn chan-buf [buf]
  (println :chan-buf buf)
  (MyManyToManyChannel. (buffers/ring-buffer 32) 0
                        (buffers/ring-buffer 32) 0
                        buf false))

(defn chan
  "Creates a channel with an optional buffer. If buf-or-n is a number,
  will create and use a fixed buffer of that size."
  ([] (chan nil))
  ([buf-or-n]
     (let [buf-or-n (if (= buf-or-n 0)
                      nil
                      buf-or-n)]
       (chan-buf (if (number? buf-or-n)
                   (buffers/fixed-buffer buf-or-n)
                   buf-or-n)))))

; --------------------------------------------------------

(defn sstate [state]
  (println (rest state)))

(defn agos-take [state blk ^not-native c]
  (println :agos-take (sstate state) blk c)
  (cljs.core.async.impl.ioc-helpers/take! state blk c))

(defn agos-put [state blk ^not-native c val]
  (println :agos-put (sstate state) blk c val)
  (cljs.core.async.impl.ioc-helpers/put! state blk c val))

(defn agos-alts [state cont-block ports & rest]
  (println :agos-alts (sstate state) cont-block ports rest)
  (apply cljs.core.async.impl.ioc-helpers/ioc-alts! state cont-block ports rest))

(defn agos-return-chan [state value]
  (println :agos-return-chan (sstate state) value)
  (cljs.core.async.impl.ioc-helpers/return-chan state value))

(defn agos-chan [buf]
  (println :agos-chan buf)
  (MyManyToManyChannel. (buffers/ring-buffer 32) 0
                        (buffers/ring-buffer 32) 0
                        buf false))

