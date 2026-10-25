package bank.domain

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right

// A transfer leg's refusal, kept so a retry of it is refused alike (bank spec 0030).

/** Whether any leg of [transfer], applied or refused, is still kept. */
internal fun Account.holdsLegsOf(transfer: TransferId): Boolean =
    ((this as? Account.Open)?.legs.orEmpty().asSequence() + refusals.keys).any { it.endsWith(":$transfer") }

/** The legs this account refused whose transfers have not yet closed, opened or not. */
internal val Account.refusals: Map<String, String>
    get() = when (this) {
        Account.Unopened -> emptyMap()
        is Account.Refusing -> refused
        is Account.Open -> refused
    }

/** A copy of a leg refused before, refused again with what it was told then (spec 0030); null for anything else. */
internal fun Account.refusedBefore(id: AccountId, command: AccountCommand): Either<AccountError, Nothing>? =
    refusals[command.reference]?.let { AccountError.LegRefused(id.value, command.reference, it).left() }

/** A never-opened account's refusals of [command]'s transfer, closed; null for anything else. */
internal fun Account.closingRefusals(command: AccountCommand, now: Long): Either<AccountError, List<AccountEvent>>? =
    (command as? AccountCommand.Close)?.takeIf { close -> this is Account.Refusing && holdsLegsOf(close.transfer) }
        ?.let { listOf(AccountEvent.LegsClosed(it.transfer, now)).right() }

/** The state once [reference] was refused for [reason]: an account never opened starts keeping refusals. */
internal fun Account.refused(reference: String, reason: String): Account = when (this) {
    Account.Unopened, is Account.Refusing -> Account.Refusing(refusals + (reference to reason))
    is Account.Open -> copy(refused = refused + (reference to reason))
}

/** The state once [transfer] closed: its applied and refused legs forgotten. */
internal fun Account.closed(transfer: TransferId): Account {
    val of = { reference: String -> reference.endsWith(":$transfer") }
    return when (this) {
        Account.Unopened, is Account.Refusing ->
            refusals.filterKeys { !of(it) }.let { if (it.isEmpty()) Account.Unopened else Account.Refusing(it) }

        is Account.Open -> copy(legs = legs.filterNot(of).toSet(), refused = refused.filterKeys { !of(it) })
    }
}

/**
 * What to write for [command] refused with [refusal]: a transfer leg's first refusal, so its copies are refused alike
 * (spec 0030), and nothing for any other command, or for a copy already refused.
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
        leg == null || refusal is AccountError.LegRefused -> emptyList()
        else -> listOf(AccountEvent.LegRefused(leg.second, leg.first, refusal.message, now))
    }
}
