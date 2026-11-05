package bank.app

import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Bank
import bank.domain.Money
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferView
import bank.domain.Unavailable
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountMessage
import dev.surefold.actor.ActorRef
import dev.surefold.actor.AskFailure
import dev.surefold.actor.ask
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.timed
import kotlin.time.Duration

/**
 * The bank as sharded entities: each call is an ask to the one entity that owns the id, on whichever node that is.
 * An ask that is not answered in time is [Unavailable] — the caller retries with the same reference, and the
 * entity's idempotency makes the retry safe.
 */
class ShardedBank(
    private val accounts: (String) -> ActorRef<AccountMessage>,
    private val transfers: TransferMoves,
    private val askTimeout: Duration,
    private val transferWait: Duration,
) : Bank {

    override fun open(id: AccountId, owner: String, initial: Money, reference: String, dailyLimit: Money?) =
        command(id, AccountCommand.Open(owner, initial, reference, dailyLimit))

    override fun deposit(id: AccountId, amount: Money, reference: String) =
        command(id, AccountCommand.Deposit(amount, reference))

    override fun withdraw(id: AccountId, amount: Money, reference: String) =
        command(id, AccountCommand.Withdraw(amount, reference))

    override fun balance(id: AccountId): Either<AccountError, Balance> = timed(
        "bank.ask.duration",
        "entity" to "account",
    ) {
        accounts(id.value).ask<AccountMessage, AccountAnswer>(askTimeout) { AccountAsk(null, it) }
            .fold({ failure -> unavailable("account $id", failure).left() }, { it })
    }

    private fun command(id: AccountId, command: AccountCommand): Either<AccountError, Balance> =
        timed("bank.ask.duration", "entity" to "account") {
            accounts(id.value).ask<AccountMessage, AccountAnswer>(askTimeout) { AccountAsk(command, it) }
                .fold({ failure -> unavailable("account $id", failure).left() }, { it })
        }

    /**
     * Waits [transferWait] for the saga to settle. One that has not is answered as far as it has got: pending,
     * debited or refunding, still moving, and safe to ask about again.
     */
    override fun transfer(
        id: TransferId,
        from: AccountId,
        to: AccountId,
        amount: Money,
    ): Either<TransferError, TransferView> = when {
        from == to -> TransferError.SameAccount(from.value).left()

        !amount.isPositive -> TransferError.InvalidTransferAmount(amount).left()

        else -> timed("bank.ask.duration", "entity" to "transfer") {
            transfers.transfer(id, from, to, amount, transferWait)
        }
    }

    override fun transferStatus(id: TransferId): Either<TransferError, TransferView> =
        timed("bank.ask.duration", "entity" to "transfer") { transfers.status(id) }
}

private fun unavailable(what: String, failure: AskFailure) = Unavailable(
    when (failure) {
        AskFailure.TimedOut -> "$what did not answer in time"
        AskFailure.Stopped -> "$what stopped before answering"
        AskFailure.Unreachable -> "$what is on a node that cannot be reached"
    },
)
