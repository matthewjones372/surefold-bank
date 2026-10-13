package bank.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** What an account is asked to do. [reference] is the caller's idempotency key: the same one twice changes nothing. */
sealed interface AccountCommand {
    val reference: String

    /**
     * Opens the account in [initial]'s currency, which it keeps: every amount after must be in it too. It pays out at
     * most [dailyLimit] a UTC day, 10,000 in that currency when none is given (bank spec 0027).
     */
    data class Open(
        val owner: String,
        val initial: Money,
        override val reference: String,
        val dailyLimit: Money? = null,
    ) : AccountCommand
    data class Deposit(val amount: Money, override val reference: String) : AccountCommand
    data class Withdraw(val amount: Money, override val reference: String) : AccountCommand

    /** The first leg of a transfer: money leaves, and is in flight until the other account takes it. */
    data class Debit(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "debit:$transfer"
    }

    data class Credit(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "credit:$transfer"
    }

    /** A debit given back, because the credit it was for could not be made. */
    data class Refund(val transfer: TransferId, val amount: Money) : AccountCommand {
        override val reference: String get() = "refund:$transfer"
    }

    /** The transfer has settled, so none of its legs can be retried: the account may forget them (spec 0013). */
    data class Close(val transfer: TransferId) : AccountCommand {
        override val reference: String get() = "close:$transfer"
    }
}

/** What happened to an account. Every one of these is a row in the journal; the state is their fold. */
sealed interface AccountEvent {
    val reference: String
    val atMillis: Long

    /** [dailyLimit] is the limit the account opened with; null only in an `Opened` written before limits. */
    data class Opened(
        val owner: String,
        val initial: Money,
        override val reference: String,
        override val atMillis: Long,
        val dailyLimit: Money? = null,
    ) : AccountEvent

    data class Deposited(val amount: Money, override val reference: String, override val atMillis: Long) : AccountEvent
    data class Withdrawn(val amount: Money, override val reference: String, override val atMillis: Long) : AccountEvent
    data class Debited(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "debit:$transfer"
    }

    data class Credited(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "credit:$transfer"
    }

    data class Refunded(val transfer: TransferId, val amount: Money, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "refund:$transfer"
    }

    /** A settled transfer's legs, and its refusals, forgotten. Moves no money. */
    data class LegsClosed(val transfer: TransferId, override val atMillis: Long) : AccountEvent {
        override val reference: String get() = "close:$transfer"
    }

    /**
     * A transfer's [leg] (`debit`, `credit` or `refund`) refused for [reason], kept so that the same leg sent again is
     * refused again however the account has changed since (bank spec 0030). Moves no money.
     */
    data class LegRefused(val transfer: TransferId, val leg: String, val reason: String, override val atMillis: Long) :
        AccountEvent {
        override val reference: String get() = "$leg:$transfer"
    }
}

/** The ways an account says no. Only a transfer's leg refused is written, as `LegRefused` (bank spec 0030). */
sealed interface AccountError {
    val message: String

    data class NoSuchAccount(val id: String) : AccountError {
        override val message: String get() = "No account $id"
    }

    data class AlreadyOpen(val id: String) : AccountError {
        override val message: String get() = "Account $id is already open"
    }

    data class InsufficientFunds(val id: String, val balance: Money, val requested: Money) : AccountError {
        override val message: String get() = "Account $id has $balance, not $requested"
    }

    data class InvalidAmount(val amount: Money) : AccountError {
        override val message: String get() = "$amount is not an amount that can move"
    }

    /** An amount in another currency than the account's: refused, never converted (conversion is spec 0015's). */
    data class CurrencyMismatch(val id: String, val account: String, val given: String) : AccountError {
        override val message: String get() = "Account $id is in $account, not $given"
    }

    /** A transfer's leg this account refused before, for [reason]: it is refused again, the same way (bank spec 0030). */
    data class AlreadyRefused(val id: String, val reason: String) : AccountError {
        override val message: String get() = reason
    }

    /** Paying [requested] out would take the account past what it may pay out in a UTC day (bank spec 0027). */
    data class DailyLimitExceeded(val id: String, val limit: Money, val remaining: Money, val requested: Money) :
        AccountError {
        override val message: String
            get() = "Account $id may pay out $limit a day and has $remaining of that left today, not $requested"
    }
}

/** The bank could not answer in time: a node moving, a journal slow. Worth trying again with the same reference. */
data class Unavailable(override val message: String) : AccountError, TransferError

