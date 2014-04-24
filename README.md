# ago - snapshots for clojurescript core.async

## Motivations

The "ago" library is meant to help folks using (clojurescript)
core.async to build discrete event simulations.

For example, I was trying to use core.async to model and simulate
clients, servers and messages in a distributed system.

By using clojurescript core.async, I could run my simulations in
modern web browsers to have an easy to use GUI / visualization
environment.

But, a missing feature in core.async is that it is not clear how to
"rewind the world" back to a previous state in a simulation so that
one can replay events.  That is, I wanted to snapshot all the inflight
go-routines and channels and their inherent state, and then later
restore the simulated world to that previous snapshot.

The vision is that UI frameworks like Om allow for undo and redo
of app-state.  In this case the app-state would be the state
of a bunch of go-routines and channels.

The "ago" library, which is built on top of clojurescript core.async,
is meant to provide that snapshot and restore ability, so that one
can have "TiVo for your simulated model".

## How To Use

The "ago" library provides API's which wrap around the main API's of
core.async.  These ago API functions should be used instead of the
regular clojurescript core.async API functions if you want
snapshot/rewindability.

The ago library API's follow a naming/parameter
convention of having an "a" prefix in their function names and also
an extra first parameter of a "world handle".  For example...

* (go ...) becomes (ago world-handle ...)
* (chan) becomes (achan world-handle)
* (timeout delay) becomes (atimeout world-handle delay)

There's an API to create a world-handle, with associated user data...

* (make-ago-world opaque-user-data)

And, to snapshot a world...

* (ago-snapshot world-handle) => snapshot

And, to restore a previous snapshot...

* (ago-restore world-handle snapshot)

You should use regular clojurescript core.async API function (go,
chan, timeout) for go-routines that you don't want to snapshot (not
part of your simulation/model), such as GUI-related go-routines that
are handling button clicks or trying to rendering output.

## LICENSE

Eclipse Public License

## Building

    lein cljsbuild once

This was inobvious to me until Aaron Miller pointed it out to me,
to help during development...

    lein cljsbuild auto

## Underneath The Hood

This section might be interesting only to those folks who
delve into how core.async works or who want to understand
ago's limitations.

ClojureScript provides hooks in its core.async macros that transform
go blocks to SSA form, and the ago library utilizes those hooks to
interpose its own take/put/alts callbacks so that ago has access to
the state machine arrays of each "go-routine".  With those hooks the
ago library can then register the state machine arrays into the
world-handle.

A world-handle is a just an immutable/persistent associative map atom.

Also, instead of using clojurescript core.async's default buffer
implementation (a mutable RingBuffer), the ago library instead
requires that you use its immutable/persistent buffer implementation,
which are also registered into the world-handle.

A snapshot is then copying a world-handle and cloning any registered
state-machine-arrays.  And, buffer snapshotting comes "for free"
due to ago's immutable/persistent buffer re-implemenation.

A restore is then swapping a previous world-handle back into place,
and copying back any relevant state-machine-arrays.

Each world-handle also tracks a branching version vector, kinda like
git/DVCS branches, so that any inflight go-routines can detect that
"hey, I'm actually a go-routine for a branch that's no longer the
mainline, so I should die (and get GC'ed)".

The ago library also has its own persistent/immutable
re-implementation of the timer queue (instead of core.async's default
mutable skip-list implementation), again for easy snapshot'ability.

One issue with ago's approach is that it may be brittle, where changes
to clojurescript core.async's SSA transformations or
Channel/Buffer/Handler protocols might break ago.

## TODO

* Need to learn how to publishing libraries in clojure.
* Need tests.
* Need docs.
* Need examples.
* Need to learn cljx.
* Run this in nodejs/v8?
* Figure out how to use this in clojure.
* Figure out how to serialize/deserialize a snapshot.
