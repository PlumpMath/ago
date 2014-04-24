# ago - replayable snapshots for clojurescript core.async

The "ago" library is meant to help folks trying to build discrete
event simulations using (clojurescript) core.async.

In my case, I was trying to use the "go-routines" and channels
(queues) in core.async to simulate clients and servers in a
distributed system.  I also used go-routines to simulate (perhaps
flakey) networks and (perhaps slow or error-ful) fileystems.

By using clojurescript core.async, I could also have my
models/simulations running in modern web browsers (i.e., easy to use
GUI or visualization environment).

But, a missing feature in core.async is that it is hard to "rewind the
world" or the go back to a previous state in a simulation and replay
events.  That is, it is hard to snapshot all the inflight go-routines
and channels and their inherent state, and then later restore some
previous snapshot.

The "ago" library, which is built on top of clojurescript core.async,
is meant to provide that snapshot'ing and restore facility, so that
once can have "TiVo for your simulated model".

## How To Use

The "ago" library provides API's which wrap around key API's of
core.async.  The "ago" API's follow a naming/parameter convention of
having an "a" prefix in their function names and also take an extra
first parameter of a world handle.

* (go ...) becomes (ago world ...)
* (chan) becomes (achan world)
* (timeout delay) becomes (atimeout world delay)

There are API's to create a world handle...

* (make-ago-world opaque-user-data)

And, to snapshot a world...

* (ago-snapshot world) => snapshot

And, to restore a previous snapshot...

* (ago-restore world snapshot)

## Building

    lein cljsbuild auto ago
