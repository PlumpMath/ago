;; Custom SSA terminators to CLJS core async.

(ns rewindable.ago
  (:require [cljs.core.async.impl.ioc-helpers :as ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

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

(defn make-ago-world []
  (let [last-id (atom 0)]
    (atom {:gen-id #(swap! last-id inc)
           :seqv [0]    ; Sequence numbers, grown when snapshot revived/branched.
           :bufs {}     ; Keyed by buf-id.
           :smas {}     ; Keyed by buf-id, value is state-machine array.
           :smas-new {} ; Same as :smas, but for new, not yet run goroutines.
           })))

(defn seqv+ [ago-world-now]
  (update-in ago-world-now [:seqv (dec (count (:seqv ago-world-now)))] inc))

; --------------------------------------------------------

; Persistent/immutable buffer implementation as an alternative to
; CLJS/core.async's default mutable RingBuffer, so that we can easily
; take ago-world snapshots.  Note: we have an unused length property
; in order to keep ManyToManyChannel happy.
(deftype FifoBuffer [ago-world seqv buf-id length max-length]
  Object
  (pop [_]
    ; Assumes nil is not a valid buffer item.
    (when-let [x (last (get-in @ago-world [:bufs buf-id]))]
      (swap! ago-world #(seqv+ (update-in % [:bufs buf-id] drop-last)))
      (when (empty? (get-in @ago-world [:bufs buf-id]))
        (swap! ago-world #(seqv+ (dissoc-in % [:bufs buf-id]))))
      x))

  (unshift [_ x]
    (swap! ago-world #(seqv+ (update-in % [:bufs buf-id] conj x)))
    nil)

  (unbounded-unshift [this x] ; From RingBuffer 'interface'.
    (.unshift this x))

  (resize [_] nil) ; From RingBuffer 'interface'.

  (cleanup [_ keep?]
    (swap! ago-world #(seqv+ (update-in % [:bufs buf-id]
                                        (fn [xs] (filter keep? xs))))))

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

(defn fifo-buffer [ago-world buf-id max-length]
  (FifoBuffer. ago-world (:seqv @ago-world) buf-id -123 max-length))

; --------------------------------------------------------

(defn ago-chan-buf [ago-world buf]
  (channels/ManyToManyChannel.
   (fifo-buffer ago-world (str (.-buf-id buf) "-takes") -1) 0
   (fifo-buffer ago-world (str (.-buf-id buf) "-puts") -1) 0
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
                       (fifo-buffer ago-world
                                    (str "ch-" ((:gen-id @ago-world)))
                                    buf-or-n)
                       buf-or-n)))))

; --------------------------------------------------------

(defn ago-reg-state-machine [ago-world state-machine-arr buf]
  (swap! ago-world #(seqv+ (assoc-in % [:smas-new (.-buf-id buf)] state-machine-arr))))

(defn ago-run-state-machine [ago-world state-machine-arr buf]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new (.-buf-id buf)])
                        (assoc-in [:smas (.-buf-id buf)] state-machine-arr)
                        (seqv+))))

(defn ago-dereg-state-machine [ago-world buf]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new (.-buf-id buf)])
                        (dissoc-in [:smas (.-buf-id buf)])
                        (seqv+))))

(defn ago-revive-state-machine [ago-world old-sma buf]
  (let [ch (rewindable.ago/ago-chan-buf ago-world buf)
        new-sma (acopy old-sma ((aget old-sma ioc-helpers/FN-IDX))
                       ioc-helpers/STATE-IDX) ; We depend on *-IDX ordering.
        new-sma2 (ioc/aset-all! new-sma ioc-helpers/USER-START-IDX ch)]
    (ago-reg-state-machine ago-world new-sma2 buf)
    (dispatch/run
     (fn []
       (ago-run-state-machine ago-world new-sma2 buf)
       (ioc-helpers/run-state-machine-wrapped new-sma2)))
    ch))

