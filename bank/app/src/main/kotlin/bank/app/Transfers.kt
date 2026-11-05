package bank.app

import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Currency
import bank.domain.Money
import bank.domain.ScreeningDecision
import bank.domain.Step
import bank.domain.Transfer
import bank.domain.TransferError
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.TransferView
import bank.domain.Unavailable
import bank.domain.WhenUnanswered
import bank.domain.evolve
import bank.domain.screened
import bank.domain.unanswered
import bank.domain.view
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.TransferEvents
import dev.surefold.Promise
import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.AskFailure
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.ask
import dev.surefold.actor.events
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.cluster.saga.Diverged
import dev.surefold.cluster.saga.Durable
import dev.surefold.cluster.saga.DurableScope
import dev.surefold.cluster.saga.Overtaken
import dev.surefold.cluster.saga.Sagas
import dev.surefold.cluster.saga.Stuck
import dev.surefold.cluster.saga.Unsettled
import dev.surefold.cluster.saga.durableSaga
import dev.surefold.cluster.saga.run
import dev.surefold.counter
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.increment
import dev.surefold.logDebug
import dev.surefold.otel.tracedSpan
import dev.surefold.resourceScope
import dev.surefold.timeoutOrNull
import io.opentelemetry.api.trace.Tracer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Where an account entity is, by id, on whichever node asks. */
fun interface AccountsById {
    fun entity(id: String): ActorRef<AccountMessage>
}

/**
 * What one transfer is asked to do. [whenUnanswered] null means this transfer is not screened: it is part of the
 * journalled input, so a node that resumes the saga takes the same steps even if screening has since been switched.
 */
internal data class TransferInput(
    val from: AccountId,
    val to: AccountId,
    val amount: Money,
    val whenUnanswered: WhenUnanswered?,
)

/**
 * Why a transfer's saga stopped short of completing. The HTTP API still answers a view: the events written on the
 * way say whether it was rejected or refunded. A declared failure is what makes the debit's undo run.
 */
internal enum class TransferHalt { Rejected, Refunded }

/**
 * The saga's own journal. Not [Kinds.TRANSFER]: that stream is the published transfer events, which a projection reads.
 */
internal const val TRANSFER_SAGA = "bank.transfer"

/** The input claimed for an id, so a second start with different details is refused before the saga is asked. */
private const val TRANSFER_CLAIM = "transfer-claim"

/**
 * One transfer as a durable saga (spec 0034): each step is journalled, and a run that dies resumes from the last
 * step that ended. The accounts stay the bank's own actors. The debit is not a transfer event — the account's
 * `Debited` is that record (spec 0031) — and a credit the destination refuses fails the saga so the debit's undo
 * gives the money back.
 */
internal fun transferSaga(
    journal: Journal,
    accounts: AccountsById,
    legTimeout: Duration,
    screening: Screening,
    tracer: Tracer,
): Durable<TransferInput, TransferHalt, TransferView> = durableSaga(
    TRANSFER_SAGA,
    Inputs,
    Halts,
    Views,
) { input ->
    TransferBody(this, input, journal, accounts, legTimeout, screening, tracer).move()
}

