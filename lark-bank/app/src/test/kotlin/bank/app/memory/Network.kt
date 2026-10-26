package bank.app.memory

import bank.app.AccountsById
import bank.domain.AccountCommand
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountMessage
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Address
import io.github.matthewjones372.surefold.actor.Reply

/** What the way between a saga and an account does to one leg, or to the account's answer to it. */
enum class Fault {
    /** The leg never reaches the account. */
    Drop,

    /** The leg reaches the account twice. */
    Repeat,

    /** The leg is applied, and its answer never gets back. */
    LoseAnswer,

    /** The leg is applied, and its answer is kept until the test lets it go: an answer that arrives late. */
    HoldAnswer,
}

/**
 * The saga's [AccountsById] with a way between it and the accounts that a test can make unkind: Surefold's `testActors`
 * delivers first in, first out and never loses a message (bank spec 0028 until Surefold spec 0128), so the retry and
 * stale-answer cases are made here, one leg at a time.
 */
class Network(private val accounts: AccountsById) : AccountsById {

    private class Rule(val fault: Fault, var times: Int, val leg: (AccountCommand) -> Boolean)

    private val rules = mutableListOf<Rule>()
    private val held = ArrayDeque<() -> Unit>()

    /** How many answers are being kept back. */
    val holding: Int get() = held.size

    /** Every leg a saga has sent, in the order sent, whatever then happened to it on the way. */
    val sent = mutableListOf<AccountCommand>()

    /** The next [times] legs that match [leg] meet [fault]; every other leg goes through untouched. */
    fun next(fault: Fault, times: Int = 1, leg: (AccountCommand) -> Boolean) {
        rules += Rule(fault, times, leg)
    }

    /** The oldest answer kept back arrives now. */
    fun releaseOldest() = held.removeFirst()()

    override fun entity(id: String): ActorRef<AccountMessage> = Wire(accounts.entity(id))

    /** The fault the next matching rule gives [message], used up as it is given, or none. */
    private fun faultFor(message: AccountMessage): Fault? = (message as? AccountAsk)?.command
        ?.let { command -> rules.firstOrNull { it.times > 0 && it.leg(command) } }
        ?.also { it.times-- }
        ?.fault

    private inner class Wire(private val account: ActorRef<AccountMessage>) : ActorRef<AccountMessage> {
        override val address: Address get() = account.address

        override fun tell(message: AccountMessage) {
            (message as? AccountAsk)?.command?.let { sent += it }
            when (faultFor(message)) {
                null -> account.tell(message)
                Fault.Drop -> Unit
                Fault.Repeat -> repeat(2) { account.tell(message) }
                Fault.LoseAnswer -> account.tell(answered(message as AccountAsk) { })
                Fault.HoldAnswer -> account.tell(answered(message as AccountAsk) { answer -> held += answer })
            }
        }
    }

    /** [ask] with its answer handed to [onward] rather than to the saga; [onward] gets the delivery to make later. */
    private fun answered(ask: AccountAsk, onward: (() -> Unit) -> Unit): AccountAsk {
        val saga = ask.reply
        return ask.copy(
            reply = object : Reply<AccountAnswer> {
                override val address: Address get() = saga.address

                override fun invoke(answer: AccountAnswer) = onward { saga(answer) }
            },
        )
    }
}
