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
 * A debit the saga hears is not written (bank spec 0031): the account's `Debited` is its record. A saga that crashes
 * between hearing it and writing its credit starts again from `Requested`, sends the debit again, and the account
 * answers it as applied, so the money still moves once.
 */
class HeardDebitSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    private fun InMemoryBank.accounts() {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
    }

    @Test
    fun `a transfer writes its request and its credit, and nothing for its debit`() = inMemory {
        accounts()
        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed
        transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
    }

    @Test
    fun `a debit heard and then lost in a crash is sent again, answered as applied, and moves the money once`() =
        inMemory {
            accounts()
            // The credit never arrives, so the saga sits on it, Debited only in memory.
            network.next(Fault.Drop) { it is AccountCommand.Credit }
            start(t1, ada, bob, gbp(3_00))
            transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Debited
            transferEvents(t1).kinds() shouldBe listOf("Requested")

            crashSaga(t1)
            nudge(t1)
            settleAll()

            transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Completed
            network.sent.filterIsInstance<AccountCommand.Debit>() shouldHaveSize 2
            accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
            accountEvents(bob).filterIsInstance<AccountEvent.Credited>() shouldHaveSize 1
            balanceOf(ada) shouldBe gbp(7_00)
            balanceOf(bob) shouldBe gbp(3_00)
            transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
        }

    @Test
    fun `a credit refused after a crash still gives the debit back`() = inMemory {
        opened(ada, gbp(10_00))
        // bob is never opened, so the credit is refused, and the saga refunds what it debited.
        network.next(Fault.Drop) { it is AccountCommand.Credit }
        start(t1, ada, bob, gbp(3_00))
        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Debited

        crashSaga(t1)
        nudge(t1)
        settleAll()

        transferStatus(t1).shouldBeRight().status shouldBe TransferStatus.Refunded
        balanceOf(ada) shouldBe gbp(10_00)
        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
        transferEvents(t1).kinds() shouldBe listOf("Requested", "CreditRefused", "SourceRefunded")
    }
}
