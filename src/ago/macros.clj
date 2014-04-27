(ns ago.macros
  (:require [cljs.core.async.impl.ioc-macros :as ioc]))

(def my-async-terminators
  {'<!                    'ago.core/ssa-take
   'cljs.core.async/<!    'ago.core/ssa-take
   '>!                    'ago.core/ssa-put
   'cljs.core.async/>!    'ago.core/ssa-put
   'alts!                 'ago.core/ssa-alts
   'cljs.core.async/alts! 'ago.core/ssa-alts
   :Return                'ago.core/ssa-return-chan})

(defmacro ago [ago-world & body]
  (let [sm (ioc/state-machine body 1 &env my-async-terminators)]
    `(let [i# (str "ago-" ((:gen-id @~ago-world)))
           b# (ago.core/fifo-buffer ~ago-world (:seqv @~ago-world) i# 1)
           c# (ago.core/ago-chan-buf ~ago-world b#)
           sm# ~sm
           sma# (sm#)
           sma2# (ioc/aset-all! sma#
                                cljs.core.async.impl.ioc-helpers/USER-START-IDX
                                c#)]
       (ago.core/ago-reg-state-machine ~ago-world sma2# i#)
       (cljs.core.async.impl.dispatch/run
        (fn []
          (when (ago.core/seqv-alive? ~ago-world (ago.core/chan-seqv c#))
            (ago.core/ago-run-state-machine ~ago-world sma2# i#)
            (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped sma2#))))
       c#)))
