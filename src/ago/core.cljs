;; The 'ago' library adds some limited support for world snapshots and
;; snapshot restoration on top of cljs.core.async.  This might be
;; useful for use cases like discrete event simulations and
;; visualizations.

(ns ago.core
  (:require-macros [cljs.core.async.impl.ioc-macros :as ioc-macros])
  (:require [cljs.core.async.impl.ioc-helpers :as ioc-helpers]
            [cljs.core.async.impl.buffers :as buffers]
            [cljs.core.async.impl.channels :as channels]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.protocols :as protocols]))

(defn acopy [asrc adst & start-idx]
  (loop [idx (or (first start-idx) 0)]
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

(defn random-array [n]
  (let [a (make-array n)]
    (dotimes [x n] (aset a x 0))
    (loop [i 1] (if (>= i n)
                  a
                  (do
                    (let [j (rand-int i)]
                      (aset a i (aget a j))
                      (aset a j i)
                      (recur (inc i))))))))

(defn now [] (.now js/Date))

; --------------------------------------------------------

(defn make-ago-world [app-data]
  (let [last-id (atom 0)]
    (atom {:app-data app-data ; Opaque, for application-specific needs.
           :gen-id #(swap! last-id inc) ; Unique even across snapshots.
           :seqv [0]          ; An seqv vector is grown during a restore.
           :bufs {}           ; Keyed by buf-id, value is FifoBuffer.
           :smas {}           ; Keyed by buf-id, value is state-machine array.
           :smas-new {}       ; Like :smas, but for new, not yet run goroutines.
           :closed {}         ; Keyed by buf-id, value is bool on chan closure.
           :logical-speed 1.0 ; Logical-speed * physical-delta = logical-delta.
           :logical-ms 0      ; Logical time/msecs, which is always updated
           :physical-ms (now) ; at the same time as physical-ms time snapshot.
           :timeouts (sorted-map) ; Keyed by logical-ms => '(timeout-ch ...).
           :latches {}            ; Keyed by latch-id => true (see make-latch).
           })))                   ; TODO: The :closed map needs GC.

(defn seqv+ [ago-world-now]
  (if (zero? (:logical-speed ago-world-now)) ; No seqv inc when at zero speed.
    ago-world-now
    (let [t (now)]
      (-> ago-world-now
          (update-in [:seqv (dec (count (:seqv ago-world-now)))] inc)
          (update-in [:logical-ms]
                     (fn [prev] (+ prev (* (- t (:physical-ms ago-world-now))
                                           (:logical-speed ago-world-now)))))
          (assoc :physical-ms t)))))

(defn compare-seqvs [seqv-x seqv-y]
  (loop [xs seqv-x
         ys seqv-y]
    (let [x (first xs)
          y (first ys)]
      (cond (= nil x y) 0
            (nil? x) 1 ; Normal compare handles length mismatches wrong.
            (nil? y) -1
            (= x y) (recur (rest xs) (rest ys))
            :else (- x y)))))

(defn seqv-alive? [ago-world seqv]
  (if (and ago-world seqv)
    (<= (compare-seqvs seqv (:seqv @ago-world)) 0)
    true)) ; Not an ago managed thing, so assume it's alive.

; --------------------------------------------------------

(defn make-latch [ago-world]
  ; Unlike the original alt-latch, match-latch keeps state in the ago-world.
  (let [latch-id ((:gen-id @ago-world))]
    (swap! ago-world #(assoc-in % [:latches latch-id] true))
    (reify
      protocols/Handler
      (active? [_] (get-in @ago-world [:latches latch-id]))
      (commit [_]
        (swap! ago-world #(dissoc-in % [:latches latch-id]))
        true))))

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

(defn fifo-buffer [ago-world segv buf-id max-length]
  (FifoBuffer. ago-world segv buf-id -123 max-length))

; --------------------------------------------------------

(deftype WrapManyToManyChannel [ago-world seqv buf-id m2m-ch]
  protocols/WritePort
  (put! [this val ^not-native handler]
    (set! (.-closed m2m-ch) (protocols/closed? this))
    (protocols/put! m2m-ch val handler))

  protocols/ReadPort
  (take! [this ^not-native handler]
    (set! (.-closed m2m-ch) (protocols/closed? this))
    (protocols/take! m2m-ch handler))

  protocols/Channel
  (closed? [_]
    (get-in @ago-world [:closed buf-id] false))
  (close! [this]
    (set! (.-closed m2m-ch) (protocols/closed? this))
    (swap! ago-world #(assoc-in % [:closed buf-id] true))
    (protocols/close! m2m-ch)))

; --------------------------------------------------------

(defn ago-chan-buf [ago-world buf & default-buf-id]
  (let [buf-id (if buf
                 (.-buf-id buf)
                 (or (first default-buf-id) "unknown"))
        seqv (:seqv @ago-world)]
    (WrapManyToManyChannel. ago-world seqv buf-id
                            (channels/ManyToManyChannel.
                             (fifo-buffer ago-world seqv (str buf-id "-takes") -1) 0
                             (fifo-buffer ago-world seqv (str buf-id "-puts") -1) 0
                             buf false))))

(defn ago-chan
  "Creates a channel with an optional buffer. If buf-or-n is a
  number, will create and use a buffer with that max-length."
  ([ago-world] (ago-chan ago-world nil))
  ([ago-world buf-or-n]
     (let [ch-id (str "ch-" ((:gen-id @ago-world)))
           buf-or-n (if (= buf-or-n 0)
                      nil
                      buf-or-n)]
       (ago-chan-buf ago-world
                     (if (number? buf-or-n)
                       (fifo-buffer ago-world (:seqv @ago-world) ch-id buf-or-n)
                       buf-or-n)
                     ch-id))))

(defn chan-ago-world [ch]
  (when (instance? WrapManyToManyChannel ch)
    (.-ago-world ch)))

(defn chan-seqv [ch]
  (when (instance? WrapManyToManyChannel ch)
    (.-seqv ch)))

(defn chan-buf-id [ch]
  (when (instance? WrapManyToManyChannel ch)
    (.-buf-id ch)))

; --------------------------------------------------------

(defn ago-reg-state-machine [ago-world state-machine-arr buf-id]
  (swap! ago-world #(-> %
                        (assoc-in [:smas-new buf-id] state-machine-arr)
                        (dissoc-in [:smas buf-id])
                        (seqv+))))

(defn ago-run-state-machine [ago-world state-machine-arr buf-id]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new buf-id])
                        (assoc-in [:smas buf-id] state-machine-arr)
                        (seqv+))))

