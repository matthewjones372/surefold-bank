# 0028 — The bank's decisions, tested in memory

## Problem

Whether money moves once is decided by the account and transfer actors in `app`: `Account.decide` is pure, but the
saga's handling of its messages lives in `app/Transfers.kt` (whether an account's answer is stale, whether a
timed-out leg still matters, whether a second start is a retry, a reused id or a wait). The only tests of it start
clusters of nodes on Postgres: 23 of `app`'s 26 specs do, and a surefold-bank check spends about five minutes in them.

That cost has a second price. Mutation testing reruns a module's whole suite before it mutates anything, so mutating
`app` cost eight minutes a check, and Pitest now runs in `domain` only. The saga, the bank's most dangerous code, is no
longer mutation-tested.

## Not doing

- **Changing what the saga or an account does,** or the journal's format. This adds tests and moves no behaviour.
- **Rewriting the saga as a pure decider.** Surefold runs the real behaviours in memory (below), so the saga is tested as it
  ships. Pulling its decisions into `domain` stays an option if the in-memory specs show it's still hard to test.
- **The read models** (`ReadModels.kt`, `Projections.kt`): mostly SQL, and a spec of their own.
- **A simulator of the bank's own.** What Surefold lacks is added to Surefold (Depends on).

## Shape

**The real behaviours, in memory.** Surefold's `testActors` (surefold-actor) steps actors on the test's thread with an
in-memory journal and snapshots, timers in test time and a `TestClock`. The bank's `account(...)` and `transfer(...)`
run there unchanged: the saga reaches accounts through `AccountsById`, which a test points at test actors, and
screening, which asks on a virtual thread, is a port a test answers synchronously.

```kotlin
// app/src/test: no cluster, no Postgres, no threads
val sim = testActors(order = Ordering.seeded(42), faults = Faults(drop = 0.1, duplicate = 0.1))  // Surefold spec 0128
val bank = InMemoryBank(sim)          // the Bank port over account and transfer test actors
bank.open(a, "ada", gbp(100)); bank.open(b, "bob", gbp(0))
repeat(50) { bank.transfer(TransferId("t-$it"), a, b, gbp(1)) }
sim.settle()                          // deliver, retry on timers, recover crashed actors from their journals
bank.total() shouldBe gbp(100)        // conserved, whatever the schedule did
```

**Properties over seeds.** Specs check, across many seeded schedules: money conserved, every transfer settles, each
leg applied once (spec 0013), a refund only after a refused credit, the daily limit (spec 0027) held across retries.
A failing seed is printed and replays exactly.

**Mutation in `app` again, on the fast specs.** Pitest is applied to `app` with its target tests limited to the
in-memory specs, so its coverage run takes seconds, and the saga is mutation-tested per change alongside `domain`.

**The cluster specs, trimmed.** A cluster spec keeps what only a cluster shows: sharding finds an entity, a node's
death is recovered from on another node, the journal on Postgres, HTTP. Behaviour now covered in memory is dropped
from it, each removal named with the property that covers it.

## Why this shape

