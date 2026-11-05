package bank.app.memory

import bank.app.NoDecision
import bank.app.Screener
import bank.app.Screening
import bank.app.gbp
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.WhenUnanswered
import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import dev.surefold.metrics
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A screened transfer (spec 0018): the saga asks the check before it debits and goes on from its decision, or from
 * the bank's own when the check is slow or has none, and only once (spec 0034).
 */
object ScreeningSpec : Suite() {
    init {
        test("an approved transfer is screened once, then debited and credited, and its start is answered completed") {
            holds {
                val check = Check.answering(approved.right())
                inMemory(screening = screening(check)) {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))

                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                    transferEvents(t1).kinds() shouldBe listOf("Requested", "Screened", "DestinationCredited")
                    screenedAs() shouldBe
                        TransferEvent.Screened(ScreeningOutcome.Approved, null, null, "nothing matched", true, 0)
                    balanceOf(ada) shouldBe gbp(7_00)
                    balanceOf(bob) shouldBe gbp(3_00)
                    check.times() shouldBe 1
                }
            }
        }
        test("a declined transfer is rejected with the rule that declined it, and nothing moves") {
            holds {
                inMemory(screening = screening(Check.answering(declined.right()))) {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))

                    val rejected = transfer(t1, ada, bob, gbp(3_00)).shouldBeRight()

                    transferEvents(t1).kinds() shouldBe listOf("Requested", "Screened")
                    rejected.status shouldBe TransferStatus.Rejected
                    rejected.reason shouldBe "Declined by big-first-payment, version 3"
                    balanceOf(ada) shouldBe gbp(10_00)
                    balanceOf(bob) shouldBe gbp(0)
                    accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 0
                }
            }
        }
        test("a check with no decision is decided at once by the bank's policy, without waiting out the timeout") {
            holds {
                val check = Check.answering(NoDecision("the check is down").left())
                val counted = Counted()
                metrics.locally(counted) {
                    inMemory(
                        screening = screening(check, WhenUnanswered.Approve),
                    ) {
                        opened(ada, gbp(10_00))
                        opened(bob, gbp(0))

                        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                        screenedAs() shouldBe TransferEvent.Screened(
                            ScreeningOutcome.Approved,
                            null,
                            null,
                            "",
                            false,
                            0,
                        )
                        check.times() shouldBe 1
                        counted.total("bank.transfer.screened_unanswered") shouldBe 1.0
                    }
                }
            }
        }
        test("a check slower than the timeout is decided by the policy, and its late answer changes nothing") {
            holds {
                val check = Check.held(approved.right())
                val counted = Counted()
                metrics.locally(counted) {
                    inMemory(screening = screening(check, WhenUnanswered.Decline, 50.milliseconds)) {
                        opened(ada, gbp(10_00))
                        opened(bob, gbp(0))

                        val rejected = transfer(t1, ada, bob, gbp(3_00)).shouldBeRight()

                        rejected.status shouldBe TransferStatus.Rejected
                        rejected.reason shouldBe "Declined: the check did not answer in time"
                        screenedAs() shouldBe TransferEvent.Screened(
                            ScreeningOutcome.Declined,
                            null,
                            null,
                            "",
                            false,
                            0,
                        )
                        counted.total("bank.transfer.screened_unanswered") shouldBe 1.0
                        counted.total("bank.transfer.steps", "event" to "Screened") shouldBe 1.0
                        check.release()
                        transferEvents(t1).kinds() shouldBe listOf("Requested", "Screened")
                        balanceOf(ada) shouldBe gbp(10_00)
                        balanceOf(bob) shouldBe gbp(0)
                    }
                }
            }
        }
        test("a check that answers before the timeout is waited for, and the timer then fires on nothing") {
            holds {
                val check = Check.answering(approved.right())
                val counted = Counted()
                metrics.locally(counted) {
                    inMemory(screening = screening(check, WhenUnanswered.Decline, 50.milliseconds)) {
                        opened(ada, gbp(10_00))
                        opened(bob, gbp(0))

                        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed
                        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                        check.times() shouldBe 1
                        screenedAs().answered shouldBe true
                        // The timeout is the wall clock. After it has passed, the decision already written still
                        // stands.
                        CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
                        transferEvents(t1).filterIsInstance<TransferEvent.Screened>() shouldHaveSize 1
                        counted.total("bank.transfer.screened_unanswered") shouldBe 0.0
                        balanceOf(ada) shouldBe gbp(7_00)
                        balanceOf(bob) shouldBe gbp(3_00)
                    }
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")
    private val approved = ScreeningDecision(ScreeningOutcome.Approved, null, null, "nothing matched")
    private val declined = ScreeningDecision(ScreeningOutcome.Declined, "big-first-payment", 3, "first payment over 50")

    private fun InMemoryBank.screenedAs() =
        transferEvents(t1).filterIsInstance<TransferEvent.Screened>().single().copy(atMillis = 0)

    /** What the bank asks: answers [decision], at once or once the test lets it, and counts how often it is asked. */
    private class Check(private val decision: Either<NoDecision, ScreeningDecision>, held: Boolean) : Screener {
        private val asked = Shared(0, "bank.screen.asked")
        private val go = Promise<Nothing, Unit>("bank.screen")

        init {
            if (!held) go.succeed(Unit)
        }

        fun times(): Int = asked.peek()

        override fun screen(id: TransferId, proposed: bank.domain.Step.Screen): Either<NoDecision, ScreeningDecision> {
            asked.update { it + 1 }
            // An interrupt is the saga's timeout. The decision stays unreturned: a slow check must not approve a
            // transfer the policy has already decided. await throws that interrupt, which Either.catch lets through.
            go.await()
            return decision
        }

        fun release() {
            go.succeed(Unit)
        }

        companion object {
            fun answering(decision: Either<NoDecision, ScreeningDecision>) = Check(decision, held = false)

            fun held(decision: Either<NoDecision, ScreeningDecision>) = Check(decision, held = true)
        }
    }

    private fun screening(
        check: Check,
        whenUnanswered: WhenUnanswered = WhenUnanswered.Decline,
        timeout: Duration = 2.seconds,
    ) = Screening.On(check, timeout, whenUnanswered)
}
