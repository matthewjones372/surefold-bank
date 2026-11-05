package bank.app.memory

import bank.app.gbp
import bank.domain.AccountId
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * A transfer's request is written once. The durable saga reads its journal to resume (spec 0034), so a new id is no
 * longer assumed empty: the request step's check is what keeps a second start from appending another `Requested`.
 */
object FreshTransferSpec : Suite() {
    init {
        test("a transfer writes its request once, and a second start appends nothing more") {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))

                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed
                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                    transferEvents(t1).filterIsInstance<TransferEvent.Requested>() shouldHaveSize 1
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
                    balanceOf(ada) shouldBe gbp(7_00)
                    balanceOf(bob) shouldBe gbp(3_00)
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")
}
