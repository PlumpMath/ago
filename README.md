# ago - [![Build Status](https://travis-ci.org/steveyen/ago.png?branch=master)](https://travis-ci.org/steveyen/ago)

A time travel library for clojurescript core.async

The "ago" library provides a limited form of time-travel (snapshots
and restores) on top of (clojurescript) core.async.

## Motivations

This library was originally meant to help folks who are building
discrete event models and simulations.  For example, I was trying to
model and simulate the clients, servers and network protocols of a
distributed system, and core.async was a good building block to
model all the concurrent activity.

By using clojurescript, too, I could run my simulations in modern web
browsers and get an easy to use GUI and nice visualizations (svg, css).

But, one issue was that it was not clear how to "rewind the world"
back to a previous state so that one can replay a simulation (and
perhaps take alternative "what-if" pathways).

What I wanted was to snapshot all the inflight go routines, channels,
timeouts and all their inherent state ("snapshot"), and then later
restore the simulated world to that previous snapshot ("rewind" or
"restore").

The vision is that UI frameworks like Om
(https://github.com/swannodette/om) allow for easy rollback or undo
and redo of app-state (yay, immutable/persistent data structures).  In
my case, I wanted a big part of the app-state to be a bunch of
go routines and channels and to be able to undo/redo all that
asynchronous activity.

The "ago" library, which is built on top of clojurescript core.async,
is meant to provide that snapshot and restore ability, so that one
can have "TiVo for your simulation".

## How To Use

The "ago" library provides API's which wrap around the main API's of
core.async.  These ago wrapper functions should only be used where you
want snapshot/rewind-ability, as they have additional overhead (from
using immutable/persistent data structures).

The ago library API's usually have a first parameter of a
"world-handle".  For example...

* Instead of (go ...) it's (ago world-handle ...)
* Instead of (chan) it's (ago-chan world-handle)
* Instead of (timeout delay) it's (ago-timeout world-handle delay)

There's an API to create a world-handle, where you can provide an
associated, opaque app-data (use app-data for whatever you want)...

* (make-ago-world app-data) => world-handle

Then you can create "ago channels" and "ago routines" with that
world-handle...

    (let [ch1 (ago-chan world-handle)]
      (ago world-handle
        (>! ch1 "hello world"))
      (ago world-handle
        ["I received" (<! ch1)]))

And, to snapshot a world, using ago-snapshot...

    (let [snapshot (ago-snapshot world-handle)]
      ...
      )

And, to restore a previous snapshot...

    (ago-restore world-handle snapshot)

Because the ago routines and ago channels have additional overhead,
you should use regular clojurescript core.async API functions (go,
chan, timeout) for areas that you don't want to snapshot (i.e., not
part of your simulation/model), such as GUI-related go routines that
are handling button clicks or rendering output.

### Time

If you use the ago-timeout feature, you may want to slow down
or speed up simulation time (or "logical time").

That is, there's a distinction between logical time and physical clock
time, where logical time can proceed at a different pace than physical
clock time.  Just set the :logical-speed value in a world-handle to
not 1.0.  This may be useful for some kinds of simulations.

Of note, logical time can rollback to lower values when you restore a
previous snapshot, which can have interesting, unintended rendering
effects if you're just doing simple delta "functional reactive" style
visualizations.

Logical time starts at 0 when you invoke (make-ago-world ...).

## LICENSE

Eclipse Public License

## Building

    lein cljsbuild once

This was inobvious to me until Aaron Miller pointed it out to me,
to help during development...

    lein cljsbuild auto

## Underneath The Hood

This section might be interesting only to those folks who get into how
core.async works or who want to understand more of ago's limitations.

ClojureScript provides hooks in its core.async macros which transform
go blocks to SSA form, and the ago library utilizes those hooks to
interpose its own take/put/alts callbacks so that ago has access to
the state machine arrays of each "go routine".  With those hooks the
ago library can then register those state machine arrays into its
world-handle.

A world-handle is a just an atom to an immutable/persistent
associative hash-map.

Also, instead of using clojurescript core.async's default buffer
implementation (a mutable RingBuffer), the ago library instead
requires that you use its immutable/persistent buffer implementation
(fifo-queue).  These buffers are also all registered into the
world-handle.

Because the world-handle holds onto all relevant core.async data, a
snapshot is then implemented by copying a world-handle and also
cloning any contents of registered state-machine-arrays.  The buffer
snapshotting comes "for free" due to ago's use of immutable/persistent
buffer implemenations.

A restore is then copying a previous world-handle back into place,
and copying back any previous contents of the state-machine-arrays.

In short, this snapshotting and rewinding all works only if you use
immutable/persistent data structures throughout your model.

Each world-handle also tracks a branching version vector, so that any
inflight go routines can detect that "hey, I'm actually a go routine
apparently spawned on a branch that's no longer the main timeline, so
I should exit (and get GC'ed)".

The ago library also has its own persistent/immutable
re-implementation of the timeout queue (instead of core.async's
mutable skip-list implementation), again for easy snapshot'ability.

Although ago was written to not have any changes to core.async, one
issue with ago's current approach is that it may be brittle, where
changes to clojurescript core.async's SSA implementation or
Channel/Buffer/Handler protocols can easily break ago.

## TODO

* Need to learn how to test in clojurescript.
* Learn about automated build / test passing badges.
* Need to learn how to publish libraries in clojure (clojars.org?).
* Need docs.
* Need examples.
* Need to learn cljx.
* Run this in nodejs/v8?
* Figure out how to use this in clojure/JVM.
* Figure out how to serialize/deserialize a snapshot.
  (e.g, save a snapshot to a file.)
  This will be difficult due to lots of state stuck in closures.
  Probably have to define some onerous app limitations to allow for
  serialization/deserialization.