/** Starts, resumes and reads transfers. [runs] is the cluster's; tests drive [saga] on the calling thread. */
class DurableTransfers internal constructor(
    private val journal: Journal,
    private val screening: Screening,
    private val askTimeout: Duration,
    private val saga: Durable<TransferInput, TransferHalt, TransferView>,
    private val runs: Sagas<TransferInput, TransferHalt, TransferView>? = null,
) : TransferMoves {
    // One direct run per id. The next caller waits for this one's promise, then runs its own body, so a different
    // transfer under the same id is refused rather than handed the first caller's answer.
    private val gates = Shared(emptyMap<String, Promise<Nothing, Unit>>(), "bank.transfer.direct")

    override fun transfer(
        id: TransferId,
        from: AccountId,
        to: AccountId,
        amount: Money,
        wait: Duration,
    ): Either<TransferError, TransferView> {
        val input = TransferInput(from, to, amount, (screening as? Screening.On)?.whenUnanswered)
        claim(id.value, input).onLeft { return it.left() }
        val cluster = runs
        return if (cluster == null) {
            directly(id.value, input)
        } else {
            across(cluster, id, input, wait)
        }
    }

    override fun status(id: TransferId): Either<TransferError, TransferView> = standing(id.value)

    /**
     * A transfer the read model still has open. Starting is idempotent, and `await` with nothing to wait for resumes
     * a saga whose runner died: the waker does this when a node leaves, and the sweeper does it for one that stopped
     * moving while its node is still up.
     */
    override fun wake(id: String) {
        val cluster = runs ?: return
        val events = transferEvents(id)
        val requested = events.filterIsInstance<TransferEvent.Requested>().firstOrNull()
        val folded = events.fold<TransferEvent, Transfer>(Transfer.Unrequested, Transfer::evolve)
        val settled = (folded as? Transfer.Moving)?.settled == true
        if (requested != null && !settled) {
            val input = TransferInput(
                requested.from,
                requested.to,
                requested.amount,
                (screening as? Screening.On)?.whenUnanswered,
            )
            cluster.start(id, input, askTimeout)
            cluster.await(id, 1.milliseconds)
        }
    }

    private fun directly(id: String, input: TransferInput): Either<TransferError, TransferView> = oneAtATime(id) {
        when (val ran = Either.catch { saga.run(journal, id, input) }) {
            is Either.Right -> ran.value.fold({ standing(id) }, { it.right() })

            is Either.Left -> when (val failed = ran.value) {
                is Stuck, is Unsettled, is Diverged, is Overtaken -> unavailable(failed.message, id).left()
                else -> unavailable(failed.message ?: failed::class.simpleName.orEmpty(), id).left()
            }
        }
    }

    private fun <A> oneAtATime(id: String, block: () -> A): A {
        val mine = Promise<Nothing, Unit>("bank.transfer.direct")
        val earlier = gates.modify { held -> (held + (id to mine)) to held[id] }
        return resourceScope {
            onRelease {
                gates.update { held -> if (held[id] === mine) held - id else held }
                mine.succeed(Unit)
            }
            earlier?.awaitValue()
            block()
        }
    }

    private fun across(
        cluster: Sagas<TransferInput, TransferHalt, TransferView>,
        id: TransferId,
        input: TransferInput,
        wait: Duration,
    ): Either<TransferError, TransferView> = cluster.start(id.value, input, askTimeout).fold(
        { failure ->
            when (failure) {
                // Started may be on its way. If the journal has nothing of the saga, the same id again is safe.
                AskFailure.TimedOut -> unstarted(id.value, standing(id.value), sagaStarted(id.value))

                AskFailure.Stopped, AskFailure.Unreachable -> unavailable("transfer $id", failure).left()
            }
        },
        {
            when (val outcome = cluster.await(id.value, wait)) {
                is Durable.Outcome.Done -> outcome.value.right()

                // The undo has finished, so the events fold to rejected or refunded.
                is Durable.Outcome.Refused -> standing(id.value)

                is Durable.Outcome.Running -> standing(id.value)

                is Durable.Outcome.Stuck -> Unavailable(outcome.reason).left()

                Durable.Outcome.Forgotten -> unstarted(id.value, standing(id.value), sagaStarted(id.value))
            }
        },
    )

    private fun sagaStarted(id: String): Boolean = journal.read(PersistenceId(TRANSFER_SAGA, id)).isNotEmpty()

    private fun standing(id: String): Either<TransferError, TransferView> {
        val moving = transferEvents(id).fold<TransferEvent, Transfer>(Transfer.Unrequested, Transfer::evolve)
        val claimed = if (moving is Transfer.Moving) null else claimed(id)
        return when {
            moving is Transfer.Moving -> moving.view(TransferId(id)).right()

            claimed != null ->
                TransferView(id, claimed.from.value, claimed.to.value, claimed.amount, TransferStatus.Pending, null)
                    .right()

            else -> TransferError.NoSuchTransfer(id).left()
        }
    }

    private fun transferEvents(id: String): List<TransferEvent> =
        journal.events(PersistenceId(Kinds.TRANSFER, id), TransferEvents)

    /**
     * The same input twice is one transfer. A different one under an id already claimed is refused, and not started.
     */
    private fun claim(id: String, input: TransferInput): Either<TransferError.TransferIdReused, Unit> {
        fun stored(): TransferInput? = journal.read(PersistenceId(TRANSFER_CLAIM, id)).firstOrNull()
            ?.let { Inputs.decode(it.bytes) }

        stored()?.let { kept ->
            return if (kept == input) Unit.right() else TransferError.TransferIdReused(id).left()
        }
        return journal.append(PersistenceId(TRANSFER_CLAIM, id), 0, listOf(Inputs.encode(input))).fold(
            {
                val kept = stored()
                if (kept == input) Unit.right() else TransferError.TransferIdReused(id).left()
            },
            { Unit.right() },
        )
    }

    private fun claimed(id: String): TransferInput? =
        journal.read(PersistenceId(TRANSFER_CLAIM, id)).firstOrNull()?.let { Inputs.decode(it.bytes) }
}