/**
 * An account's state. [recent] holds the last [RECENT] references applied, oldest first, so a caller's retried
 * command is recognised without the state growing with every transaction the account has seen. [legs] holds the
 * references of transfer legs applied whose transfer has not yet settled: a saga retries a leg on a timer, long after
 * a busy account has moved past [RECENT] other commands, so those are kept until the saga closes them (spec 0013).
 * [paidOut] is what the account paid out on [paidOutOn], a UTC day since the epoch, and [debitedOn] the transfers
 * whose debits are in it, so a refund knows whether to give its debit back (spec 0027). [dailyLimit], [paidOut] and
 * [debitedOn] are null or empty in a snapshot taken before limits. [refused] maps each transfer leg the account
 * refused, until its transfer settles, to the reason, so the leg sent again is refused again (spec 0030).
 */
sealed interface Account {
    data object Unopened : Account

    /** Not opened, but it refused a transfer's leg for that, and keeps the refusal as an open account would. */
    data class Refusing(val refused: Map<String, String>) : Account

    data class Open(
        val owner: String,
        val balance: Money,
        val recent: List<String>,
        val legs: Set<String> = emptySet(),
        val dailyLimit: Money? = null,
        val paidOutOn: Long = 0,
        val paidOut: Money? = null,
        val debitedOn: Set<String> = emptySet(),
        val refused: Map<String, String> = emptyMap(),
    ) : Account

    companion object {
        const val RECENT = 256
    }
}

/** The balance an account answers with, whether the command moved it or was a repeat. */
data class Balance(val id: String, val owner: String, val balance: Money)

/**
 * The account's decision: the events a command produces, none for a repeat, or the refusal. Pure, so every
 * rule about money is tested without an actor, a journal or a clock.
 */
fun Account.decide(id: AccountId, command: AccountCommand, now: Long): Either<AccountError, List<AccountEvent>> =
    when (this) {
        Account.Unopened -> when (command) {
            is AccountCommand.Open -> opening(id, command, now)
            is AccountCommand.Deposit, is AccountCommand.Withdraw, is AccountCommand.Debit, is AccountCommand.Credit,
            is AccountCommand.Refund, is AccountCommand.Close,
            -> AccountError.NoSuchAccount(id.value).left()
        }
        is Account.Refusing -> when (command) {
            is AccountCommand.Open -> opening(id, command, now)
            is AccountCommand.Close ->
                if (refused.keys.none { it.endsWith(":${command.transfer}") }) AccountError.NoSuchAccount(id.value).left()
                else listOf(AccountEvent.LegsClosed(command.transfer, now)).right()
            is AccountCommand.Debit, is AccountCommand.Credit, is AccountCommand.Refund ->
                refused[command.reference]?.let { AccountError.AlreadyRefused(id.value, it).left() }
                    ?: AccountError.NoSuchAccount(id.value).left()
            is AccountCommand.Deposit, is AccountCommand.Withdraw -> AccountError.NoSuchAccount(id.value).left()
        }
        is Account.Open -> when (command) {
            // Opening an open account again for the same owner is a retry; for anyone else it is a clash.
            is AccountCommand.Open ->
                if (command.owner == owner) emptyList<AccountEvent>().right() else AccountError.AlreadyOpen(id.value).left()
            is AccountCommand.Deposit -> once(command) { moving(id, command.amount) { AccountEvent.Deposited(it, command.reference, now) } }
            is AccountCommand.Credit ->
                decidedOnce(id, command) { moving(id, command.amount) { AccountEvent.Credited(command.transfer, it, now) } }
            is AccountCommand.Refund ->
                decidedOnce(id, command) { moving(id, command.amount) { AccountEvent.Refunded(command.transfer, it, now) } }
            is AccountCommand.Withdraw ->
                once(command) { taking(id, command.amount, now) { AccountEvent.Withdrawn(it, command.reference, now) } }
            is AccountCommand.Debit ->
                decidedOnce(id, command) { taking(id, command.amount, now) { AccountEvent.Debited(command.transfer, it, now) } }
            is AccountCommand.Close ->
                if ((legs + refused.keys).none { it.endsWith(":${command.transfer}") }) emptyList<AccountEvent>().right()
                else listOf(AccountEvent.LegsClosed(command.transfer, now)).right()
        }
    }

/**
 * What to write when [command] is refused with [refusal]: a transfer's leg is decided once (bank spec 0030), so its
 * refusal is kept, and the same leg sent again is refused again. Null for anything else: a deposit or withdrawal, a leg
 * refused because it was refused before, or an account that could not decide at all.
 */
fun Account.refusalToKeep(command: AccountCommand, refusal: AccountError, now: Long): AccountEvent.LegRefused? {
    val (transfer, leg) = when (command) {
        is AccountCommand.Debit -> command.transfer to "debit"
        is AccountCommand.Credit -> command.transfer to "credit"
        is AccountCommand.Refund -> command.transfer to "refund"
        else -> return null
    }
    return when (refusal) {
        is AccountError.AlreadyRefused, is Unavailable -> null
        else -> AccountEvent.LegRefused(transfer, leg, refusal.message, now)
    }
}

