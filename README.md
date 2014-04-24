# ago - replayable snapshots for clojurescript core.async

The "ago" library is designed to help those attempting to build
discrete event simulations and models using (clojurescript)
core.async.

The core.async library of clojure provides useful features so that one
can build discrete event simulations in a straightforward way.
Features like core.async's asynchronous programming model (independent
tasks or "go-routines") and its channels (queues) are the key, useful
building blocks for model builders.

For example, one could use core.async's go-routines to represent
independent clients and servers in a distributed system and other
go-routines to represent (perhaps flaky) network links and (perhaps
slow or error-ful) filesystems.

With clojurescript core.async, one can then also have the
models/simulations running in modern web browsers (i.e., easy to use
GUI or visualization platform).

But, a missing feature in core.async is that it is hard to "rewind the
world" or the go back to a previous state in a simulation and replay
events.  That is, it is hard to snapshot all the inflight go-routines
and channels and their inherent state, and then later restore some
previous snapshot.

The "ago" library, which is built on top of clojurescript core.async,
is meant to provide that snapshot'ing and restore facility, so that
once can have "TiVo for your simulated model".

## Building

    lein cljsbuild auto ago
