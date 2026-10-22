# 0032 — The journal's commits sized, and the writes around them cheaper

## Problem

Once the transfer writes less (spec 0031), the commits themselves set the rate. Three things around them are fixed
where they should be measured, or cost more than they need to:

- **Group commit's size is lark's default.** `Wiring.kt` builds `GroupCommit()`, which means up to 64 appends a group
  and 2 committers per journal database per node: 12 commits in flight across the cluster. Lark spec 0108 chose those
  numbers on its own load. The bank's config cannot change them.
- **Derived writes wait for their commits as journal writes do.** db-0 holds the read models, their offsets, the
  access log and grants beside the journal. A read-model batch, which is rebuilt from the journal if it is lost, waits
  for the same durable flush as an event.
- **One row every partition writes.** Each account-projection batch upserts `ledger_totals` for its currency
  (`ReadModels.kt`, `x = x + ?`), from up to 8 partitions, so the batches queue on that row's lock.
- **One commit per staff request.** A staff look is an unbatched insert into `access_log`, then an update when it is
  published to Kafka (`AccessLogging.kt`). The load avoids this now by calling as a customer, but a real ops page does
  not.

Postgres itself runs untuned in `deploy/docker/compose.yml`: only `max_connections` and `shared_buffers` are set.

## Not doing

- Turning durability off for the journal. Every event is still committed with `synchronous_commit = on`.
- A third journal database. Spec 0004 found two on one host gave no speed-up.
- CloudNativePG's settings (spec 0008). This spec's Postgres flags are for the Docker stand-in, and say so.

## Shape

```hocon
journal {
  groupCommit = true
  # Lark spec 0108's defaults. Raised only with a ramp that shows it helps.
  maxAppends = 64
  maxAppends = ${?JOURNAL_GROUP_MAX_APPENDS}
  committers = 2
  committers = ${?JOURNAL_GROUP_COMMITTERS}
}
```

```kotlin
// A derived write: lost in a crash, the projection replays it from its offset, which commits in the same transaction.
connection.createStatement().use { it.execute("set local synchronous_commit = off") }
```

- **Group commit configurable:** `GroupCommit(settings.maxAppends, settings.committers)`.
- **Read-model and offset transactions use `synchronous_commit = off`,** with `set local`, so the journal's
  transactions on the same database are untouched. The offset commits with the rows it covers, so a lost commit loses
  both and the projection replays them.
- **`ledger_totals` keyed by currency and partition.** The ledger endpoint sums the partitions' rows when it reads.
- **The access log written in batches** by one writer per node, every 100 ms or 500 rows, whichever comes first. A
  look still never delays its answer.
- **A ramp, not a guess.** `scripts/ramp.sh` raises the transfer rate until 1 in 1,000 fails, for each setting, and
  writes the knee to `build/reports/ramp.txt`.

## Why this shape

Each change takes waiting out of a write that does not need it, and leaves every journal append as durable as today.
The alternative is `synchronous_commit = off` for the whole of db-0. That is simpler, but it would put the journal's
partition there at risk of losing a committed event, which is the one thing the bank must not do. Recommended: the
per-transaction setting.

## Depends on

Nothing. `GroupCommit` already takes both numbers.

## Stack

- [x] **`group-commit-settings`**: `maxAppends` and `committers` in config and the compose file.
      Done when: a node started with `JOURNAL_GROUP_COMMITTERS=4` logs it, and the defaults are unchanged.
- [x] **`derived-writes-async`**: `set local synchronous_commit = off` in the read-model and offset transactions.
      Done when: `ReadModelsSpec` passes, and a test kills Postgres mid-projection and the ledger still conserves after
      a restart.
- [x] **`ledger-totals-per-partition`**: a migration adds `partition` to the key; reads sum it.
      Done when: the ledger endpoint answers the same as before on `ApiSpec`'s data.
- [ ] **`access-log-batched`**: one writer per node, batched.
      Done when: `AccessLogSpec` passes, and a staff page's 1,000 requests make at most 20 inserts.
- [ ] **`ramp`**: the ramp script, run once for the defaults and once for each changed setting.
      Done when: the README's load table gives the knee for each.

## Acceptance

```bash
./gradlew build
scripts/ramp.sh transfers
```

## Open questions

1. **Should Postgres's own `commit_delay` be tried too?** Recommend no: the bank's group commit already gathers the
   appends, and `commit_delay` adds a wait to a lone append.
2. **Could the access log commit asynchronously too?** Spec 0021 says every staff look is recorded, so a row lost in
   a crash breaks it. Recommend no: the access log stays durable and is only batched.
3. **What does the ramp stop at?** Recommend 1 in 1,000 failing, or p99 over 1 s, whichever comes first.
