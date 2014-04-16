;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns rewindable.ago
  (:require [cljs.core.async.impl.ioc-macros :as ioc]))

; From https://github.com/clojure/core.async/blob/master/src/main/clojure/cljs/core/async/impl/ioc_macros.clj
(def my-async-terminators
  {'<!                    'rewindable.agos/agos-take
   'cljs.core.async/<!    'rewindable.agos/agos-take
   '>!                    'rewindable.agos/agos-put
   'cljs.core.async/>!    'rewindable.agos/agos-put
   'alts!                 'rewindable.agos/agos-alts
   'cljs.core.async/alts! 'rewindable.agos/agos-alts
   :Return                'rewindable.agos/agos-return-chan})

; From https://github.com/clojure/core.async/blob/master/src/main/clojure/cljs/core/async/macros.clj
(defmacro my-go [& body]
  (let [sm (ioc/state-machine body 1 &env my-async-terminators)]
    `(let [c# (cljs.core.async/chan 1)]
       (cljs.core.async.impl.dispatch/run
        (fn []
          (let [f# ~sm
                state# (-> (f#)
                           (ioc/aset-all! cljs.core.async.impl.ioc-helpers/USER-START-IDX c#))]
            (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped state#))))
       c#)))