/** What the bank asks of its transfers, in place of an actor per id. */
interface TransferMoves {
    fun transfer(
        id: TransferId,
        from: AccountId,
        to: AccountId,
        amount: Money,
        wait: Duration,
    ): Either<TransferError, TransferView>

    fun status(id: TransferId): Either<TransferError, TransferView>

    fun wake(id: String)
}

/**
 * A start that never landed. The caller sends the same id again; it is not a transfer that does not exist.
 */
internal fun unstarted(
    id: String,
    standing: Either<TransferError, TransferView>,
    sagaStarted: Boolean,
): Either<TransferError, TransferView> {
    if (sagaStarted) return standing
    val pending = standing is Either.Right && standing.value.status == TransferStatus.Pending
    val missing = standing is Either.Left && standing.value is TransferError.NoSuchTransfer
    return if (pending || missing) {
        Unavailable("transfer $id did not start in time; send it again with the same id").left()
    } else {
        standing
    }
}

private fun unavailable(message: String?, id: String) = Unavailable(message ?: "transfer $id stopped")

private fun unavailable(what: String, failure: AskFailure) = Unavailable(
    when (failure) {
        AskFailure.TimedOut -> "$what did not answer in time"
        AskFailure.Stopped -> "$what stopped before answering"
        AskFailure.Unreachable -> "$what is on a node that cannot be reached"
    },
)