Running the shipped behaviours under Surefold's deterministic runtime tests the saga as it is, not a refactor of it, and
`AccountsById` already makes the seam. Seeded faults over properties beat examples here because the saga's bugs are
orderings (a retry's twin arriving second, a crash between a debit and its credit) that examples rarely reach. The
alternative, a pure decider with a bank-built simulator, means rewriting the saga first and duplicating what Surefold
should own.

## Depends on

**Surefold spec 0128, a test runtime that can be unkind** (committed in Surefold): `testActors` gains a seeded delivery order,
message drop/duplicate/delay, and crashing an actor so it recovers from its journal. Until it lands, the in-memory
specs run Surefold's first-in-first-out order with a bank-side `AccountsById` wrapper that drops and repeats messages,
and crash-recovery is left to the cluster specs.

## Stack

- [x] **`in-memory-bank`** (#32) — `InMemoryBank` over `testActors` in `app`'s tests, and example specs moved from cluster
      specs for the saga's decisions. Done when: the saga's behaviour specs run without a cluster, in under 5 seconds.
- [x] **`bank-properties`** (after Surefold 0128) — seeded property specs with faults and crashes. Done when: 1,000 seeds
      run in under 10 seconds, conserving money with each leg applied once.
- [ ] **`mutate-app-fast`** — Pitest in `app` with target tests limited to the in-memory specs. Done when: a check
      touching `Transfers.kt` mutates it in under a minute and kills at least 80% of its mutants.
      The specs are in: screening, the account actor, its bulk credits and the saga's counters, in memory. Pitest
      with them targets `TransfersKt` and `AccountsKt`: 39 seconds, 81% killed (72 of 89). The change to
      `build.gradle.kts` that turns it on is the owner's, being build configuration; the entry is ticked once it is
      in. What survives returns null from a lambda whose value is unused, or needs a full mailbox.
- [ ] **`trim-cluster-specs`** — cluster specs keep only what needs a cluster. Done when: surefold-bank's unit run is at
      most half today's five minutes, and no behaviour lost its last test.
      Every cluster case is sorted below. Three screening cases are gone, about 4 of `app`'s 274 seconds on CI; no
      other case is a saga decision alone. What is left of the five minutes is starting nodes, containers and
      Chromium for what only they show, one class after another: halving it means running the classes in parallel
      or starting nodes faster, which is build configuration and test setup, the owner's.

## Acceptance

```bash
./gradlew :app:test --tests 'bank.app.memory.*' :app:pitest   # in memory, mutation-tested
./gradlew build                                              # everything, cluster specs included
```

## Settled while building

- **Nine in-memory specs had stopped running.** `inMemory` became generic with `bank-properties`, so a spec written
  `= inMemory { ... }` returned its last value, and JUnit skips a `@Test` that returns one, saying only a warning.
  `inMemory` returns `Unit` again, `inMemoryOf` returns a value, and `app`'s `junit-platform.properties` makes such a
  warning fail the run.
- **The property spec is left out of mutation.** It runs in the build; under Pitest it would run again for every
  mutant, and the example specs kill the same mutants without it.

## The cluster specs, case by case

`app`'s specs outside `memory`, on CI's run of `main` before the trim: 55 cases in 28 classes, 274 seconds, nearly
all of it in the classes that start nodes. Each case is kept for what only a cluster shows, or is gone with the
specs that now hold its behaviour.

**Gone, held in memory** (each removal's commit names the same):

| Cluster case (`bank.app.ScreeningSpec`) | Now held by |
|---|---|
| an approved transfer moves, and a declined one is rejected with the rule as its reason and moves nothing | `memory.ScreeningSpec`: an approved transfer is screened once, then debited and credited; a declined transfer is rejected with the rule that declined it, and nothing moves. `ScreeningSpec`: the check's approval and its decline are read with the rule, version and evidence it gave |
| a check that does not answer in time leaves it to the bank's policy | `memory.ScreeningSpec`: a check slower than the timeout is decided by the policy, and its late answer changes nothing. `ScreeningSpec`: a check that is down, or slower than the timeout, gives no decision |
| approving when unanswered, a transfer moves though the check is down | `memory.ScreeningSpec`: a check with no decision is decided at once by the bank's policy. `ScreeningSpec`: a check that is down, or slower than the timeout, gives no decision |

The two `ScreeningSpec` cases named call `PelicanScreener` against the stub check with no node; screening's
settings reach a node through the cases kept, which run with it on.

**Kept, needing a cluster:**

- Sharding, journals and nodes: `ConservationSpec` (three nodes, two journal databases; the money properties also
  hold in `BankPropertiesSpec`, without sharding), `LeavingSpec` (a node leaves mid-run), `SweeperSpec` (a saga only
  the journal knows of, found through the read models), `ScreeningSpec`'s saga stopped mid-screening and screening
  again on a new node, `ReadModelsSpec` (the read models' SQL on Postgres), `DbRolesSpec` (row-level security).
- HTTP and pages: `ApiSpec` (its retries, id reuse and cross-currency refund are `SecondStartSpec`'s and
  `SettlingSpec`'s decisions, kept for the contract's errors, currencies and metrics), `DailyLimitApiSpec` (its
  transfer half is `TransferDailyLimitSpec`'s, kept for `DailyLimitExceeded` and the opening limit over HTTP),
  `CallerTableSpec`, `PagesSpec`, `AccountPageSpec`, `TransferPageSpec`.
- Who may see what: `AccessAuditSpec`, `AccessLogSpec`, `ActAsSpec`, `GrantsSpec`, `ShadowSpec`.
- Publishing, ops, logs and traces: `PublishingSpec`, `OpsSpec`, `LogJsonSpec`, `ExemplarsSpec`, `TraceActorsSpec`,
  `TraceEventsSpec`, `TraceExportSpec`, `ScreeningSpec`'s trace to the check.

**Kept, starting no cluster** (under a second each): `ShardedBankSpec`, `LegRefusedReadSpec`, `LevelFileSpec`,
`TraceCallsSpec`, `OpsSpec`'s p99, and `ScreeningSpec`'s three cases on `PelicanScreener` alone.

## Open questions

- Seeds per check? Recommended: 200 on every check, 10,000 nightly on `main`, failures printing their seed.
- Tag the cluster specs (JUnit `@Tag("cluster")`) so Pitest and Gauntlet's test selection (Gauntlet spec 0013) can
  leave them out? Recommended: yes, in `in-memory-bank`.
- The daily limit's properties in the first property PR? Recommended: yes, it's the first rule that depends on retries.
