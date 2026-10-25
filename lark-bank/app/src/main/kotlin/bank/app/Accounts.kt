package bank.app

import bank.domain.Account
import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.balanceOf
import bank.domain.decide
import bank.domain.evolve
import bank.domain.refusing
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.BulkCreditSent
import bank.protocol.Kinds
import bank.protocol.WriteClosed
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.Snapshotting
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.otel.tracedSpan
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlin.time.Duration

/** The time an event is stamped with: lark's clock, so a test can hold it still. */
internal fun now(): Long = clock.get().now().toEpochMilli()

/**
 * One account: the only writer of its balance, wherever in the cluster its shard lives. Each command is decided by
 * the pure domain, written to the journal, and answered once written; the commands waiting for a busy account are
 * written a batch to an append. A reliably delivered credit is confirmed by
 * `delivered` after its step, and dropped unrun if its delivery was already handled.
 *
 * With [closeAfter], a settled transfer's close is answered at once and held in memory, and written with the next
 * thing the account writes, or after [closeAfter] if nothing is (bank spec 0031): a busy account's closes cost no
 * append of their own. Until written, the transfer's legs stay open, which only answers a late copy of one as applied
 * (spec 0013). A restart forgets the closes held, and those legs then stay open.
 */
fun account(
    id: AccountId,
    snapshots: Snapshotting<Account>?,
    batch: Int = 1,
    tracer: Tracer = OpenTelemetry.noop().getTracer("lark-bank"),
    closeAfter: Duration? = null,
): Behaviour<AccountMessage, Remembered<Account>, JournalConflict> {
    // Each held close's events, by transfer, oldest first: a second close of the same transfer adds nothing.
    val closing = LinkedHashMap<TransferId, List<AccountEvent>>()

    /** [events] with every close held before them, which they now carry. */
    fun carrying(events: List<AccountEvent>): Array<AccountEvent> =
        (closing.values.flatten() + events).toTypedArray().also { closing.clear() }

    /** Whether [command] is a close to hold, as it is when closes wait: held, with the timer started for the first. */
    fun Ctx<AccountMessage>.held(command: AccountCommand, events: List<AccountEvent>): Boolean {
        if (closeAfter == null || command !is AccountCommand.Close || events.isEmpty()) return false
        if (closing.isEmpty()) timers.after(CLOSING, closeAfter, WriteClosed)
        closing[command.transfer] = events
        return true
    }

    return delivered(
        persistent<AccountMessage, AccountEvent, Account>(
            id = PersistenceId(Kinds.ACCOUNT, id.value),
            empty = Account.Unopened,
            codec = AccountEvents,
            command = { ctx, state, message ->
                when (message) {
                    is AccountAsk -> {
                        val command = message.command
                        if (command == null) {
                            none().then { message.reply(state.balanceOf(id)) }
                        } else {
                            tracer.tracedSpan("account ${command::class.simpleName?.lowercase()}") {
                                // A span on the account's node, in the trace the command arrived in (bank spec 0024).
                                state.decide(id, command, now()).fold(
                                    { refusal ->
                                        counted("refused")
                                        // A leg's first refusal is written, so its copies are refused alike
                                        // (spec 0030).
                                        persist(*carrying(state.refusing(command, refusal, now())))
                                            .then { message.reply(Either.Left(refusal)) }
                                    },
                                    { events ->
                                        counted(if (events.isEmpty()) "repeated" else "applied")
                                        if (ctx.held(command, events)) {
                                            none().then { message.reply(state.balanceOf(id)) }
                                        } else {
                                            persist(*carrying(events)).then { after ->
                                                message.reply(after.balanceOf(id))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    is BulkCreditSent ->
                        state.decide(id, AccountCommand.Deposit(message.credit.amount, message.credit.reference), now())
                            .fold(
                                {
                                    counted("refused")
                                    none()
                                },
                                { events ->
                                    counted("bulk")
                                    persist(*carrying(events))
                                },
                            )

                    // Nothing written since the first close was held: the closes go in an append of their own.
                    WriteClosed -> persist(*carrying(emptyList()))
                }
            },
            event = Account::evolve,
            snapshots = snapshots,
            // A busy account decides the commands waiting for it and writes them in one append (lark spec 0085).
            batch = batch,
        ).onStart { closing.clear() },
    )
}

private const val CLOSING = "closing"

/** One tag, one key, always the same set, or Prometheus drops the series. */
private fun counted(outcome: String) = counter("bank.account.commands", "outcome" to outcome).increment()