private class TransferBody(
    private val scope: DurableScope<TransferHalt>,
    private val input: TransferInput,
    private val journal: Journal,
    private val accounts: AccountsById,
    private val legTimeout: Duration,
    private val screening: Screening,
    private val tracer: Tracer,
) {
    private val transfer = TransferId(scope.id)
    private val log = TransferLog(journal, transfer)

    fun move(): TransferView {
        val requestedAt = scope.step(
            "request",
            Longs,
            action = { _ ->
                log.requested()?.atMillis ?: now().also { at ->
                    log.append(TransferEvent.Requested(input.from, input.to, input.amount, at))
                }
            },
            check = { _ ->
                log.requested()?.let { Settled.Happened(it.atMillis) } ?: Settled.DidNot
            },
        )
        val policy = input.whenUnanswered
        if (policy != null) {
            scope.step(
                "screen",
                Units,
                action = { _ -> screen(policy, requestedAt) },
                check = { _ -> if (log.screened() != null) Settled.Happened(Unit) else Settled.DidNot },
            )
            val moving = log.moving()
            if (moving.status == TransferStatus.Rejected) {
                closeBoth()
                return moving.view(transfer)
            }
        }
        scope.step(
            "debit",
            Units,
            action = { _ -> debit() },
            check = { _ ->
                when {
                    log.has<TransferEvent.Rejected>() -> Settled.DidNot
                    debited() -> Settled.Happened(Unit)
                    else -> Settled.DidNot
                }
            },
            undo = { _, _, _ -> refund() },
        )
        scope.step(
            "credit",
            Units,
            action = { _ -> credit() },
            check = { _ ->
                when {
                    log.has<TransferEvent.CreditRefused>() -> Settled.DidNot
                    log.has<TransferEvent.DestinationCredited>() -> Settled.Happened(Unit)
                    else -> Settled.DidNot
                }
            },
        )
        closeBoth()
        return log.moving().view(transfer)
    }

    private fun screen(policy: WhenUnanswered, requestedAt: Long) {
        val proposed = Step.Screen(input.from, input.to, input.amount, requestedAt)
        val on = screening as? Screening.On
        val decision = if (on == null) {
            null
        } else {
            scope.timeoutOrNull(on.timeout) { on.screener.screen(transfer, proposed) }
        }
        val parsed: ScreeningDecision? = decision?.fold({ null }, { it })
        if (parsed == null) counter("bank.transfer.screened_unanswered").increment()
        val at = now()
        if (log.screened() == null) log.append(parsed?.screened(at) ?: unanswered(policy, at))
    }

    private fun debit() {
        if (log.has<TransferEvent.Rejected>()) scope.fail(TransferHalt.Rejected)
        leg(input.from, AccountCommand.Debit(transfer, input.amount)).fold(
            { refusal ->
                if (!log.has<TransferEvent.Rejected>()) log.append(TransferEvent.Rejected(refusal.message, now()))
                closeBoth()
                scope.fail(TransferHalt.Rejected)
            },
            {
                // Heard, not written (spec 0031): the account's Debited is the durable record of the debit.
                counter("bank.transfer.steps", "event" to "SourceDebited").increment()
            },
        )
    }

    /** Gives the debit back. A refusal is tried again: the money stays in flight until the account takes it. */
    private fun refund() {
        val command = AccountCommand.Refund(transfer, input.amount)
        while (leg(input.from, command) is Either.Left) pause()
        if (!log.has<TransferEvent.SourceRefunded>()) log.append(TransferEvent.SourceRefunded(now()))
        closeBoth()
    }

    private fun credit() {
        if (log.has<TransferEvent.CreditRefused>()) scope.fail(TransferHalt.Refunded)
        leg(input.to, AccountCommand.Credit(transfer, input.amount)).fold(
            { refusal ->
                if (!log.has<TransferEvent.CreditRefused>()) {
                    log.append(TransferEvent.CreditRefused(refusal.message, now()))
                }
                scope.fail(TransferHalt.Refunded)
            },
            {
                if (!log.has<TransferEvent.DestinationCredited>()) {
                    log.append(TransferEvent.DestinationCredited(now()))
                }
            },
        )
    }

    /** The account already applied this transfer's debit, so a step that began and never ended is not sent again. */
    private fun debited(): Boolean = journal.events(PersistenceId(Kinds.ACCOUNT, input.from.value), AccountEvents)
        .any { it is AccountEvent.Debited && it.transfer == transfer }

    private fun leg(account: AccountId, command: AccountCommand): AccountAnswer {
        while (true) {
            val asked = Either.catchOrThrow<IllegalStateException, Either<AskFailure, AccountAnswer>> {
                tracer.tracedSpan("transfer ${command::class.simpleName?.lowercase()}") {
                    accounts.entity(account.value).ask<AccountMessage, AccountAnswer>(legTimeout) {
                        AccountAsk(command, it)
                    }
                }
            }
            asked.fold(
                { full ->
                    // A tell from the saga's turn throws when the account's mailbox is full, rather than waiting.
                    counter("bank.transfer.legs_deferred").increment()
                    logDebug("transfer ${scope.id}: ${full.message}; the leg goes again in $legTimeout")
                    pause()
                },
                { answer ->
                    answer.fold(
                        { pause() },
                        { return it },
                    )
                },
            )
        }
    }

    /** A close lost only leaves a leg open (spec 0013), so a failure here does not fail the transfer. */
    private fun closeBoth() {
        listOf(input.from, input.to).forEach { account ->
            Either.catchOrThrow<IllegalStateException, Either<AskFailure, AccountAnswer>> {
                accounts.entity(account.value).ask<AccountMessage, AccountAnswer>(legTimeout) {
                    AccountAsk(AccountCommand.Close(transfer), it)
                }
            }
        }
    }

    private fun pause() {
        clock.get().sleep(legTimeout.coerceAtLeast(1.milliseconds))
    }
}

