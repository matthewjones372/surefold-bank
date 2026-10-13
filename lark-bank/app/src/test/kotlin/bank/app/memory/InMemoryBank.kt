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
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.TransferAnswer
import bank.protocol.TransferAsk
import bank.protocol.TransferEvents
import bank.protocol.TransferMessage
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Remembered
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.TestActor
import io.github.matthewjones372.lark.actor.TestActors
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.kotest.assertions.arrow.core.shouldBeRight
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Noon UTC on a fixed day, so no spec's daily limit (bank spec 0027) crosses midnight while it runs. */
private val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")

/**
 * The bank's shipped `account(...)` and `transfer(...)` behaviours as Lark test actors, stepped on the test's thread
 * with an in-memory journal: no cluster, no Postgres, no threads (bank spec 0028).
 */
fun inMemory(legTimeout: Duration = 1.seconds, block: InMemoryBank.() -> Unit) =
    // Events are stamped from lark's clock, bound here for the test's thread.
    clock.locally(fixedClock(NOON)) { testActors(clock = fixedClock(NOON)) { InMemoryBank(this, legTimeout).block() } }

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

    /** Moves the test's time on, firing each saga's leg timer that falls due on the way. */
    fun advance(by: Duration = legTimeout) = sim.advance(by)

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
internal class Actors(private val sim: TestActors, private val legTimeout: Duration) {
    private val accounts = HashMap<String, TestActor<AccountMessage, Remembered<Account>, JournalConflict>>()
    private val sagas = HashMap<String, TestActor<TransferMessage, Remembered<Transfer>, JournalConflict>>()

    val network = Network { id -> accountOf(id) }

    fun accountOf(id: String) =
        accounts.getOrPut(id) { sim.spawn("account-$id", account(AccountId(id), snapshots = null)) }

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

/** An account or a settled saga has answered before the tell returns; only a saga still moving waits this out. */
private val ASK = 1.milliseconds

/** Each event's kind, for a spec to say which were written and in what order. */
internal fun List<Any>.kinds(): List<String?> = map { it::class.simpleName }
