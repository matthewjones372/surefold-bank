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
 * A transfer's legs are applied once (spec 0013). Asking the account again is the saga's own retry of an ask that
 * was not answered, and the account's idempotency is what keeps a second ask from moving the money twice (spec 0034).
 */
class DrivenOnceSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    @Test
    fun `a transfer debits and credits once, and settles`() = inMemory {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))

        transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

        accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
        accountEvents(bob).filterIsInstance<AccountEvent.Credited>() shouldHaveSize 1
        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
        transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
    }
}
