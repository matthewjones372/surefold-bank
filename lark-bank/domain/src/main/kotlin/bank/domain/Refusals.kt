package bank.domain

import arrow.core.Either
import arrow.core.left

// A transfer leg's refusal, kept so a retry of it is refused alike (bank spec 0029).

/** Whether any leg of [transfer], applied or refused, is still kept. */
internal fun Account.Open.holdsLegsOf(transfer: TransferId): Boolean =
    (legs.asSequence() + refused.keys).any { it.endsWith(":$transfer") }

/** A copy of a leg refused before, refused again with what it was told then (spec 0029); null for anything else. */
internal fun Account.refusedBefore(id: AccountId, command: AccountCommand): Either<AccountError, Nothing>? =
    (this as? Account.Open)?.refused?.get(command.reference)
        ?.let { AccountError.LegRefused(id.value, command.reference, it).left() }

/**
 * What to write for [command] refused with [refusal]: a transfer leg's first refusal, so its copies are refused alike
 * (spec 0029), and nothing for any other command, or for a copy already refused.
 */
fun Account.refusing(command: AccountCommand, refusal: AccountError, now: Long): List<AccountEvent> {
    val leg = when (command) {
        is AccountCommand.Debit -> Leg.Debit to command.transfer
        is AccountCommand.Credit -> Leg.Credit to command.transfer
        is AccountCommand.Open, is AccountCommand.Deposit, is AccountCommand.Withdraw, is AccountCommand.Refund,
        is AccountCommand.Close,
        -> null
    }
    return when {
        leg == null || this !is Account.Open || refusal is AccountError.LegRefused -> emptyList()
        else -> listOf(AccountEvent.LegRefused(leg.second, leg.first, refusal.message, now))
    }
}
