# 0030 — A leg decided once

## Problem

An account applies each of a transfer's legs once (spec 0013), but it remembers only the legs it applied. A leg it
refused is forgotten, so the same leg arriving again is decided again, and can be decided differently.

The saga sends a leg again whenever its answer is slow. So:

1. cy holds 20.00. Transfer t-3 asks to debit 38.63, and cy refuses it for want of money. The refusal is slow.
2. The saga's leg timer fires, and it sends the same debit again.
3. A deposit of 50.00 reaches cy.
4. The second debit arrives, and is applied: cy is debited 38.63.
5. The refusal arrives first, and the saga records t-3 as Rejected. The later success is an answer to a step the saga
   has moved past, and is ignored.

t-3 is Rejected, and 38.63 has left cy for nowhere. A credit has the mirror case: one refused because its account was
not open, retried after someone opens that account, credits money the saga has already refunded to its source.

Spec 0028's property specs found this (seed 22). Nothing here needs a fault the cluster does not have: a slow answer,
a retry, and a deposit in between.

## Not doing

- **Changing when a leg is refused.** The same rules refuse it; the account only remembers that it did.
- **Remembering refused deposits and withdrawals.** Only a transfer's legs are retried by the bank itself; a caller
  sending a refused withdrawal again asks again, and gets today's answer.
- **Changing the saga.** It already ignores an answer to a step it has moved past; it only needs every answer to a
  leg to be the same.

## Shape

- **An account remembers what it refused.** When a debit, credit or refund is refused, the account writes
  `LegRefused(transfer, leg, reason)` before it answers. `Account.Open.refused` maps the leg's reference
  (`debit:t-3`) to the reason. A leg whose reference is there is refused again with `AlreadyRefused(reason)`, whose
  message is the first refusal's, and nothing is written.
- **An unopened account remembers too.** A leg refused because the account does not exist is written to that
  account's journal, which moves it to `Account.Refusing(refused)`: not opened, with refusals to keep. Opening it
  carries them into the open account, so a credit refused before the account existed stays refused after.
- **A close forgets them**, as it forgets applied legs: `LegsClosed(t-3)` removes `t-3`'s refusals with its legs. A
  refusing account with none left is unopened again. The saga sends its closes once it has settled, after every leg
  it sent on the same way, so no retry of a leg can arrive after its close.
- An account unavailable (a timeout, a full mailbox) has decided nothing, and writes nothing.
- `LegRefused` moves no money. The statements and the ledger skip it; Kafka carries it, and bank-checks skips it.

## Why this shape

A leg is the transfer's question to an account, and its answer has to be final for the saga's retries to be safe:
applied-once was half of that, decided-once is the other half. Remembering the refusal in the account keeps the
decision where the money is, bounded by transfers in flight, as `legs` is. The alternative, a rejected saga telling
its source to give back any debit it took, lets money leave and come back, shows a debit on a statement for a transfer
that never happened, and still leaves the credit's mirror case open.

## Depends on

- **Spec 0013**, whose `legs` and closes this extends.
- **Spec 0028**, whose property specs check it.

## Stack

- [x] **`legs-refused`** — `LegRefused`, `AlreadyRefused`, `Account.Refusing` and `Account.Open.refused`, the actor
      writing a refusal before answering it, the wire and journal shapes, the published event, the read models and
      bank-checks skipping it.
      Done when: a debit refused and sent again after a deposit is refused again with the first reason, a credit
      refused by an unopened account is refused again after it opens, and spec 0028's property specs conserve money
      over 1,000 seeds.

## Acceptance

```bash
./gradlew :domain:test :protocol:test :app:test --tests 'bank.app.memory.*'
./gradlew build
```

## Settled while building

- **A refusal is kept as its message,** not the error's fields: the saga records only the message, and the second
  answer only needs to say the same.
- `RefusedLegSpec` replays seed 22's case without random faults; spec 0028's properties pass 1,000 seeds in about 7
  seconds, and 10,000 in 21.

## Open questions

None.
