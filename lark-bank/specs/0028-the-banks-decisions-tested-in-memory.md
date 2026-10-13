# 0028 — The bank's decisions, tested in memory

## Problem

Whether money moves once is decided by the account and transfer actors in `app`: `Account.decide` is pure, but the
saga's handling of its messages lives in `app/Transfers.kt` (whether an account's answer is stale, whether a
timed-out leg still matters, whether a second start is a retry, a reused id or a wait). The only tests of it start
clusters of nodes on Postgres: 23 of `app`'s 26 specs do, and a lark-bank check spends about five minutes in them.

That cost has a second price. Mutation testing reruns a module's whole suite before it mutates anything, so mutating
`app` cost eight minutes a check, and Pitest now runs in `domain` only. The saga, the bank's most dangerous code, is no
longer mutation-tested.

## Not doing

- **Changing what the saga or an account does,** or the journal's format. This adds tests and moves no behaviour.
- **Rewriting the saga as a pure decider.** Lark runs the real behaviours in memory (below), so the saga is tested as it
  ships. Pulling its decisions into `domain` stays an option if the in-memory specs show it's still hard to test.
- **The read models** (`ReadModels.kt`, `Projections.kt`): mostly SQL, and a spec of their own.
- **A simulator of the bank's own.** What Lark lacks is added to Lark (Depends on).

## Shape

**The real behaviours, in memory.** Lark's `testActors` (lark-actor) steps actors on the test's thread with an
in-memory journal and snapshots, timers in test time and a `TestClock`. The bank's `account(...)` and `transfer(...)`
run there unchanged: the saga reaches accounts through `AccountsById`, which a test points at test actors, and
screening, which asks on a virtual thread, is a port a test answers synchronously.

```kotlin
// app/src/test: no cluster, no Postgres, no threads
val sim = testActors(order = Ordering.seeded(42), faults = Faults(drop = 0.1, duplicate = 0.1))  // Lark spec 0128
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

Running the shipped behaviours under Lark's deterministic runtime tests the saga as it is, not a refactor of it, and
`AccountsById` already makes the seam. Seeded faults over properties beat examples here because the saga's bugs are
orderings (a retry's twin arriving second, a crash between a debit and its credit) that examples rarely reach. The
alternative, a pure decider with a bank-built simulator, means rewriting the saga first and duplicating what Lark
should own.

## Depends on

**Lark spec 0128, a test runtime that can be unkind** (committed in Lark): `testActors` gains a seeded delivery order,
message drop/duplicate/delay, and crashing an actor so it recovers from its journal. Until it lands, the in-memory
specs run Lark's first-in-first-out order with a bank-side `AccountsById` wrapper that drops and repeats messages,
and crash-recovery is left to the cluster specs.

## Stack

- [x] **`in-memory-bank`** (#32) — `InMemoryBank` over `testActors` in `app`'s tests, and example specs moved from cluster
      specs for the saga's decisions. Done when: the saga's behaviour specs run without a cluster, in under 5 seconds.
- [x] **`bank-properties`** (after Lark 0128) — seeded property specs with faults and crashes. Done when: 1,000 seeds
      run in under 10 seconds, conserving money with each leg applied once.
- [ ] **`mutate-app-fast`** — Pitest in `app` with target tests limited to the in-memory specs. Done when: a check
      touching `Transfers.kt` mutates it in under a minute and kills at least 80% of its mutants.
- [ ] **`trim-cluster-specs`** — cluster specs keep only what needs a cluster. Done when: lark-bank's unit run is at
      most half today's five minutes, and no behaviour lost its last test.

## Acceptance

```bash
./gradlew :app:test --tests 'bank.app.memory.*' :app:pitest   # in memory, mutation-tested
./gradlew build                                              # everything, cluster specs included
```

## Open questions

- Seeds per check? Recommended: 200 on every check, 10,000 nightly on `main`, failures printing their seed.
- Tag the cluster specs (JUnit `@Tag("cluster")`) so Pitest and Gauntlet's test selection (Gauntlet spec 0013) can
  leave them out? Recommended: yes, in `in-memory-bank`.
- The daily limit's properties in the first property PR? Recommended: yes, it's the first rule that depends on retries.