(defn ago-judge-state-machines [bufs-ss smas-ss smas-cur]
  (loop [ss-buf-ids (sort (keys smas-ss))
         cur-buf-ids (sort (keys smas-cur))
         acc-recycled-smas {} ; State machines shared by ss and cur (just need reset).
         acc-reborn-smas []]  ; State machines that need rebirth (were in ss only).
    (let [ss-buf-id (first ss-buf-ids)
          cur-buf-id (first cur-buf-ids)]
      (cond
       (= nil ss-buf-id cur-buf-id)
       [acc-recycled-smas acc-reborn-smas]

       (and ss-buf-id ; In ss but not in cur.
            (or (nil? cur-buf-id)
                (< ss-buf-id cur-buf-id)))
       (recur (rest ss-buf-ids) cur-buf-ids
              acc-recycled-smas
              (let [ss-buf (get bufs-ss ss-buf-id)
                    sma-old (get smas-ss ss-buf-id)]
                ; Later, can invoke (ago-revive-state-machine agw sma-old ss-buf).
                (conj acc-reborn-smas [sma-old ss-buf])))

       (and cur-buf-id ; In cur but not in ss, so drop cur's sma.
            (or (nil? ss-buf-id)
                (< cur-buf-id ss-buf-id)))
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

       :else (println "UNEXPECTED case in ago-judge-state-machines")))))

; --------------------------------------------------------

(defn ago-take [state blk ^not-native c]
  (cljs.core.async.impl.ioc-helpers/take! state blk c))

(defn ago-put [state blk ^not-native c val]
  (cljs.core.async.impl.ioc-helpers/put! state blk c val))

(defn ago-alts [state cont-block ports & rest]
  (apply cljs.core.async.impl.ioc-helpers/ioc-alts! state cont-block ports rest))

(defn ago-return-chan [state value]
  (let [^not-native c (aget state ioc-helpers/USER-START-IDX)]
    (when-not (nil? value)
      (protocols/put! c value (ioc-helpers/fn-handler (fn [] nil))))
    (protocols/close! c)
    (ago-dereg-state-machine (.-ago-world (.-buf c)) (.-buf c))
    c))

; --------------------------------------------------------

(defn copy-sma-map [sma-map] ; Copy a hash-map of <buf-id => state-machine-array>.
  (apply hash-map (mapcat (fn [[buf-id sma]] [buf-id (aclone sma)])
                          sma-map)))

(defn ago-snapshot [ago-world]
  (let [ago-world-now @ago-world]
    (-> ago-world-now ; The mutable sma's need explicit cloning.
        (assoc :smas (copy-sma-map (:smas ago-world-now)))
        (assoc :smas-new (copy-sma-map (:smas-new ago-world-now))))))

(defn ago-restore [ago-world snapshot]
    (let [ss (ago-snapshot (atom snapshot)) ; Re-snapshot so snapshot stays immutable.
          [recycled-smas reborn-smas] (ago-judge-state-machines (:bufs ss)
                                                                (:smas ss)
                                                                (:smas @ago-world))
          [recycled-smasN reborn-smasN] (ago-judge-state-machines (:bufs ss)
                                                                  (:smas-new ss)
                                                                  (:smas-new @ago-world))
          branch-id (- ((:gen-id @ago-world)))] ; Negative in case snapshot re-restored.
      (swap! ago-world
             #(-> %
                  (assoc :seqv (conj (:seqv ss) branch-id 0))
                  (assoc :bufs (:bufs ss))
                  (assoc :smas recycled-smas)
                  (assoc :smas-new recycled-smasN)))
      (doseq [[sma-old ss-buf] reborn-smas]
        (ago-revive-state-machine ago-world sma-old ss-buf))
      (doseq [[sma-old ss-buf] reborn-smasN]
        (ago-revive-state-machine ago-world sma-old ss-buf))
      ago-world))

