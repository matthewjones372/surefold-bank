package bank.app.memory

import bank.app.ShardedBank
import bank.app.account
import bank.app.transfer
import bank.domain.Account
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Bank
import bank.domain.Money
import bank.domain.Transfer
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.evolve
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.Nudge
import bank.protocol.TransferAnswer
import bank.protocol.TransferAsk
import bank.protocol.TransferEvents
import bank.protocol.TransferMessage
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Faults
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.Ordering
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.TestActor
import io.github.matthewjones372.lark.actor.TestActors
import io.github.matthewjones372.lark.actor.TraceEntry
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Noon UTC on a fixed day, so no spec's daily limit (bank spec 0027) crosses midnight while it runs. */
private val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")

/**
 * The bank's shipped `account(...)` and `transfer(...)` behaviours as Lark test actors, stepped on the test's thread
 * with an in-memory journal: no cluster, no Postgres, no threads (bank spec 0028). [order] and [faults] are Lark's
 * (Lark spec 0128): a seeded order, and messages between actors lost, doubled, delayed or crashing their receiver.
 */
fun inMemory(
    legTimeout: Duration = 1.seconds,
    order: Ordering = Ordering.Told,
    faults: Faults = Faults(),
    block: InMemoryBank.() -> Unit,
) {
    // Unit, so a spec written `= inMemory { ... }` is a void @Test, which JUnit runs: one returning its last value
    // is skipped. A spec that wants a value back asks [inMemoryOf].
    inMemoryOf(legTimeout, order, faults, block = block)
}

/**
 * As [inMemory], answering what [block] does: for a spec that runs many and looks at each. With [closeAfter], each
 * account holds a settled transfer's close for its next write, as a node does (bank spec 0031).
 */
fun <A> inMemoryOf(
    legTimeout: Duration = 1.seconds,
    order: Ordering = Ordering.Told,
    faults: Faults = Faults(),
    closeAfter: Duration? = null,
    block: InMemoryBank.() -> A,
): A = // Events are stamped from lark's clock, bound here for the test's thread.
    clock.locally(fixedClock(NOON)) {
        testActors(clock = fixedClock(NOON), order = order, faults = faults) {
            InMemoryBank(this, legTimeout, Actors(this, legTimeout, closeAfter)).block()
        }
    }

