package bank.app.memory

import bank.app.gbp
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.Transfer
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.evolve
import io.github.matthewjones372.lark.actor.Faults
import io.github.matthewjones372.lark.actor.Ordering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Lost, doubled and late legs and answers, and crashed accounts and sagas, all drawn from one seed. */
private val UNKIND = Faults(drop = 0.1, duplicate = 0.1, delay = 0.1, maxDelay = 2.seconds, crash = 0.03)

private val ada = AccountId("ada")
private val bob = AccountId("bob")
private val cy = AccountId("cy")
private val everyone = listOf(ada, bob, cy)

/** Ada's daily limit (bank spec 0027), which the transfers below can reach. */
private val ADA_LIMIT = gbp(60_00)
private val OPENED = gbp(150_00)

private class Move(val id: TransferId, val from: AccountId, val to: AccountId, val amount: Money)

/** Eight transfers between the three accounts, each way and size drawn from [seed]. */
private fun moves(seed: Long): List<Move> = Random(seed).let { random ->
    (1..8).map { n ->
        val (from, to) = everyone.shuffled(random).take(2)
        Move(TransferId("t-$n"), from, to, gbp(random.nextLong(1_00, 40_00)))
    }
}

private fun List<Money>.total(): Money = fold(gbp(0)) { sum, amount -> sum + amount }

/**
 * Bank spec 0028's properties, each over many seeded schedules of the shipped account and transfer behaviours. A
 * failure names its seeds, and `inMemoryOf(order = Ordering.seeded(seed), faults = UNKIND)` replays one exactly.
 */
class BankPropertiesSpec {
    @Test
    fun `across 1,000 unkind schedules money is conserved, every transfer settles and each leg applies once`() {
        val broken = (1L..1_000L)
            .associateWith { seed -> inMemoryOf(order = Ordering.seeded(seed), faults = UNKIND) { brokenUnder(seed) } }
            .filterValues { it.isNotEmpty() }
        broken shouldBe emptyMap()
    }

    /** The properties [seed]'s schedule broke, by name; none when it kept them all. */
    private fun InMemoryBank.brokenUnder(seed: Long): List<String> {
        opened(ada, gbp(100_00), dailyLimit = ADA_LIMIT)
        opened(bob, gbp(50_00))
        opened(cy, gbp(0))
        val moves = moves(seed)
        moves.forEach { start(it.id, it.from, it.to, it.amount) }
        settleAll()

        val sagas = moves.associate { it.id to transferEvents(it.id) }
        val status = sagas.mapValues { (_, events) ->
            (events.fold<TransferEvent, Transfer>(Transfer.Unrequested, Transfer::evolve) as? Transfer.Moving)
        }
        val legs = everyone.flatMap(::accountEvents).filter {
            it is AccountEvent.Debited || it is AccountEvent.Credited || it is AccountEvent.Refunded
        }
        fun applied(move: Move) =
            legs.filter { it.reference.endsWith(":${move.id}") }.map { it::class }.sortedBy { it.simpleName }

        return listOfNotNull(
            "every transfer settles".unless(status.values.all { it?.settled == true }),
            "money is conserved".unless(everyone.map(::balanceOf).total() == OPENED),
            "each leg applies once".unless(legs.groupingBy { it.reference }.eachCount().all { it.value == 1 }),
            "a completed transfer debited its source and credited its destination".unless(
                moves.filter { status[it.id]?.status == TransferStatus.Completed }
                    .all { applied(it) == listOf(AccountEvent.Credited::class, AccountEvent.Debited::class) },
            ),
            "a rejected transfer moved nothing".unless(
                moves.filter { status[it.id]?.status == TransferStatus.Rejected }.all { applied(it).isEmpty() },
            ),
            "a refund only follows a refused credit".unless(
                legs.filterIsInstance<AccountEvent.Refunded>()
                    .all { refund -> sagas[refund.transfer].orEmpty().any { it is TransferEvent.CreditRefused } },
            ),
            "ada's daily limit holds across retries".unless(
                accountEvents(ada).filterIsInstance<AccountEvent.Debited>().map { it.amount }.total() <= ADA_LIMIT,
            ),
        )
    }

    private fun String.unless(held: Boolean): String? = takeUnless { held }
}
