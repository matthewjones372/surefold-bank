package bank.app.memory

import bank.app.AccountsById
import bank.app.DurableTransfers
import bank.app.Screening
import bank.app.ShardedBank
import bank.app.TransferMoves
import bank.app.account
import bank.app.transferSaga
import bank.domain.Account
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Bank
import bank.domain.Money
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.evolve
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.TransferEvents
import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.events
import dev.surefold.actor.journal
import dev.surefold.actor.spawn
import dev.surefold.assert.shouldBeRight
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.onLeft
import dev.surefold.fixedClock
import dev.surefold.scope
import io.opentelemetry.api.OpenTelemetry
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Noon UTC on a fixed day, so no spec's daily limit (bank spec 0027) crosses midnight while it runs. */
private val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")

/**
 * The bank's account actors and its durable transfer saga, in one scope, with an in-memory journal: no cluster and
 * no Postgres (bank spec 0028). A transfer runs on the calling thread, and asks the account actors, which run on
 * the scope's own.
 */
fun inMemory(
    legTimeout: Duration = 1.seconds,
    closeAfter: Duration? = null,
    journal: Journal = InMemoryJournal(),
    screening: Screening = Screening.Off,
    block: InMemoryBank.() -> Unit,
) {
    // Unit, so a suite test can end on the block. A spec that wants a value back asks [inMemoryOf].
    inMemoryOf(legTimeout, closeAfter, journal, screening, block)
}

/** As [inMemory], answering what [block] does. With [closeAfter], each account holds a settled transfer's close. */
fun <A> inMemoryOf(
    legTimeout: Duration = 1.seconds,
    closeAfter: Duration? = null,
    journal: Journal = InMemoryJournal(),
    screening: Screening = Screening.Off,
    block: InMemoryBank.() -> A,
): A = clock.locally(fixedClock(NOON)) {
    scope {
        val here = this
        journal(journal)
        val accounts = MemoryAccounts(here, closeAfter)
        val tracer = OpenTelemetry.noop().getTracer("surefold-bank-test")
        val saga = transferSaga(journal, accounts, legTimeout, screening, tracer)
        val moves = DurableTransfers(journal, screening, askTimeout = 2.seconds, saga)
        InMemoryBank(journal, accounts, moves).block()
    }
}

/** The [Bank] port as it ships, [ShardedBank], over account actors and a durable saga instead of a cluster. */
class InMemoryBank internal constructor(
    private val journal: Journal,
    private val accounts: MemoryAccounts,
    moves: TransferMoves,
) : Bank by ShardedBank(
    accounts = accounts::entity,
    transfers = moves,
    askTimeout = 5.seconds,
    transferWait = 30.seconds,
) {
    fun transferEvents(id: TransferId): List<TransferEvent> =
        journal.events(PersistenceId(Kinds.TRANSFER, id.value), TransferEvents)

    fun accountEvents(id: AccountId): List<AccountEvent> =
        journal.events(PersistenceId(Kinds.ACCOUNT, id.value), AccountEvents)

    /** The account as its journal folds, which is what its actor holds once a command has been answered. */
    fun accountState(id: AccountId): Account =
        accountEvents(id).fold<AccountEvent, Account>(Account.Unopened) { account, event -> account.evolve(event) }

    /** [id] opened for an owner of the same name, or the spec fails. */
    fun opened(id: AccountId, initial: Money, dailyLimit: Money? = null) =
        open(id, id.value, initial, "open:$id", dailyLimit).shouldBeRight()

    /** What [id]'s account holds now, or the spec fails. */
    fun balanceOf(id: AccountId): Money = balance(id).shouldBeRight().balance
}

/** Each account spawned on first use, as sharding starts one. */
internal class MemoryAccounts(private val scope: Scope<Nothing>, private val closeAfter: Duration?) : AccountsById {
    private class Slot(val ref: Promise<Throwable, ActorRef<AccountMessage>> = Promise("bank.memory.account"))

    private val accounts = Shared(emptyMap<String, Slot>(), "bank.memory.accounts")

    override fun entity(id: String): ActorRef<AccountMessage> {
        val slot = Slot()
        val won = accounts.modify { held ->
            val existing = held[id]
            if (existing != null) held to existing else (held + (id to slot)) to slot
        }
        if (won === slot) {
            Either.catch {
                val spawned = scope.spawn(
                    "account-$id",
                    account(AccountId(id), snapshots = null, closeAfter = closeAfter),
                )
                won.ref.succeed(spawned)
            }.onLeft { failed ->
                accounts.update { held -> if (held[id] === slot) held - id else held }
                won.ref.fail(failed)
            }
        }
        return when (val ended = won.ref.await()) {
            is Either.Right -> ended.value
            is Either.Left -> throw ended.value
        }
    }
}

/** Each event's kind, for a spec to say which were written and in what order. */
internal fun List<Any>.kinds(): List<String?> = map { it::class.simpleName }