/** The [Bank] port as it ships, [ShardedBank], over account and transfer test actors instead of sharded entities. */
class InMemoryBank internal constructor(
    private val sim: TestActors,
    /** How long a saga waits for a leg's answer before sending it again, in the test's own time. */
    val legTimeout: Duration,
    private val actors: Actors = Actors(sim, legTimeout),
) : Bank by ShardedBank(
    accounts = { id -> Plain(actors.accountOf(id)) },
    transfers = { id -> Plain(actors.sagaOf(id)) },
    askTimeout = ASK,
    transferWait = ASK,
) {

    /** The way between every saga and the accounts, for a spec to drop, repeat or hold legs on. */
    val network: Network get() = actors.network

    /** A start told straight to the saga, with a reply that keeps every answer: one still moving has none yet. */
    fun start(id: TransferId, from: AccountId, to: AccountId, amount: Money): Answers =
        Answers(id).also { actors.sagaOf(id.value).tell(TransferAsk(TransferRequest.Start(from, to, amount), it)) }

    /** The saga crashes as its node's death would: what it held only in memory goes, and its journal stays. */
    fun crashSaga(id: TransferId) = sim.crash(actors.sagaOf(id.value))

    /** A nudge told straight to the saga, as the sweeper sends one to a transfer it finds stuck. */
    fun nudge(id: TransferId) = actors.sagaOf(id.value).tell(Nudge)

    /** The saga crashed as its node's death would: its timers gone, and its next message recovers it. */
    fun crash(id: TransferId) = sim.crash(actors.sagaOf(id.value))

    /** A status asked straight of the saga, whose answer nobody hears: the message a recovery waits for. */
    fun poke(id: TransferId) = actors.sagaOf(id.value).tell(TransferAsk(TransferRequest.Status, Unheard))

    /** Runs every message waiting, and what they tell, to idle, without moving the test's time on. */
    fun settle() = sim.settle()

    /** Every delivery, loss, duplicate, delay and crash so far, to read when a seed fails. */
    val trace: List<TraceEntry> get() = sim.trace

    /** Moves the test's time on, firing each saga's leg timer that falls due on the way. */
    fun advance(by: Duration = legTimeout) = sim.advance(by)

    /**
     * Runs messages and timers to idle, then asks each saga still moving how far it has got, as a caller polling
     * would, until none is: a crashed saga's timers went with it, so only a message starts it again.
     */
    fun settleAll(rounds: Int = 50) {
        repeat(rounds) {
            sim.settle()
            val moving = actors.sagaIds.map(::TransferId).filterNot(::settled)
            if (moving.isEmpty()) return
            moving.forEach { actors.sagaOf(it.value).tell(TransferAsk(TransferRequest.Status, Unheard)) }
        }
        sim.settle()
    }

    private fun settled(id: TransferId): Boolean =
        (transferEvents(id).fold<TransferEvent, Transfer>(Transfer.Unrequested, Transfer::evolve) as? Transfer.Moving)
            ?.settled == true

    fun transferEvents(id: TransferId): List<TransferEvent> =
        sim.journal.events(PersistenceId(Kinds.TRANSFER, id.value), TransferEvents)

    fun accountEvents(id: AccountId): List<AccountEvent> =
        sim.journal.events(PersistenceId(Kinds.ACCOUNT, id.value), AccountEvents)

    /** The account as its actor holds it now. */
    fun accountState(id: AccountId): Account = actors.accountOf(id.value).state.value

    /** [id] opened for an owner of the same name, or the spec fails. */
    fun opened(id: AccountId, initial: Money, dailyLimit: Money? = null) =
        open(id, id.value, initial, "open:$id", dailyLimit).shouldBeRight()

    /** What [id]'s account holds now, or the spec fails. */
    fun balanceOf(id: AccountId): Money = balance(id).shouldBeRight().balance

    /** What one start was answered: nothing while the saga is still moving, then once when it settles or refuses. */
    class Answers(id: TransferId) : Reply<TransferAnswer> {
        override val address = Address("test", "/temp/start-${id.value}", 1)
        val answers = mutableListOf<TransferAnswer>()

        override fun invoke(answer: TransferAnswer) {
            answers += answer
        }
    }
}

/** Each entity's test actor, spawned on first use as sharding starts one, and every saga on the one [network]. */
internal class Actors(
    private val sim: TestActors,
    private val legTimeout: Duration,
    private val closeAfter: Duration? = null,
) {
    private val accounts = HashMap<String, TestActor<AccountMessage, Remembered<Account>, JournalConflict>>()
    private val sagas = HashMap<String, TestActor<TransferMessage, Remembered<Transfer>, JournalConflict>>()

    val network = Network { id -> accountOf(id) }

    val sagaIds: Set<String> get() = sagas.keys.toSet()

    fun accountOf(id: String) = accounts.getOrPut(id) {
        sim.spawn("account-$id", account(AccountId(id), snapshots = null, closeAfter = closeAfter))
    }

    fun sagaOf(id: String) =
        sagas.getOrPut(id) { sim.spawn("transfer-$id", transfer(TransferId(id), network, legTimeout)) }
}

/**
 * A test actor seen only as an [ActorRef], so Lark's `ask` takes a node's path: an ask nobody answers yet times out,
 * as one to a saga still moving does on a node, where a test actor's own ask fails. It all still runs on this thread.
 */
private class Plain<M : Any>(private val actor: ActorRef<M>) : ActorRef<M> {
    override val address: Address get() = actor.address

    override fun tell(message: M) = actor.tell(message)
}

/** Where a poke's answer goes: the poke only wakes the saga. */
private object Unheard : Reply<TransferAnswer> {
    override val address = Address("test", "/temp/unheard", 1)

    override fun invoke(answer: TransferAnswer) = Unit
}

/** An account or a settled saga has answered before the tell returns; only a saga still moving waits this out. */
private val ASK = 1.milliseconds

/** Each event's kind, for a spec to say which were written and in what order. */
internal fun List<Any>.kinds(): List<String?> = map { it::class.simpleName }
