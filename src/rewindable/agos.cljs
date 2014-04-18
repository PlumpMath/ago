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

(defn chan-buf [buf]
  (println :chan-buf buf)
  (channels/ManyToManyChannel. (buffers/ring-buffer 32) 0
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
  (channels/ManyToManyChannel. (buffers/ring-buffer 32) 0
                               (buffers/ring-buffer 32) 0
                               buf false))

