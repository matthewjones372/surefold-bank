package bank.app.memory

import bank.app.Screener
import bank.app.Screening
import bank.app.gbp
import bank.domain.AccountId
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.WhenUnanswered
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.right
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A start with an id the saga already has: a caller's retry, the id reused for another transfer, or one mid-way. */
class SecondStartSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    @Test
    fun `the same start again after it settled is the same transfer, answered as it settled, and moves nothing more`() =
        inMemory {
            opened(ada, gbp(10_00))
            opened(bob, gbp(0))
            val first = transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()

            transfer(t1, ada, bob, gbp(7_00)).shouldBeRight() shouldBe first

            first.status shouldBe TransferStatus.Completed
            transferEvents(t1) shouldHaveSize 2
            balanceOf(ada) shouldBe gbp(3_00)
            balanceOf(bob) shouldBe gbp(7_00)
        }

    @Test
    fun `an id used again for a different transfer is refused, and moves nothing`() = inMemory {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
        transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()

        transfer(t1, ada, bob, gbp(1)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")
        transfer(t1, bob, ada, gbp(7_00)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")

        transferEvents(t1) shouldHaveSize 2
        balanceOf(ada) shouldBe gbp(3_00)
    }

    @Test
    fun `a different transfer under an id still moving is refused, and the same one waits for it`() {
        val asked = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val screening = Screening.On(
            Screener { _, _ ->
                asked.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ScreeningDecision(ScreeningOutcome.Approved, null, null, "ok").right()
            },
            5.seconds,
            WhenUnanswered.Approve,
        )
        inMemory(screening = screening) {
            opened(ada, gbp(10_00))
            opened(bob, gbp(0))
            val first = CompletableFuture<Unit>()
            Thread.ofVirtual().start {
                try {
                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()
                    first.complete(Unit)
                } catch (failed: Throwable) {
                    first.completeExceptionally(failed)
                }
            }
            check(entered.await(5, TimeUnit.SECONDS)) { "the transfer did not reach screening" }

            transfer(t1, ada, bob, gbp(1)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")
            val same = CompletableFuture<Unit>()
            Thread.ofVirtual().start {
                try {
                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()
                    same.complete(Unit)
                } catch (failed: Throwable) {
                    same.completeExceptionally(failed)
                }
            }
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            same.get(10, TimeUnit.SECONDS)

            asked.get() shouldBe 1
            transfer(t1, ada, bob, gbp(7_00)).shouldBeRight().status shouldBe TransferStatus.Completed
            transferEvents(t1).kinds() shouldBe listOf("Requested", "Screened", "DestinationCredited")
            balanceOf(ada) shouldBe gbp(3_00)
        }
    }
}
