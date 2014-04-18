;; Custom SSA terminators to CLJS core async.

(ns rewindable.agos
  (:require [cljs.core.async.impl.ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(deftype RingBuffer [^:mutable head ^:mutable tail ^:mutable length ^:mutable arr]
  Object
  (pop [_]
    (when-not (zero? length)
      (let [x (aget arr tail)]
        (aset arr tail nil)
        (set! tail (js-mod (inc tail) (alength arr)))
        (set! length (dec length))
        x)))

  (unshift [_ x]
    (aset arr head x)
    (set! head (js-mod (inc head) (alength arr)))
    (set! length (inc length))
    nil)

  (unbounded-unshift [this x]
    (if (== (inc length) (alength arr))
      (.resize this))
    (.unshift this x))

  ;; Doubles the size of the buffer while retaining all the existing values
  (resize
    [_]
    (let [new-arr-size (* (alength arr) 2)
          new-arr (make-array new-arr-size)]
      (cond
       (< tail head)
       (do (buffers/acopy arr tail new-arr 0 length)
           (set! tail 0)
           (set! head length)
           (set! arr new-arr))

       (> tail head)
       (do (buffers/acopy arr tail new-arr 0 (- (alength arr) tail))
           (buffers/acopy arr 0 new-arr (- (alength arr) tail) head)
           (set! tail 0)
           (set! head length)
           (set! arr new-arr))

       (== tail head)
       (do (set! tail 0)
           (set! head 0)
           (set! arr new-arr)))))

  (cleanup [this keep?]
    (dotimes [x length]
      (let [v (.pop this)]
        (when ^boolean (keep? v)
          (.unshift this v))))))

(defn ring-buffer [n]
  (assert (> n 0) "Can't create a ring buffer of size 0")
  (RingBuffer. 0 0 0 (make-array n)))

; --------------------------------------------------------

(defn chan-buf [buf]
  (println :chan-buf buf)
  (channels/ManyToManyChannel. (ring-buffer 32) 0
                               (ring-buffer 32) 0
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
  (channels/ManyToManyChannel. (ring-buffer 32) 0
                               (ring-buffer 32) 0
                               buf false))

