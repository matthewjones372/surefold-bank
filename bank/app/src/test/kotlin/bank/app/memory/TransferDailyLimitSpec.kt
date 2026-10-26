package bank.app.memory

import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Bank spec 0027 through the saga: a transfer's debit counts against the source's daily limit, and only once. */
class TransferDailyLimitSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")

    private fun InMemoryBank.accounts() {
        opened(ada, gbp(500_00), dailyLimit = gbp(100_00))
        opened(bob, gbp(0))
    }

    @Test
    fun `a transfer whose debit is past the daily limit is rejected, saying what is left today, and moves nothing`() =
        inMemory {
            accounts()
            withdraw(ada, gbp(60_00), "w-1").shouldBeRight()

            val rejected = transfer(TransferId("t-1"), ada, bob, gbp(40_01)).shouldBeRight()

            rejected.status shouldBe TransferStatus.Rejected
            rejected.reason shouldBe
                "Account ada may pay out 100.00 GBP a day and has 40.00 GBP of that left today, not 40.01 GBP"
            transferEvents(TransferId("t-1")).kinds() shouldBe listOf("Requested", "Rejected")
            balanceOf(ada) shouldBe gbp(440_00)
            transfer(TransferId("t-2"), ada, bob, gbp(40_00)).shouldBeRight().status shouldBe TransferStatus.Completed
        }

    @Test
    fun `a debit sent again after its answer was lost counts against the limit once`() = inMemory {
        accounts()
        network.next(Fault.LoseAnswer) { it is AccountCommand.Debit }
        transfer(TransferId("t-1"), ada, bob, gbp(60_00))
        advance()
        transferStatus(TransferId("t-1")).shouldBeRight().status shouldBe TransferStatus.Completed

        // Charged twice, 120.00 would be over the limit and this would be rejected.
        transfer(TransferId("t-2"), ada, bob, gbp(40_00)).shouldBeRight().status shouldBe TransferStatus.Completed
        withdraw(ada, gbp(1), "w-1").shouldBeLeft() shouldBe
            AccountError.DailyLimitExceeded("ada", gbp(100_00), gbp(0), gbp(1))
        balanceOf(ada) shouldBe gbp(400_00)
    }
}
