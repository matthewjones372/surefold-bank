# 0035 — shared memory, and every spec as a suite

## Problem

The transfer's direct path, the owner and party maps, the token cache, the ops snapshots and the policy file all
coordinate with JDK maps, locks and atomics. Surefold already has the cells those are: `Shared` for a value several
callers update, `Promise` for one caller waiting on another, and `Cache` for a lookup that is worth remembering.
The specs are JUnit tests, so they run one after another, and each wait is a sleep. A suite runs its tests together.
A wait is `surefold-test`'s `eventually`, which tries again until the condition holds. Failures are exceptions, so a
caller has to catch them. Surefold's `Either` already carries a failure, and `Either.catch` is how a block that
throws becomes one.

## Not doing

- The Kafka producer's future stays a `CompletableFuture`, because that is what the client returns. The bank's
  publisher answers a `Promise`, and a stage is built from it only where a stream's `mapAsync` asks for one.
- Pelican's filter still completes the future its `attempt` returned, so a latency exemplar keeps the request's trace.
- The access log's batch queue, the load test's threads and the issuer stay as they are. They are not the bank's
  memory of an account, a token or a transfer. The load's wait for the books still uses `Either.catch`.
- The generated screening client still throws. It is generated, and its caller turns that into `NoDecision`.
- A codec's `read` still throws `WireException`. `MessageCodec` returns the message, so an unreadable frame has
  nowhere else to go. The same is true of a stream that must start again: `restartOnDefect` restarts on a thrown
  defect, and a declared `fail` passes through, so a projection that did not commit and a publish that did not
  land still leave as a thrown defect, never from inside `getOrElse`.
- Pelican's ops stream and its authenticator still use the exception those interfaces throw. `null` from an
  authenticator is anonymous, so a refused token is still `Unauthenticated`.
- An interrupt is not an `Either`. `Either.catch` lets it through.
- A transfer id is not a `SingleFlight`. A second call under that id still runs, so a different transfer is refused
  instead of being handed the first caller's answer.

## Shape

A direct transfer takes a promise for its id and waits for the one already there, then runs. It completes its own
promise when it finishes, including when it is interrupted, so the next caller is not left waiting.

An owner, a transfer's two accounts and a request's head are a `Cache`. The event that names them is planted in a
`Shared` map so the next read does not ask again; a miss still asks. A token is a `Cache` until its `exp`. A refused
token and a token with no expiry are not kept. The ops page reads a `Shared` map of the latest snapshot per node.
`policies.yaml` is a `Shared` value, reread when the file's modified time changes.

Every spec is a `Suite`. A test that used to construct its own fixture still does, once per test. Suites run several
at a time (`surefold.junit.suites.limit`), and the tests inside a suite run together. A suite that starts a node, a
database or the process logger is `isolated`, so it has the JVM to itself: those share one Postgres, stdout and the
logger. A check is recorded with
`holds`, so a spec that asserts nothing fails. A wait polls with `eventually` until what it is waiting for is true.
A pause that has to elapse on the wall clock while the suite's clock is fixed waits there; a pause on the suite's
own clock is `clock.sleep`.

A failure the bank handles is an `Either`. `Either.catch` takes whatever a block throws, apart from an interrupt
and the other fatal errors, and `catchOrThrow` takes one type and lets the rest through. The value comes back with
`fold`, `map` or `flatMap`. `getOrElse` supplies a value, and never throws. OpenFGA answers `Either`: a store that
is not there, a check it could not make, and a write that did not land are a `Left`. A Kafka consumer that gets one
does not commit, waits, and is built again. A direct transfer's saga answers `Stuck`, `Unsettled`, `Diverged` or
`Overtaken` as `Unavailable`, and the gate around that call is released with `resourceScope` when the call ends.

## Depends on

- Surefold 0246 (cache), 0247 (`Shared`, `Promise`), 0253 (the JUnit engine) and `surefold-test`'s `eventually`,
  released as `surefold-cache`, `surefold-junit` and `surefold-test` 0.18.1.