/** Nothing, if [command]'s reference was applied recently: the same command twice moves the money once. */
private inline fun Account.Open.once(
    command: AccountCommand,
    decide: () -> Either<AccountError, List<AccountEvent>>,
): Either<AccountError, List<AccountEvent>> =
    if (command.reference in recent || command.reference in legs) emptyList<AccountEvent>().right() else decide()

/** A transfer's leg refused before is refused again, the same way (spec 0030); applied before, it is a repeat. */
private inline fun Account.Open.decidedOnce(
    id: AccountId,
    command: AccountCommand,
    decide: () -> Either<AccountError, List<AccountEvent>>,
): Either<AccountError, List<AccountEvent>> =
    refused[command.reference]?.let { AccountError.AlreadyRefused(id.value, it).left() } ?: once(command, decide)

private fun Account.Open.moving(
    id: AccountId,
    amount: Money,
    event: (Money) -> AccountEvent,
): Either<AccountError, List<AccountEvent>> = when {
    amount.currency != balance.currency -> AccountError.CurrencyMismatch(id.value, balance.currency.code, amount.currency.code).left()
    !amount.isPositive -> AccountError.InvalidAmount(amount).left()
    else -> listOf(event(amount)).right()
}

private fun Account.Open.taking(
    id: AccountId,
    amount: Money,
    now: Long,
    event: (Money) -> AccountEvent,
): Either<AccountError, List<AccountEvent>> = when {
    amount.currency != balance.currency -> AccountError.CurrencyMismatch(id.value, balance.currency.code, amount.currency.code).left()
    !amount.isPositive -> AccountError.InvalidAmount(amount).left()
    amount > balance -> AccountError.InsufficientFunds(id.value, balance, amount).left()
    amount > leftOn(now) -> AccountError.DailyLimitExceeded(id.value, limit, leftOn(now), amount).left()
    else -> listOf(event(amount)).right()
}

/** The state after an event. Recovery is this, folded over the journal from the newest snapshot. */
fun Account.evolve(event: AccountEvent): Account = when (event) {
    // A refusing account keeps its refusals once opened: a credit refused before it existed stays refused (spec 0030).
    is AccountEvent.Opened -> Account.Open(
        event.owner, event.initial, listOf(event.reference), dailyLimit = event.dailyLimit,
        refused = (this as? Account.Refusing)?.refused.orEmpty(),
    )
    is AccountEvent.Deposited -> open().moved(event.amount, event.reference)
    is AccountEvent.Credited -> open().moved(event.amount, event.reference).leg(event.reference)
    is AccountEvent.Refunded -> open().moved(event.amount, event.reference).leg(event.reference)
        .givenBack(event.transfer, event.amount, event.atMillis)
    is AccountEvent.Withdrawn -> open().moved(-event.amount, event.reference).paid(event.amount, event.atMillis)
    is AccountEvent.Debited -> open().moved(-event.amount, event.reference).leg(event.reference)
        .paid(event.amount, event.atMillis, event.transfer)
    is AccountEvent.LegsClosed -> when (this) {
        is Account.Refusing -> refused.filterKeys { !it.endsWith(":${event.transfer}") }
            .let { left -> if (left.isEmpty()) Account.Unopened else Account.Refusing(left) }
        else -> open().let { open ->
            open.copy(
                legs = open.legs.filterNot { it.endsWith(":${event.transfer}") }.toSet(),
                refused = open.refused.filterKeys { !it.endsWith(":${event.transfer}") },
            )
        }
    }
    is AccountEvent.LegRefused -> when (this) {
        Account.Unopened -> Account.Refusing(mapOf(event.reference to event.reason))
        is Account.Refusing -> copy(refused = refused + (event.reference to event.reason))
        is Account.Open -> copy(refused = refused + (event.reference to event.reason))
    }
}

private fun Account.Open.leg(reference: String): Account.Open = copy(legs = legs + reference)

private fun Account.open(): Account.Open = when (this) {
    is Account.Open -> this
    // A journal whose first money event comes before Opened is a corrupt journal, not a case to handle.
    Account.Unopened, is Account.Refusing -> error("an event for an account that was never opened")
}

private fun Account.Open.moved(by: Money, reference: String): Account.Open =
    copy(balance = balance + by, recent = (recent + reference).takeLast(Account.RECENT))

fun Account.balanceOf(id: AccountId): Either<AccountError, Balance> = when (this) {
    Account.Unopened, is Account.Refusing -> AccountError.NoSuchAccount(id.value).left()
    is Account.Open -> Balance(id.value, owner, balance).right()
}
