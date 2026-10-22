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
 * A nudge while the saga waits on a leg it sent sends nothing (bank spec 0031): the leg's timer sends it again. A new
 * saga is nudged by itself as it starts, so before this every transfer sent its debit twice.
 */
class DrivenOnceSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    private fun InMemoryBank.accounts() {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
    }

    @Test
    fun `a nudge while the debit is unanswered sends no second debit, and the transfer settles`() = inMemory {
        accounts()
        network.next(Fault.HoldAnswer) { it is AccountCommand.Debit }
        val answers = start(t1, ada, bob, gbp(3_00))

        nudge(t1)
        nudge(t1)
        network.sent.filterIsInstance<AccountCommand.Debit>() shouldHaveSize 1

        network.releaseOldest()
        settleAll()
        answers.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
        network.sent.filterIsInstance<AccountCommand.Debit>() shouldHaveSize 1
        network.sent.filterIsInstance<AccountCommand.Credit>() shouldHaveSize 1
        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
    }

    @Test
    fun `a leg still unanswered at its timeout is sent again, nudged or not`() = inMemory {
        accounts()
        network.next(Fault.Drop) { it is AccountCommand.Debit }
        val answers = start(t1, ada, bob, gbp(3_00))

        nudge(t1)
        network.sent.filterIsInstance<AccountCommand.Debit>() shouldHaveSize 1

        advance()
        settleAll()
        answers.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
        network.sent.filterIsInstance<AccountCommand.Debit>() shouldHaveSize 2
        balanceOf(ada) shouldBe gbp(7_00)
    }

    @Test
    fun `a nudge while a debit's answer is lost leaves it to the timer, and the debit is applied once`() = inMemory {
        accounts()
        network.next(Fault.LoseAnswer) { it is AccountCommand.Debit }
        val answers = start(t1, ada, bob, gbp(3_00))

        // The debit was applied, its answer lost: the nudge finds the saga still on the debit, and leaves it to the
        // timer, which sends it again and hears it applied.
        nudge(t1)
        advance()
        settleAll()
        answers.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
    }
}