(defn ago-dereg-state-machine [ago-world buf-id]
  (swap! ago-world #(-> %
                        (dissoc-in [:smas-new buf-id])
                        (dissoc-in [:smas buf-id])
                        (seqv+))))

(defn ago-revive-state-machine [ago-world old-sma buf-id]
  (let [buf (fifo-buffer ago-world (:seqv @ago-world) buf-id 1)
        ch (ago-chan-buf ago-world buf)
        new-sma (acopy old-sma ((aget old-sma ioc-helpers/FN-IDX))
                       ioc-helpers/STATE-IDX)] ; We depend on *-IDX ordering.
    (ioc-macros/aset-all! new-sma ioc-helpers/USER-START-IDX ch)
    (ago-run-state-machine ago-world new-sma buf-id)
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
              (let [sma-old (get smas-ss ss-buf-id)]
                ; Later, can invoke (ago-revive-state-machine agw sma-old ss-buf-id).
                (conj acc-reborn-smas [sma-old ss-buf-id])))

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

(defn fn-handler [active-cb f]
  (reify
    protocols/Handler
    (active? [_] (active-cb))
    (commit [_] (when (active-cb) f))))

(defn ssa-take [state blk ^not-native c]
  (let [ago-ch (aget state ioc-helpers/USER-START-IDX)
        ago-world (chan-ago-world ago-ch)
        active? #(seqv-alive? ago-world (chan-seqv ago-ch))]
    (if-let [cb (protocols/take!
                 c (fn-handler
                    active?
                    (fn [x]
                      (when (active?)
                        (let [s (get-in @ago-world [:smas (chan-buf-id ago-ch)] state)]
                          (ioc-macros/aset-all! s ioc-helpers/VALUE-IDX
                                                x ioc-helpers/STATE-IDX blk)
                          (ioc-helpers/run-state-machine-wrapped s))))))]
      (do (ioc-macros/aset-all! state ioc-helpers/VALUE-IDX
                                @cb ioc-helpers/STATE-IDX blk)
          :recur)
      nil)))

(defn ssa-put [state blk ^not-native c val]
  (let [ago-ch (aget state ioc-helpers/USER-START-IDX)
        ago-world (chan-ago-world ago-ch)
        active? #(seqv-alive? ago-world (chan-seqv ago-ch))]
    (if-let [cb (protocols/put!
                 c val
                 (fn-handler
                  active?
                  (fn [ret-val]
                    (when (active?)
                      (let [s (get-in @ago-world [:smas (chan-buf-id ago-ch)] state)]
                        (ioc-macros/aset-all! s ioc-helpers/VALUE-IDX
                                              ret-val ioc-helpers/STATE-IDX blk)
                        (ioc-helpers/run-state-machine-wrapped s))))))]
      (do (ioc-macros/aset-all! state ioc-helpers/VALUE-IDX
                                @cb ioc-helpers/STATE-IDX blk)
          :recur)
      nil)))

(defn alt-handler [latch cb]
  (reify
    protocols/Handler
    (active? [_] (protocols/active? latch))
    (commit [_]
      (protocols/commit latch)
      cb)))

(defn do-alts [ago-world fret ports opts]
  ; returns derefable [val port] if immediate, nil if enqueued.
  (let [latch (make-latch ago-world)
        n (count ports)
        idxs (random-array n)
        priority (:priority opts)
        ret (loop [i 0]
              (when (< i n)
                (let [idx (if priority i (aget idxs i))
                      port (nth ports idx)
                      wport (when (vector? port) (port 0))
                      vbox (if wport
                             (let [val (port 1)]
                               (protocols/put! wport val
                                               (alt-handler latch #(fret [% wport]))))
                             (protocols/take! port
                                              (alt-handler latch #(fret [% port]))))]
                  (if vbox
                    (channels/box [@vbox (or wport port)])
                    (recur (inc i))))))]
    (or ret
        (when (contains? opts :default)
          (when-let [got (and (protocols/active? latch)
                              (protocols/commit latch))]
            (channels/box [(:default opts) :default]))))))

(defn ssa-alts [state cont-block ports & {:as opts}]
  (let [ago-ch (aget state ioc-helpers/USER-START-IDX)
        ago-world (chan-ago-world ago-ch)]
    (ioc-macros/aset-all! state ioc-helpers/STATE-IDX cont-block)
    (when-let [cb (do-alts ago-world
                           (fn [val]
                             (let [[v c] val
                                   s (get-in @ago-world [:smas (chan-buf-id ago-ch)] state)]
                               (when (and (seqv-alive? ago-world (chan-seqv ago-ch))
                                          (seqv-alive? ago-world (chan-seqv c)))
                                 (ioc-macros/aset-all! s ioc-helpers/VALUE-IDX val)
                                 (ioc-helpers/run-state-machine-wrapped s))))
                           ports
                           opts)]
      (ioc-macros/aset-all! state ioc-helpers/VALUE-IDX @cb)
      :recur)))

(defn ssa-return-chan [state value]
  (let [^not-native c (aget state ioc-helpers/USER-START-IDX)
        ago-world (chan-ago-world c)
        active? #(seqv-alive? ago-world (chan-seqv c))]
    (when (active?)
      (when-not (nil? value)
        (protocols/put! c value (fn-handler active? (fn [] nil))))
      (protocols/close! c))
    (ago-dereg-state-machine ago-world (chan-buf-id c))
    c))

; --------------------------------------------------------

(def curr-js-timeout-id (atom nil)) ; Value is nil or js-timeout-id.

(defn timeout-handler [ago-world]
  (swap! ago-world seqv+) ; Updates logical-ms.
  (swap! curr-js-timeout-id (fn [x]
                              (when x (js/clearTimeout x))
                              nil))
  (let [logical-ms (:logical-ms @ago-world)
        timeouts2 (loop [timeouts (:timeouts @ago-world)]
                    (if-let [[soonest-ms chs] (first timeouts)]
                      (if (<= soonest-ms logical-ms)
                        (do (doseq [ch chs]
                              (protocols/close! ch))
                            (recur (dissoc timeouts soonest-ms)))
                        timeouts)))]
    (swap! ago-world #(assoc % :timeouts timeouts2))
    (when-let [[soonest-ms chs] (first timeouts2)]
      (reset! curr-js-timeout-id (js/setTimeout (fn [] (timeout-handler ago-world))
                                                (max 0 (- soonest-ms logical-ms)))))))

(defn ago-timeout [ago-world delay-ms] ; Logical milliseconds from now.
  (let [timeout-at (+ delay-ms (:logical-ms @ago-world))
        timeout-ch (ago-chan-buf ago-world nil
                                 (str "timeout-" ((:gen-id @ago-world))))]
    (swap! ago-world                               ; Persistent sorted-map for
           #(update-in % [:timeouts]               ; timeouts instead of skip-list
                       (fn [m] (assoc m timeout-at ; to provide snapshot'ability.
                                      (conj (get-in % [:timeouts timeout-at])
                                            timeout-ch)))))
    (timeout-handler ago-world)
    timeout-ch))

; --------------------------------------------------------

(defn copy-sma-map [sma-map] ; Copy a hash-map of <buf-id => state-machine-array>.
  (apply hash-map (mapcat (fn [[buf-id sma]] [buf-id (aclone sma)]) sma-map)))

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
      (swap! ago-world #(-> %
                            (assoc :seqv (conj (:seqv ss) branch-id 0))
                            (assoc :bufs (:bufs ss))
                            (assoc :smas recycled-smas)
                            (assoc :smas-new recycled-smasN)
                            (assoc :closed (:closed ss))
                            (assoc :logical-ms (:logical-ms ss))
                            (assoc :physical-ms (now))
                            (assoc :timeouts (:timeouts ss))
                            (assoc :latches (:latches ss))))
      (doseq [[sma-old ss-buf-id] reborn-smas]
        (ago-revive-state-machine ago-world sma-old ss-buf-id))
      (doseq [[sma-old ss-buf-id] reborn-smasN]
        (ago-revive-state-machine ago-world sma-old ss-buf-id))
      (when (seq (:timeouts @ago-world))
        (timeout-handler ago-world))
      ago-world))
