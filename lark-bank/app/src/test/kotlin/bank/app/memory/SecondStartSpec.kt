package bank.app.memory

import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountId
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
    fun `a start while the first is still moving waits for it, and both are answered once it settles`() = inMemory {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
        // The first debit is lost on the way, so the saga is still pending until its leg timer sends it again.
        network.next(Fault.Drop) { it is AccountCommand.Debit }

        val first = start(t1, ada, bob, gbp(7_00))
        val second = start(t1, ada, bob, gbp(7_00))
        val reused = start(t1, ada, bob, gbp(1))

        first.answers.shouldBeEmpty()
        second.answers.shouldBeEmpty()
        // A different transfer under the id is refused at once, moving or not.
        reused.answers.single().shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Pending

        advance()

        first.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
        second.answers.single() shouldBe first.answers.single()
        transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
        balanceOf(ada) shouldBe gbp(3_00)
    }
}
