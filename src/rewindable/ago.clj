;; Wrappers around core.async that track parent-child relationships
;; and provide events.  Useful for building things like visualizations
;; and simulations.

(ns rewindable.ago
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
          (let [sm# ~sm
                sm-instance# (sm#)
                state# (ioc/aset-all! sm-instance#
                                      cljs.core.async.impl.ioc-helpers/USER-START-IDX
                                      c#)]
            (cljs.core.async.impl.ioc-helpers/run-state-machine-wrapped state#))))
       c#)))
