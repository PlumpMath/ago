;; Custom SSA terminators to CLJS core async.

(ns rewindable.ago
  (:require [cljs.core.async.impl.ioc-helpers :as ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(def ^:const FN-IDX 0)
(def ^:const STATE-IDX 1)
(def ^:const VALUE-IDX 2)
(def ^:const BINDINGS-IDX 3)
(def ^:const EXCEPTION-FRAMES 4)
(def ^:const CURRENT-EXCEPTION 5)
(def ^:const USER-START-IDX 6)

(defn acopy [asrc adst & start-idx]
  (loop [idx (or start-idx 0)]
    (when (< idx (alength asrc))
      (aset adst idx (aget asrc idx))
      (recur (inc idx))))
  adst)

(defn dissoc-in [m [k & ks :as keys]]
  (if ks
    (if-let [next-map (get m k)]
      (let [new-map (dissoc-in next-map ks)]
        (if (seq new-map)
          (assoc m k new-map)
          (dissoc m k)))
      m)
    (dissoc m k)))

; --------------------------------------------------------

(defn copy-sma-map [sma-map] ; Copy a hash-map of <buf-id => state-machine-array>.
  (apply hash-map (mapcat (fn [[buf-id sma]] [buf-id (aclone sma)])
                          sma-map)))

; --------------------------------------------------------

(defn make-ago-world []
  (let [last-id (atom 0)]
    (atom {:gen-id #(swap! last-id inc)
           :bufs {}     ; Keyed by buf-id.
           :smas {}     ; Keyed by buf-id, value is state-machine array.
           :smas-new {} ; Same as :smas, but for new, not yet run goroutines.
           })))

(defn ago-snapshot [ago-world-now]
  (-> ago-world-now
      (assoc :smas (copy-sma-map (:smas ago-world-now)))
      (assoc :smas-new (copy-sma-map (:smas-new ago-world-now)))))

; --------------------------------------------------------

; Persistent/immutable buffer implementation as an alternative to
; CLJS/core.async's default mutable RingBuffer, so that we can easily
; take ago-world snapshots.  Note: we have an unused length property
; in order to keep ManyToManyChannel happy.
(deftype FifoBuffer [ago-world buf-id length max-length]
  Object
  (pop [_]
    ; Assumes nil is not a valid buffer item.
    (when-let [x (last (get-in @ago-world [:bufs buf-id]))]
      (swap! ago-world #(update-in % [:bufs buf-id] drop-last))
      (when (empty? (get-in @ago-world [:bufs buf-id]))
        (swap! ago-world #(dissoc-in % [:bufs buf-id])))
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

(defn fifo-buffer [ago-world label max-length]
  (FifoBuffer. ago-world (str label "-" ((:gen-id @ago-world)))
               -123 max-length))

; --------------------------------------------------------

(defn ago-chan-buf [ago-world buf]
  (channels/ManyToManyChannel. (fifo-buffer ago-world :takes -1) 0
                               (fifo-buffer ago-world :puts -1) 0
                               buf false))

(defn ago-chan
  "Creates a channel with an optional buffer. If buf-or-n is a
  number, will create and use a buffer with that max-length."
  ([ago-world] (ago-chan ago-world nil))
  ([ago-world buf-or-n]
     (let [buf-or-n (if (= buf-or-n 0)
                      nil
                      buf-or-n)]
       (ago-chan-buf ago-world
                     (if (number? buf-or-n)
                       (fifo-buffer ago-world :buf buf-or-n)
                       buf-or-n)))))

; --------------------------------------------------------

(defn ago-reg-state-machine [ago-world state-machine-arr buf]
  (swap! ago-world #(assoc-in % [:smas-new (.-buf-id buf)] state-machine-arr)))

(defn ago-run-state-machine [ago-world state-machine-arr buf]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new (.-buf-id buf)])
                        (assoc-in [:smas (.-buf-id buf)] state-machine-arr))))

(defn ago-dereg-state-machine [ago-world buf]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new (.-buf-id buf)])
                        (dissoc-in [:smas (.-buf-id buf)]))))

(defn ago-revive-state-machine [ago-world old-sma buf]
  (let [ch (rewindable.ago/ago-chan-buf ago-world buf)
        new-sma (acopy old-sma ((aget old-sma FN-IDX)) STATE-IDX)
        new-sma2 (ioc/aset-all! new-sma ioc-helpers/USER-START-IDX ch)]
    (ago-reg-state-machine ~ago-world new-sma2 buf)
    (dispatch/run
     (fn []
       (ago-run-state-machine ~ago-world new-sma2 buf)
       (ioc-helpers/run-state-machine-wrapped new-sma2)))
    ch))

; --------------------------------------------------------

(defn ago-take [state blk ^not-native c]
  (cljs.core.async.impl.ioc-helpers/take! state blk c))

(defn ago-put [state blk ^not-native c val]
  (cljs.core.async.impl.ioc-helpers/put! state blk c val))

(defn ago-alts [state cont-block ports & rest]
  (apply cljs.core.async.impl.ioc-helpers/ioc-alts! state cont-block ports rest))

(defn ago-return-chan [state value]
  (let [^not-native c (aget state USER-START-IDX)]
    (when-not (nil? value)
      (protocols/put! c value (ioc-helpers/fn-handler (fn [] nil))))
    (protocols/close! c)
    (ago-dereg-state-machine (.-ago-world (.-buf c)) (.-buf c))
    c))

