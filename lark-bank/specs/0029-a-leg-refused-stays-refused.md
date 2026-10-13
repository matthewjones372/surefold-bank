# 0029 — A leg refused stays refused

## Problem

An account remembers a leg it applied (spec 0013) but not one it refused, so a retry of a refused leg is decided
afresh. A saga retries a leg whose answer is late, so it can act on a refusal while a retry is still on its way:

1. Debit `t-4` reaches `cy`, which has nothing, and is refused; the answer is slow, and the leg timer fires.
2. The saga sends the debit again, and again; the second is refused, and the saga writes `Rejected`.
3. `cy` is credited by another transfer, and the third debit arrives: it is applied.
4. Its answer reaches a saga that has settled, which ignores it. The money has left `cy` and gone nowhere.

A credit has the same hole the other way round: refused, then refunded by the saga, then applied by a late retry,
and the money is paid out twice. Bank spec 0028's property specs find the debit case in 64 of 1,000 seeded
schedules with late messages; on a node it needs only an answer slower than the leg timeout.

## Not doing

- **The saga.** It already ignores answers to steps it has moved past; with this, there is never a late success.
- **Withdrawals and deposits.** A caller's retried command after a refusal is a new decision on purpose: a refused
  withdrawal may succeed once money arrives. Only transfer legs, which a saga retries for one decision, change.
- **What `Close` keeps.** A refused leg is closed with the rest of its transfer's legs, as applied ones are.

## Shape

A refused debit or credit writes an event, and the account answers every copy of that leg with the same refusal.

```kotlin
data class LegRefused(val transfer: TransferId, val leg: Leg, val error: AccountError, override val atMillis: Long) :
    AccountEvent { override val reference get() = "${leg.prefix}:$transfer" }   // "debit:t-4", as Debited's

// Account.decide: a leg refused before answers with what it said then, writing nothing
account.decide(id, Debit(t4, gbp(22_56)), now)   // Left(InsufficientFunds(...)), and LegRefused written
account.decide(id, Debit(t4, gbp(22_56)), now)   // Left(InsufficientFunds(...)) again, nothing written
```

- `Account.Open.refused: Map<String, AccountError>` holds refusals by leg reference until `LegsClosed` drops them,
  as `legs` holds applied ones. A refused reference counts as open, so the saga's `Close` writes `LegsClosed`.
- The account actor persists `LegRefused` and then answers `Left` with its error, so the first answer and every
  repeat are the same.
- `LegRefused` goes through the journal codec, the wire contract and the events topic's `account.proto` as the other
  leg events do. The read models ignore it: it moves no money.

## Why this shape

The account is where a leg is decided, so it is where the decision has to be kept; spec 0013 already made "the same
leg twice decides once" true for applied legs, and this makes it true for refused ones. The alternative, the saga
refunding a debit that succeeds after it rejected, adds a path back from a settled saga and still pays a refused
credit out twice. A refusal costs one journal row, only for a transfer that was refused.

## Depends on

Nothing. Lark spec 0128 (merged) is what found it.

## Stack

- [ ] **`leg-refused`** — `LegRefused`, `refused` on the account, `decide` and the account actor, the codecs and
      schemas. Done when: a domain spec shows a refused debit and a refused credit answer the same refusal to a
      retry after the account's balance has changed, and write once.
- [ ] **`bank-properties`** (spec 0028) — the seeded property specs, stacked on this. Done when: 1,000 seeds with
      drops, duplicates, delays and crashes keep every property.

## Acceptance

```bash
./gradlew :domain:test :app:test --tests 'bank.app.memory.*'
./gradlew build
```

## Open questions

- Keep the refusal's full `AccountError` in the event, or only its message? Recommended: the error, so a repeat's
  answer is typed exactly like the first; it needs the error in the codec, which `Wire.kt` already has.
- Should a refused `Refund` be kept too? Recommended: no, a refund to the source cannot be refused for funds or the
  daily limit; it can only fail on currency or amount, which no retry changes.
- Does the events topic need `LegRefused` at all? Recommended: yes, with consumers free to skip it, so the topic stays
  a full copy of the account journal.
