package bank.app.memory

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import bank.app.AccountsById
import bank.app.NoDecision
import bank.app.Screener
import bank.app.Screening
import bank.app.account
import bank.app.gbp
import bank.app.transfer
import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.Step
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.TransferView
import bank.domain.WhenUnanswered
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import bank.protocol.Nudge
import bank.protocol.TransferAsk
import bank.protocol.TransferEvents
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.TestActors
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fixedClock
import io.github.matthewjones372.lark.metrics
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A screened transfer (spec 0018) in memory (bank spec 0028): the saga asks the check before it debits, on a thread of
 * its own, and goes on from its decision, from the bank's own when the check is slow or has none, and only once.
 */
class ScreeningSpec {
    private val approved = ScreeningDecision(ScreeningOutcome.Approved, null, null, "nothing matched")
    private val declined = ScreeningDecision(ScreeningOutcome.Declined, "big-first-payment", 3, "first payment over 50")

    @Test
    fun `an approved transfer is screened once, then debited and credited, and its start is answered completed`() =
        screened(Check.answering(approved.right())) {
            start()

            awaitSettled()
            transferEvents().map { it::class.simpleName } shouldBe
                listOf("Requested", "Screened", "DestinationCredited")
            screenedAs() shouldBe
                TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "nothing matched", true, 0)
            answered().status shouldBe TransferStatus.Completed
            balances() shouldBe (gbp(7_00) to gbp(3_00))
            check.asked.get() shouldBe 1
            sim.pendingTimers shouldBe 0
        }

    @Test
    fun `a declined transfer is rejected with the rule that declined it, and nothing moves`() =
        screened(Check.answering(declined.right())) {
            start()

            awaitSettled()
            transferEvents().map { it::class.simpleName } shouldBe listOf("Requested", "Screened")
            answered().status shouldBe TransferStatus.Rejected
            answered().reason shouldBe "Declined by big-first-payment, version 3"
            balances() shouldBe (gbp(10_00) to gbp(0))
            accountEvents(AccountId("ada")).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 0
            sim.pendingTimers shouldBe 0
        }

    @Test
    fun `a check with no decision is decided at once by the bank's policy, without waiting out the timeout`() {
        screened(Check.answering(NoDecision("the check is down").left()), whenUnanswered = WhenUnanswered.Approve) {
            start()

            awaitSettled()
            // Decided by the bank, not the check: answered false, and no evidence of the check's.
            screenedAs() shouldBe TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "", false, 0)
            answered().status shouldBe TransferStatus.Completed
            check.asked.get() shouldBe 1
            // Its timer went with the decision: nothing is left to fire.
            sim.pendingTimers shouldBe 0
        }
    }

    @Test
    fun `a check slower than the timeout is decided by the policy, and its late answer changes nothing`() {
        screened(Check.held(approved.right()), whenUnanswered = WhenUnanswered.Decline) {
            start()
            transferEvents().map { it::class.simpleName } shouldBe listOf("Requested")

            sim.advance(TIMEOUT)
            answered().status shouldBe TransferStatus.Rejected
            answered().reason shouldBe "Declined: the check did not answer in time"
            screenedAs() shouldBe TransferEvent.Screened(ScreeningOutcome.Declined, null, null, "", false, 0)
            counted.total("bank.transfer.screened_unanswered") shouldBe 1.0
            counted.total("bank.transfer.steps", "event" to "Screened") shouldBe 1.0

            // The check answers at last; the saga has moved past screening, so it writes nothing more.
            check.release()
            check.awaitAnswered()
            transferEvents().map { it::class.simpleName } shouldBe listOf("Requested", "Screened")
            balances() shouldBe (gbp(10_00) to gbp(0))
        }
    }

    @Test
    fun `a check that answers before the timeout is waited for, and the timer then fires on nothing`() {
        screened(Check.held(approved.right()), whenUnanswered = WhenUnanswered.Decline) {
            start()
            // The start and the saga's own nudge both drive it while it waits: the check is asked once.
            start()
            check.release()

            awaitSettled()
            // Both starts waited, and both are answered as it settled.
            everyAnswer().map { it.status } shouldBe listOf(TransferStatus.Completed, TransferStatus.Completed)
            check.asked.get() shouldBe 1
            screenedAs().answered shouldBe true
            // The screening timer was cancelled with the answer: firing it now writes nothing, and counts nothing.
            sim.advance(TIMEOUT)
            transferEvents().filterIsInstance<TransferEvent.Screened>() shouldHaveSize 1
            counted.total("bank.transfer.screened_unanswered") shouldBe 0.0

            // Nudged, as the sweeper might, the two that waited are not answered again; started once more, it is
            // answered at once.
            nudge()
            everyAnswer() shouldHaveSize 2
            start()
            everyAnswer() shouldHaveSize 3
        }
    }

    /** What the bank asks: answers [decision], at once or once the test lets it, and counts how often it is asked. */
    private class Check(private val decision: Either<NoDecision, ScreeningDecision>, held: Boolean) : Screener {
        val asked = AtomicInteger()
        private val go = CountDownLatch(if (held) 1 else 0)
        private val done = CountDownLatch(1)

        override fun screen(id: TransferId, proposed: Step.Screen): Either<NoDecision, ScreeningDecision> {
            asked.incrementAndGet()
            go.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return decision.also { done.countDown() }
        }

        fun release() = go.countDown()

        /** The answer is given, and the saga's step for it, on the check's thread, has had time to run. */
        fun awaitAnswered() {
            done.await(WAIT_SECONDS, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MILLIS)
        }

        companion object {
            fun answering(decision: Either<NoDecision, ScreeningDecision>) = Check(decision, held = false)

            fun held(decision: Either<NoDecision, ScreeningDecision>) = Check(decision, held = true)
        }
    }

    /** One screened saga between two accounts, on test actors, with what it was answered and what it counted. */
    private class Screened(
        val sim: TestActors,
        val check: Check,
        val counted: Counted,
        whenUnanswered: WhenUnanswered,
    ) {
        private val t1 = TransferId("t-1")
        private val ada = AccountId("ada")
        private val bob = AccountId("bob")
        private val accounts = HashMap<String, ActorRef<AccountMessage>>()
        private val byId = AccountsById { id ->
            accounts.getOrPut(id) { sim.spawn("account-$id", account(AccountId(id), null)) }
        }
        private val saga =
            sim.spawn("transfer-t-1", transfer(t1, byId, LEG, Screening.On(check, TIMEOUT, whenUnanswered)))
        private val answers = InMemoryBank.Answers(t1)

        init {
            open(ada, gbp(10_00))
            open(bob, gbp(0))
        }

        private fun open(id: AccountId, initial: Money) =
            byId.entity(id.value).tell(AccountAsk(AccountCommand.Open(id.value, initial, "open:$id"), Ignored))

        fun start() = saga.tell(TransferAsk(TransferRequest.Start(ada, bob, gbp(3_00)), answers))

        fun nudge() = saga.tell(Nudge)

        fun transferEvents(): List<TransferEvent> =
            sim.journal.events(PersistenceId(Kinds.TRANSFER, t1.value), TransferEvents)

        fun accountEvents(id: AccountId): List<AccountEvent> =
            sim.journal.events(PersistenceId(Kinds.ACCOUNT, id.value), AccountEvents)

        /** The saga's screening, without its time: a step on the check's thread is stamped by the system clock. */
        fun screenedAs() = transferEvents().filterIsInstance<TransferEvent.Screened>().single().copy(atMillis = 0)

        /** The one answer the start was given, once it settled. */
        fun answered() = everyAnswer().single()

        fun everyAnswer() = answers.answers.map { it.shouldBeInstanceOf<Either.Right<TransferView>>().value }

        /** What each account holds, from its journal: Ada's, then Bob's. */
        fun balances() = balance(ada) to balance(bob)

        private fun balance(id: AccountId) = accountEvents(id).fold(gbp(0)) { sum, event ->
            when (event) {
                is AccountEvent.Opened -> sum + event.initial
                is AccountEvent.Debited -> sum - event.amount
                is AccountEvent.Credited -> sum + event.amount
                is AccountEvent.Refunded -> sum + event.amount
                else -> sum
            }
        }

        /** Until the saga has settled: the check answers on a thread of its own, which steps the saga there. */
        fun awaitSettled() {
            check.awaitAnswered()
            val until = System.nanoTime() + WAIT_SECONDS * 1_000_000_000
            while (answers.answers.isEmpty() && System.nanoTime() < until) Thread.sleep(SETTLE_MILLIS)
        }
    }

    /** Where an account's answer to an opening goes: nowhere, since opening is the spec's set-up. */
    private object Ignored : Reply<AccountAnswer> {
        override val address = Address("test", "/temp/ignored", 1)

        override fun invoke(answer: AccountAnswer) = Unit
    }

    private fun screened(
        check: Check,
        whenUnanswered: WhenUnanswered = WhenUnanswered.Decline,
        block: Screened.() -> Unit,
    ) {
        val counted = Counted()
        metrics.locally(counted) {
            clock.locally(fixedClock(NOON)) {
                testActors(clock = fixedClock(NOON)) { Screened(this, check, counted, whenUnanswered).block() }
            }
        }
    }

    private companion object {
        val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")
        val LEG: Duration = 1.seconds
        val TIMEOUT: Duration = 2.seconds
        const val WAIT_SECONDS = 2L
        const val SETTLE_MILLIS = 20L
    }
}
