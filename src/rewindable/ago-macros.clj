;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns rewindable.ago-macros
  (:require [cljs.core.async.impl.ioc-macros :as ioc]))

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
    `(let [i# (str "ago-" ((:gen-id @~ago-world)))
           b# (rewindable.ago/fifo-buffer ~ago-world i# 1)
           c# (rewindable.ago/ago-chan-buf ~ago-world b#)
           sm# ~sm
           sma# (sm#)
           sma2# (ioc/aset-all! sma#
                                cljs.core.async.impl.ioc-helpers/USER-START-IDX
                                c#)]
       (rewindable.ago/ago-reg-state-machine ~ago-world sma2# b#)
       (cljs.core.async.impl.dispatch/run
        (fn []
          (when (rewindable.ago/seqv-alive? ~ago-world (rewindable.ago/chan-seqv c#))
            (rewindable.ago/ago-run-state-machine ~ago-world sma2# b#)
            (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped sma2#))))
       c#)))
