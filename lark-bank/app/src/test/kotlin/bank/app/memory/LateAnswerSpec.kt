package bank.app.memory

import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A leg's answer that is lost, late or twice: the saga sends a leg again after its timeout, so each account may see a
 * leg more than once and the saga may hear more than one answer to it. Each leg is applied once (spec 0013), and only
 * an answer to the step the saga is on moves it.
 */
class LateAnswerSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    private fun InMemoryBank.accounts() {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
    }

    // Each spec moves 3.00 of ada's 10.00: little enough that a leg applied twice is paid, and shows here.
    private fun InMemoryBank.movedOnce() {
        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
        accountEvents(bob).filterIsInstance<AccountEvent.Credited>() shouldHaveSize 1
    }

    @Test
    fun `a leg whose answer was lost is sent again after its timeout, and applied once`() = inMemory {
        accounts()
        network.next(Fault.LoseAnswer) { it is AccountCommand.Debit }
        network.next(Fault.LoseAnswer) { it is AccountCommand.Credit }

        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Pending
        advance()
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Debited
        advance()

        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Completed
        movedOnce()
    }

    @Test
    fun `a leg that reaches its account twice is applied once`() = inMemory {
        accounts()
        network.next(Fault.Repeat, times = 2) { it is AccountCommand.Debit || it is AccountCommand.Credit }

        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

        transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
        movedOnce()
    }

    @Test
    fun `an answer to a step the saga has moved past is ignored`() = inMemory {
        accounts()
        // The debit's answer is late, so the debit goes again; the credit's is lost, so the saga waits on the credit.
        network.next(Fault.HoldAnswer) { it is AccountCommand.Debit }
        network.next(Fault.LoseAnswer) { it is AccountCommand.Credit }
        transfer(t1, ada, bob, gbp(3_00))
        advance()
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Debited

        // The first debit's answer, arriving while the saga is on the credit.
        network.releaseOldest()

        transferEvents(t1).kinds() shouldBe listOf("Requested")
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Debited
        advance()
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Completed
        transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
        movedOnce()
    }

    @Test
    fun `a leg that timed out and answers later moves the saga on, and its retry's answer is then the stale one`() =
        inMemory {
            accounts()
            // Both the debit and its retry are applied, and both answers are held back.
            network.next(Fault.HoldAnswer, times = 2) { it is AccountCommand.Debit }
            transfer(t1, ada, bob, gbp(3_00))
            advance()
            network.holding shouldBe 2
            transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Pending

            // The timed-out debit's own answer comes first: the saga is still on the debit, so it moves on.
            network.releaseOldest()
            transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Completed
            network.releaseOldest()

            transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
            movedOnce()
        }
}
