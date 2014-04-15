;; Custom SSA terminators to CLJS core async.

(ns rewindable.agos
  (:require [cljs.core.async.impl.ioc-helpers]
            [cljs.core.async.impl.buffers]
            [cljs.core.async.impl.channels]))

(defn agos-take [state blk ^not-native c]
  (cljs.core.async.impl.ioc-helpers/take! state blk c))

(defn agos-put [state blk ^not-native c val]
  (cljs.core.async.impl.ioc-helpers/put! state blk c val))

(defn agos-alts [state cont-block ports & rest]
  (apply cljs.core.async.impl.ioc-helpers/ioc-alts! state cont-block ports rest))

(defn agos-return-chan [state value]
  (cljs.core.async.impl.ioc-helpers/return-chan state value))

(defn agos-chan [buf]
  (cljs.core.async.impl.channels/ManyToManyChannel. (buffers/ring-buffer 32) 0
                                                    (buffers/ring-buffer 32) 0
                                                    buf false))

