;; Custom SSA terminators to CLJS core async.

(ns rewindable.agos
  (:require [cljs.core.async.impl.ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(defn make-uverse []
  (let [last-id (atom 0)]
    (atom {:gen-id #(swap! last-id inc)
           :bufs {}})))

; Persistent buffer implementation as an alternative to
; CLJS/core.async's default mutable RingBuffer.
(deftype UverseBuf [uverse buf-id ^:mutable length]
  Object
  (pop [_]
    (when-let [x (last (get-in @uverse [:bufs buf-id]))]
      (swap! uverse #(update-in % [:bufs buf-id] drop-last))
      (set! length (dec length))
      x))

  (unshift [_ x]
    (swap! uverse #(update-in % [:bufs buf-id] conj x))
    (set! length (inc length))
    nil)

  (unbounded-unshift [this x] ; From RingBuffer 'interface'.
    (.unshift this x))

  (resize [_] nil) ; From RingBuffer 'interface'.

  (cleanup [_ keep?]
    (swap! uverse #(update-in % [:bufs buf-id] (fn [xs] (filter keep? xs))))
    (set! length (count (get-in @uverse [:bufs buf-id])))))

(defn uverse-buf [uverse]
  (UverseBuf. uverse ((:gen-id @uverse)) 0))

; --------------------------------------------------------

(defn chan-buf [buf]
  (println :chan-buf buf)
  (channels/ManyToManyChannel. (uverse-buf (make-uverse)) 0
                               (uverse-buf (make-uverse)) 0
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

