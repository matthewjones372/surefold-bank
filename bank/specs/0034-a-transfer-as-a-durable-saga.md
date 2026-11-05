# 0034 — a transfer as a durable saga

## Problem

A transfer is a hand-rolled persistent actor. It remembers which leg it is on, arms a timer when an ask is not
answered, and a sweeper tells it `Nudge` when its node has gone. Surefold 0.18.1 ships the same idea as a library:
`durableSaga` journals each step as it begins and ends, replays a run that died, and a cluster waker resumes one
whose node left. The bank's actor is the thing that library replaces.

## Not doing

- Accounts stay the bank's own persistent actors. They are not `keyed` entities, and a transfer does not use
  `entityStep`: a keyed event wraps the domain event, which the account projections do not read.
- The published transfer stream is unchanged. A completed transfer is still `Requested` then `DestinationCredited`.
  There is still no `SourceDebited`. The account's `Debited` is the durable record of the debit (spec 0031).
- Pelican stays `1.2.0`. The bank's own event package stays `0.1.0-SNAPSHOT`.
- A step that has ended is not sent again. Spec 0031's recovery sent a debit again after a crash and let the account
  answer it as applied. The saga's step record replaces that second send. A step that began and did not end is asked
  again, and the account still applies it once (spec 0013).

## Shape

The saga's kind is `bank.transfer`, not `transfer`, so its step journal is not the stream the projections follow.
The transfer events are still appended to `Kinds.TRANSFER`.

```kotlin
val saga = durableSaga("bank.transfer", Inputs, Halts, Views) { input ->
    step("request", Longs, { request(input) }, check = { requestedOrNot() })
    step("debit", Units, { debit(input) }, check = { debitSettled() }, undo = { _, _, _ -> refund(input) })
    step("credit", Units, { credit(input) }, check = { creditSettled() })
    view()
}
val runs = saga.on(cluster)
runs.start(id, input)
runs.await(id, transferWait)
```

`Sagas.start` does not compare a second input with the first, so the id is claimed in an append-only record before
`start`. The same details are the same transfer. Different details are `TransferIdReused` and the saga is not started.
Whether this transfer is screened is part of that input, so a config change cannot make a replay take a different step.

A declared failure is how the debit's undo runs. The HTTP API still answers a view: the events fold to rejected or
refunded once the undo has finished. A credit the destination refuses fails the saga, and the undo refunds the source
until the account takes the money back.

## Why this shape

The step journal is the recovery record, and the waker replaces a nudge of an actor that only existed in memory.
Plain `step` talks to the accounts the bank already has. `entityStep` would require those accounts to be keyed, and
their projections would stop.

The in-memory specs no longer drive the saga with Surefold's message faults. A lost ask is retried inside the step,
on the wall clock, and the account's idempotency is what keeps a retry from moving the money twice. The properties
in spec 0028 are the same — conservation, settlement, one application of each leg, the daily limit — over seeds of
transfers rather than seeds of dropped messages.

## Depends on

Surefold core spec 0299, shipped in `dev.surefold:surefold-cluster-saga:0.18.1`. The integration modules (Kafka,
config, Liquibase, observability, the kotlinx remote codec) are their `0.18.0` release. Anything else they request
at `0.18.0` is core, and the build resolves it as `0.18.1`.

## Stack

- [x] **`surefold-durable-saga`** — the coordinates, the saga, and the specs that stated the old actor.
      Done when: a transfer completes, rejects and refunds in memory; the same id twice is one transfer; a different
      one is refused; screening decides before the debit; 1,000 seeds conserve the money.
