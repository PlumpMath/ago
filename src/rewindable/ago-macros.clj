;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns rewindable.ago-macros
  (:require [cljs.core.async.impl.ioc-macros :as ioc]))

; From https://github.com/clojure/core.async/blob/master/src/main/clojure/cljs/core/async/impl/ioc_macros.clj
(def ^:const FN-IDX 0)
(def ^:const STATE-IDX 1)
(def ^:const VALUE-IDX 2)
(def ^:const BINDINGS-IDX 3)
(def ^:const EXCEPTION-FRAMES 4)
(def ^:const CURRENT-EXCEPTION 5)
(def ^:const USER-START-IDX 6)

(def my-async-terminators
  {'<!                    'rewindable.ago/ago-take
   'cljs.core.async/<!    'rewindable.ago/ago-take
   '>!                    'rewindable.ago/ago-put
   'cljs.core.async/>!    'rewindable.ago/ago-put
   'alts!                 'rewindable.ago/ago-alts
   'cljs.core.async/alts! 'rewindable.ago/ago-alts
   :Return                'rewindable.ago/ago-return-chan})

; From https://github.com/clojure/core.async/blob/master/src/main/clojure/cljs/core/async/macros.clj
(defmacro ago [ago-world & body]
  (let [sm (ioc/state-machine body 1 &env my-async-terminators)]
    `(let [b# (rewindable.ago/fifo-buffer ~ago-world :ago 1)
           c# (rewindable.ago/ago-chan-buf ~ago-world b#)
           sm# ~sm
           sma# (sm#)
           sma2# (ioc/aset-all! sma#
                                cljs.core.async.impl.ioc-helpers/USER-START-IDX
                                c#)]
       (rewindable.ago/ago-reg-state-machine ~ago-world sma2# b#)
       (cljs.core.async.impl.dispatch/run
        (fn []
          (rewindable.ago/ago-run-state-machine ~ago-world sma2# b#)
          (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped sma2#)))
       c#)))
