# 0031 — A transfer in fewer writes

## Problem

A transfer's rate is set by the journal, not the JVM: lark spec 0108 measured it as "at about eight appends a transfer
the journal, not the JVM, set the rate", and spec 0016's profile found Postgres's backends waiting on `WALWrite`.
Today one transfer, with screening off, costs:

- **Seven appends.** On the transfer: `Requested`, `SourceDebited` and `DestinationCredited`. On the accounts: `Debited`,
  `Credited`, and a `LegsClosed` on each. Five of those are on the request's path, each waiting for its own commit
  before the next step is sent.
- **A recovery read.** A transfer's entity is new every time, but it still reads the journal for its id before it
  takes its first command.
- **A second debit.** A new saga is driven twice at once, by its start and by the `Nudge` it sends itself on starting
  (`Transfers.kt`, `onStart`). The account drops the second as a leg it has applied, so this costs a message and an
  ask, not an append.

Everyone who moves money pays this. At 400 transfers a second it is about 2,800 appends a second across two journal
databases on one 4-core host.

## Not doing

- The account's own write path. Batching (spec 0013, `batch = 64`) already shares one append between a busy account's
  commands.
- Screening's `Screened` event. It is the record of a decision and stays.
- Group commit's sizing and Postgres's settings: spec 0032.
- Dropping a durable step. Every money movement is still an event before the saga goes on.

## Shape

Four appends on the path instead of five, and two in all on the transfer instead of three:

| Step | Today | After |
|---|---|---|
| Start | `Requested` | `Requested` |
| Debit | `Debited` (account), `SourceDebited` (transfer) | `Debited` (account) |
| Credit | `Credited` (account), `DestinationCredited` (transfer) | the same |
| Close | `LegsClosed` × 2 | `LegsClosed` × 2, written with each account's next batch |

```kotlin
// The saga after a debit's answer: nothing written, the credit sent at once. A saga recovered between the two
// sends the debit again, and the account answers it as applied (spec 0030 keeps a refusal the same way).
is LegAnswered -> when (val event = moving.after(message.step, refusal, now())) {
    null -> none().then { ctx.drive(moving.answered(message.step)) }
    else -> persist(event).then { after -> ctx.drive(after) }
}
```

- **`SourceDebited` goes.** The debit's own `Debited` on the account is the durable record. A saga that recovers
  without it sends the debit again, and the account's `legs` answer it as applied without moving money. Only the
  in-memory state moves on.
- **A refused debit is still written** (`Rejected`), since it ends the saga.
- **`Close` stays a tell,** but the account writes `LegsClosed` with its next command's batch rather than as an append
  of its own, unless nothing follows within its `passivateAfter`.
- **A transfer drives once on start.** `Nudge` is sent only by a saga recovered unsettled, not by one being made.
- **A new transfer skips its recovery read** where lark lets an entity say its id is fresh (see Depends on).

## Why this shape

The saga writes what it needs to resume, and the account already writes what money moved. Writing that the debit
happened on both sides doubles the cost of the step. The alternative is to have the account drive the credit itself,
so a transfer is one entity's worth of writes. That is fewer appends again, but it moves the saga into the account
and loses spec 0001's single place that says where a transfer has got to. Recommended: the table above.

## Depends on

- **Skipping the recovery read** needs lark to start a persistent entity as empty when its caller knows the id is new.
  That is a lark spec, not yet written. Until it lands the read stays, and the other changes still apply.
- **Driving once on start** needs the saga's start hook to see the state it recovered, which lark's `onStart` does
  not pass. Until lark offers that, `Transfers.kt` wraps the persistent behaviour's start through `Behaviour`'s public
  constructor (`onRecovered`), which takes no batch: fine for the saga, which steps one message at a time.
- Everything else is the bank's own code.

## Stack

- [x] **`transfer-drives-once`**: `Nudge` only from a recovered, unsettled saga.
      Done when: a new transfer sends one `Debit`, shown by a test in spec 0028's in-memory runtime.
- [x] **`transfer-no-source-debited`**: the saga moves past a debit without writing it, and a recovered saga sends the
      debit again. `SourceDebited` is still read from old journals.
      Done when: the saga's property tests (spec 0028) pass with a crash between the debit and the credit, and the
      ledger is conserved.
- [ ] **`legs-closed-batched`**: an account writes `LegsClosed` with its next batch.
      Done when: `ApiSpec` and the chaos run still conserve the ledger, and the appends a transfer counts (from
      `bank.transfer.steps` and the journal's own count) drop to 4 on the path.
- [ ] **`fresh-entity`**: a new transfer starts without a journal read, once lark has it.
      Done when: a transfer makes no `select` on the journal, shown with the journal's query count.

## Acceptance

```bash
./gradlew build
docker compose -f deploy/docker/compose.yml run --rm -e SCENARIO=transfers -e RATE=400 load
```

Before and after on the same host: transfers' p99 and the rate at which they start failing.

## Open questions

1. **Old journals still hold `SourceDebited`.** Recommend reading it forever, and no longer writing it. No migration.
2. **Kafka's transfer events lose `SourceDebited`.** Does anything downstream read it? Recommend checking bank-checks'
   monitoring (spec 0018) first; the account's `Debited` carries the same fact.
3. **`LegsClosed` waits for the account's next batch.** Is a leg kept a while longer acceptable? Recommend yes: a leg
   kept is spec 0013's safe direction.
