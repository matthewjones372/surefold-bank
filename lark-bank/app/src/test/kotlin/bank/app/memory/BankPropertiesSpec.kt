package bank.app.memory

import bank.app.gbp
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.github.matthewjones372.lark.actor.Faults
import io.github.matthewjones372.lark.actor.Ordering
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The saga's properties over seeded schedules (bank spec 0028's `bank-properties`): Lark's test runtime picks the order
 * of every message from a seed, and from the same seed drops, repeats and delays messages between actors, and crashes
 * accounts and sagas so they recover from their journals. Whatever it does, money is conserved, every transfer
 * settles, each leg is applied once, a refund follows only a refused credit, and the daily limit holds.
 *
 * A failing seed is named in the failure; `SEED=<n>` runs that one alone, and replays it exactly. `SEEDS=<n>` runs the
 * first n, for a longer run than a check's.
 */
class BankPropertiesSpec {

    @Test
    fun `money is conserved, every transfer settles, and each leg is applied once, whatever the schedule`() {
        val only = System.getenv("SEED")?.toLong()
        val seeds = only?.let { listOf(it) } ?: (1L..(System.getenv("SEEDS")?.toLong() ?: SEEDS))
        val opened = ACCOUNTS.values.fold(gbp(0)) { sum, opening -> sum + opening.initial }
        seeds.forEach { seed ->
            // Money is conserved: what the accounts hold once it has all settled is what they opened with.
            withClue("seed $seed (SEED=$seed replays it)") { scenario(seed) shouldBe opened }
        }
    }

    /**
     * One seeded run: accounts, transfers among them chosen from the seed, and the faults; every other property is
     * checked here, and what the accounts hold at the end is returned.
     */
    private fun scenario(seed: Long): Money {
        val pick = Random(seed)
        val unkind = Faults(drop = 0.1, duplicate = 0.1, delay = 0.1, maxDelay = 3.seconds, crash = 0.05)
        return clock.locally(fixedClock(NOON)) {
            testActors(clock = fixedClock(NOON), order = Ordering.seeded(seed), faults = unkind) {
                val bank = InMemoryBank(this, LEG)
                ACCOUNTS.forEach { (id, opening) -> bank.opened(id, opening.initial, opening.limit) }

                val asked = List(TRANSFERS) { n ->
                    val from = ACCOUNTS.keys.random(pick)
                    // Now and then to an account nobody opened, so its credit is refused and the debit refunded.
                    val to = if (pick.nextInt(8) == 0) NOWHERE else (ACCOUNTS.keys - from).random(pick)
                    Asked(TransferId("t-$n"), from, to, gbp(pick.nextLong(1_00, 40_00)))
                }
                asked.forEach { transfer ->
                    bank.start(transfer)
                    // Now and then started again, as a client that heard nothing would: still one transfer.
                    if (pick.nextInt(5) == 0) bank.start(transfer)
                }
                settled(bank, asked)

                bank.properties(asked)
                ACCOUNTS.keys.fold(gbp(0)) { sum, id -> sum + bank.balanceOf(id) }
            }
        }
    }

    private fun InMemoryBank.start(transfer: Asked) = start(transfer.id, transfer.from, transfer.to, transfer.amount)

    /**
     * Runs the schedule to idle, then starts each transfer still moving again, as its client or the sweeper would on a
     * node: a saga that crashed with nothing more told to it waits for that, as one on a downed node does.
     */
    private fun io.github.matthewjones372.lark.actor.TestActors.settled(bank: InMemoryBank, asked: List<Asked>) {
        repeat(SWEEPS) {
            settle()
            val moving = asked.filter { transfer -> bank.statusOf(transfer.id) !in SETTLED }
            if (moving.isEmpty()) return
            moving.forEach { transfer -> bank.start(transfer) }
        }
        settle()
    }

    private fun InMemoryBank.properties(asked: List<Asked>) {
        // Every transfer settles: completed, rejected, or refunded.
        asked.forEach { withClue(it.id.value) { statusOf(it.id) shouldBeIn SETTLED } }
        ACCOUNTS.keys.forEach { id ->
            legsOnce(id, asked)
            ACCOUNTS.getValue(id).limit?.let { limit -> withinLimit(id, limit) }
        }
        asked.forEach { transfer ->
            withClue(transfer.id.value) {
                refundedOnlyAfterRefusal(transfer.id)
                agreesWithAccounts(transfer)
            }
        }
    }

