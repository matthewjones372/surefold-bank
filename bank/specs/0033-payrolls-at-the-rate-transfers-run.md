# 0033 — Payrolls at the rate transfers run

## Problem

A payroll of 100 credits fails at 20 a second on the benchmark host, which is 2,000 credits a second. That is about
the journal rate that 400 transfers a second already reach. Payroll fails first for reasons of its own, not the
journal's:

- **One sender per node.** `entities.accounts.reliable("payroll")` makes one producer actor per node. Every credit of
  every payroll on that node goes through its mailbox (1,024) and its 1,000-credit window (`keep`).
- **The producer copies its state on every message.** Surefold's `Producer` keeps an immutable `Map` of outboxes, rebuilt
  with `queues + (to to Outbox(...))` on every `KeepCommand` and every `Confirmed`. Entries are never removed, so with
  10,000 accounts each message copies up to 10,000 entries. That is about 4,000 messages a second on one thread.
- **A payroll's sends are sequential.** `ReliablePayrolls.send` hands over its 100 credits one at a time on the
  request's thread. Each waits up to 5 s for room in the window, and the first that cannot get it makes the whole
  request a 503.
- **A full window holds at 1,000 / (round trip).** At 2,000 credits a second the window needs each credit confirmed in
  under 0.5 s, across a remote append and a remote confirm.
- **Late confirms are resent.** Every 2 s the producer resends each queue's first unconfirmed credit, so a slow
  confirm adds traffic just when the system is busiest.

## Not doing

- A durable producer (surefold spec 0085). Payroll's producer is in memory, and a payroll is safe to send again by its id.
- The account's write path. A busy account already batches its credits (spec 0013).
- Answering 202 only once each credit is confirmed. Payroll answers once all its credits are handed over, as it does
  today.

## Shape

```kotlin
// Several senders on a node, each with its own window, chosen by the account: one account's credits still go in
// order through one of them.
val payroll = (0 until settings.payroll.senders).map { entities.accounts.reliable("payroll-$it", keep = settings.payroll.window) }
fun senderFor(account: AccountId) = payroll[Math.floorMod(account.value.hashCode(), payroll.size)]
```

- **`payroll.senders`** (default 4) and **`payroll.window`** (default 1,000) are config, read by
  `ReliablePayrolls`.
- **A payroll's credits are grouped by sender** and handed over in one call per sender, where surefold has a call that
  takes several (see Depends on). Until then they are handed over one at a time, as today, but spread over the
  senders.
- **`Busy` names how many were sent,** as it does now, so the payroll is still safe to send again.
- **The load's payroll scenario reports credits a second,** not only requests, so its numbers compare with
  transfers'.

## Why this shape

Several senders take the single thread and the single window off the path without changing what a credit promises.
One account still goes through one sender, so its credits keep their order. The alternative is one producer per
payroll. That gives the most parallelism, but it costs an actor and a resend timer for each request and loses the
shared window that keeps a node from being flooded. Recommended: a fixed number of senders.

## Depends on

Two changes in surefold's `Producer` (`deps/surefold/actor/surefold-actor/.../Producer.kt`), as a surefold spec not yet written:

- **A mutable map of outboxes, inside the actor,** with an entry removed once its queue is empty, so a message costs
  O(1) and not O(accounts).
- **`sendAll`,** handing over several commands for one window permit check and one tell.

Until then, the senders spread the cost over several threads, and each copies a smaller map.

## Stack

- [x] **`payroll-senders`**: `payroll.senders` and `payroll.window` in config, and the senders chosen by account.
      Done when: a test sends one payroll over 4 senders and every credit is confirmed once, and the payroll scenario
      at 20/s fails fewer than 1 in 1,000.
- [x] **`payroll-load-reports-credits`**: the load reports credits a second for payroll.
      Done when: the payroll run's summary has a credits-a-second line.
- [ ] **`payroll-send-all`**: credits handed over in one call per sender, once surefold has `sendAll`.
      Done when: a payroll of 100 makes at most `senders` tells.

## Acceptance

```bash
./gradlew build
docker compose -f deploy/docker/compose.yml run --rm -e SCENARIO=payroll -e RATE=20 load
```

## Open questions

1. **How many senders?** Recommend 4, one per core of the benchmark host, then the ramp from spec 0032.
2. **Should the window grow with the senders?** Recommend 1,000 each, so 4 senders hold 4,000 in flight. That is under
   what a region's mailbox takes.
3. **Should `resendAfter` grow under load?** Recommend leaving surefold's 2 s. A busy system is better served by the
   fixes above than by resending later.
