;; Custom SSA terminators to CLJS core async.

(ns rewindable.ago
  (:require [cljs.core.async.impl.ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(defn make-ago-world []
  (let [last-id (atom 0)]
    (atom {:gen-id #(swap! last-id inc)
           :bufs {}})))

; Persistent/immutable buffer implementation as an alternative to
; CLJS/core.async's default mutable RingBuffer, so that we can easily
; take ago-world snapshots.  Note: we have an unused length property
; in order to keep ManyToManyChannel happy.
(deftype FifoBuffer [ago-world buf-id length max-length]
  Object
  (pop [_]
    (when-let [x (last (get-in @ago-world [:bufs buf-id]))]
      (swap! ago-world #(update-in % [:bufs buf-id] drop-last))
      x))

  (unshift [_ x]
    (swap! ago-world #(update-in % [:bufs buf-id] conj x))
    nil)

  (unbounded-unshift [this x] ; From RingBuffer 'interface'.
    (.unshift this x))

  (resize [_] nil) ; From RingBuffer 'interface'.

  (cleanup [_ keep?]
    (swap! ago-world #(update-in % [:bufs buf-id] (fn [xs] (filter keep? xs)))))

  protocols/Buffer
  (full? [this]
    (and (>= max-length 0)
         (>= (count this) max-length)))
  (remove! [this]
    (.pop this))
  (add! [this itm]
    (.unshift this itm))

  cljs.core/ICounted
  (-count [this]
    (count (get-in @ago-world [:bufs buf-id]))))

(defn fifo-buffer [ago-world max-length]
  (FifoBuffer. ago-world ((:gen-id @ago-world)) 0 max-length))

; --------------------------------------------------------

(defn ago-world-chan-buf [ago-world buf]
  (channels/ManyToManyChannel. (fifo-buffer ago-world -1) 0
                               (fifo-buffer ago-world -1) 0
                               buf false))

(defn ago-world-chan
  "Creates a channel with an optional buffer. If buf-or-n is a
  number, will create and use a buffer with that max-length."
  ([ago-world] (ago-world-chan ago-world nil))
  ([ago-world buf-or-n]
     (let [buf-or-n (if (= buf-or-n 0)
                      nil
                      buf-or-n)]
       (ago-world-chan-buf ago-world
                           (if (number? buf-or-n)
                             (fifo-buffer ago-world buf-or-n)
                             buf-or-n)))))

; --------------------------------------------------------

(defn ago-take [state blk ^not-native c]
  (cljs.core.async.impl.ioc-helpers/take! state blk c))

(defn ago-put [state blk ^not-native c val]
  (cljs.core.async.impl.ioc-helpers/put! state blk c val))

(defn ago-alts [state cont-block ports & rest]
  (apply cljs.core.async.impl.ioc-helpers/ioc-alts! state cont-block ports rest))

(defn ago-return-chan [state value]
  (cljs.core.async.impl.ioc-helpers/return-chan state value))