    /** Each leg is applied once (spec 0013), however often it arrived. */
    private fun InMemoryBank.legsOnce(id: AccountId, asked: List<Asked>) {
        val events = accountEvents(id)
        asked.forEach { transfer ->
            withClue("${transfer.id.value} on ${id.value}") {
                events.count { it is AccountEvent.Debited && it.transfer == transfer.id } shouldBeLessThanOrEqual 1
                events.count { it is AccountEvent.Credited && it.transfer == transfer.id } shouldBeLessThanOrEqual 1
                events.count { it is AccountEvent.Refunded && it.transfer == transfer.id } shouldBeLessThanOrEqual 1
            }
        }
    }

    /** The daily limit holds (spec 0027): paid out today, less what was refunded, is within it. */
    private fun InMemoryBank.withinLimit(id: AccountId, limit: Money) {
        val events = accountEvents(id)
        val debited = events.filterIsInstance<AccountEvent.Debited>().fold(gbp(0)) { sum, debit -> sum + debit.amount }
        val refunded = events.filterIsInstance<AccountEvent.Refunded>().fold(gbp(0)) { sum, back -> sum + back.amount }
        withClue("${id.value}'s daily limit") { (debited - refunded <= limit) shouldBe true }
    }

    /** A refund only ever follows a refused credit, and a refused credit is always refunded. */
    private fun InMemoryBank.refundedOnlyAfterRefusal(id: TransferId) {
        val kinds = transferEvents(id).map { it::class }
        val refused = kinds.indexOf(TransferEvent.CreditRefused::class)
        val refunded = kinds.indexOf(TransferEvent.SourceRefunded::class)
        (refunded >= 0) shouldBe (refused >= 0)
        refused shouldBeLessThanOrEqual refunded
    }

    /** What the accounts did agrees with how the saga says it ended. */
    private fun InMemoryBank.agreesWithAccounts(transfer: Asked) {
        val id = transfer.id
        val debited = accountEvents(transfer.from).any { it is AccountEvent.Debited && it.transfer == id }
        val credited = transfer.to != NOWHERE &&
            accountEvents(transfer.to).any { it is AccountEvent.Credited && it.transfer == id }
        val refunded = accountEvents(transfer.from).any { it is AccountEvent.Refunded && it.transfer == id }
        listOf(debited, credited, refunded) shouldBe when (statusOf(id)) {
            TransferStatus.Completed -> listOf(true, true, false)
            TransferStatus.Refunded -> listOf(true, false, true)
            else -> listOf(false, false, false)
        }
    }

    private fun InMemoryBank.statusOf(id: TransferId): TransferStatus = transferStatus(id).shouldBeRight().status

    /** One transfer the run asks for. */
    private data class Asked(val id: TransferId, val from: AccountId, val to: AccountId, val amount: Money)

    /** What an account opens with: its money, and a daily limit of its own or the bank's. */
    private data class Opening(val initial: Money, val limit: Money? = null)

    private companion object {
        /** Seeds a check runs; spec 0028 settles on 200 a check. */
        const val SEEDS = 200L
        const val TRANSFERS = 12

        /** How often the sweeper nudges what is still moving before a run is called stuck. */
        const val SWEEPS = 20

        val LEG = 1.seconds
        val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")
        val NOWHERE = AccountId("nowhere")

        // Small enough that some transfers are refused for want of money, or by ada's limit.
        val ACCOUNTS = mapOf(
            AccountId("ada") to Opening(gbp(100_00), limit = gbp(60_00)),
            AccountId("bob") to Opening(gbp(50_00)),
            AccountId("cy") to Opening(gbp(20_00)),
            AccountId("dee") to Opening(gbp(0)),
        )
        val SETTLED = setOf(TransferStatus.Completed, TransferStatus.Rejected, TransferStatus.Refunded)
    }
}