/** The transfer's own events, on [Kinds.TRANSFER], beside the saga's step journal. */
private class TransferLog(private val journal: Journal, private val id: TransferId) {
    private val events = journal.events(PersistenceId(Kinds.TRANSFER, id.value), TransferEvents).toMutableList()

    fun requested(): TransferEvent.Requested? = events.filterIsInstance<TransferEvent.Requested>().firstOrNull()

    fun screened(): TransferEvent.Screened? = events.filterIsInstance<TransferEvent.Screened>().firstOrNull()

    inline fun <reified T : TransferEvent> has(): Boolean = events.any { it is T }

    fun moving(): Transfer.Moving {
        val folded = events.fold<TransferEvent, Transfer>(Transfer.Unrequested, Transfer::evolve)
        return folded as? Transfer.Moving ?: error("transfer ${id.value} has no request")
    }

    fun append(event: TransferEvent) {
        val last = journal.read(PersistenceId(Kinds.TRANSFER, id.value)).lastOrNull()?.sequence ?: 0L
        journal.append(PersistenceId(Kinds.TRANSFER, id.value), last, listOf(TransferEvents.encode(event))).fold(
            { conflict -> error("transfer ${id.value} was written elsewhere: $conflict") },
            { events += event },
        )
        if (event !is TransferEvent.Requested) {
            counter("bank.transfer.steps", "event" to event::class.simpleName.orEmpty()).increment()
        }
    }
}

private object Inputs : StateCodec<TransferInput> {
    override fun encode(state: TransferInput): ByteArray = bytes {
        writeUTF(state.from.value)
        writeUTF(state.to.value)
        writeMoney(state.amount)
        val policy = state.whenUnanswered
        writeBoolean(policy != null)
        if (policy != null) writeUTF(policy.name)
    }

    override fun decode(bytes: ByteArray): TransferInput = bytes.read {
        TransferInput(
            AccountId(readUTF()),
            AccountId(readUTF()),
            readMoney(),
            if (readBoolean()) WhenUnanswered.valueOf(readUTF()) else null,
        )
    }
}

private object Halts : StateCodec<TransferHalt> {
    override fun encode(state: TransferHalt): ByteArray = bytes { writeByte(state.ordinal) }

    override fun decode(bytes: ByteArray): TransferHalt = bytes.read { TransferHalt.entries[readUnsignedByte()] }
}

private object Views : StateCodec<TransferView> {
    override fun encode(state: TransferView): ByteArray = bytes {
        writeUTF(state.id)
        writeUTF(state.from)
        writeUTF(state.to)
        writeMoney(state.amount)
        writeUTF(state.status.name)
        writeBoolean(state.reason != null)
        state.reason?.let { writeUTF(it) }
    }

    override fun decode(bytes: ByteArray): TransferView = bytes.read {
        TransferView(
            readUTF(),
            readUTF(),
            readUTF(),
            readMoney(),
            TransferStatus.valueOf(readUTF()),
            if (readBoolean()) readUTF() else null,
        )
    }
}

private object Longs : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = bytes { writeLong(state) }

    override fun decode(bytes: ByteArray): Long = bytes.read { readLong() }
}

private object Units : StateCodec<Unit> {
    override fun encode(state: Unit): ByteArray = ByteArray(0)

    override fun decode(bytes: ByteArray) = Unit
}

private fun DataOutputStream.writeMoney(money: Money) {
    writeUTF(money.amount.toPlainString())
    writeUTF(money.currency.code)
    writeInt(money.currency.exponent)
}

private fun DataInputStream.readMoney(): Money {
    val amount = BigDecimal(readUTF())
    val currency = Currency(readUTF(), readInt())
    return Money(amount.setScale(currency.exponent), currency)
}

private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
    val stream = ByteArrayOutputStream()
    DataOutputStream(stream).use { it.write() }
    return stream.toByteArray()
}

private fun <A> ByteArray.read(read: DataInputStream.() -> A): A = DataInputStream(ByteArrayInputStream(this)).use(read)

private inline fun Either<TransferError.TransferIdReused, Unit>.onLeft(
    handle: (TransferError.TransferIdReused) -> Nothing,
) {
    if (this is Either.Left) handle(value)
}
