package bank.app.memory

import bank.app.gbp
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * A debit the saga hears is not written (bank spec 0031): the account's `Debited` is its record. Running the saga
 * again after it has ended does not debit a second time — the step's own record, not a nudge, is what says it happened
 * (spec 0034).
 */
object HeardDebitSpec : Suite() {
    init {
        test("a transfer writes its request and its credit, and nothing for its debit") {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))
                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
                    accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
                }
            }
        }
        test("a transfer run again after it ended does not debit a second time") {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))
                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                    transfer(t1, ada, bob, gbp(3_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                    accountEvents(ada).filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
                    accountEvents(bob).filterIsInstance<AccountEvent.Credited>() shouldHaveSize 1
                    balanceOf(ada) shouldBe gbp(7_00)
                    balanceOf(bob) shouldBe gbp(3_00)
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")
}
