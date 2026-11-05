package bank.app.memory

import bank.app.gbp
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Each leg is applied once (spec 0013). A lost answer used to be a timer and a second tell; the durable saga retries
 * the ask itself, and the account answers a copy as already applied (spec 0034).
 */
class LateAnswerSpec {
    @Test
    fun `a transfer's debit and credit are each applied once`() = inMemory {
        val ada = AccountId("ada")
        val bob = AccountId("bob")
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))

        transfer(TransferId("t-1"), ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
        accountEvents(bob).filterIsInstance<AccountEvent.Credited>() shouldHaveSize 1
    }
}
