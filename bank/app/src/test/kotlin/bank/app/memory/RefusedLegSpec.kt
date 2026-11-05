package bank.app.memory

import bank.app.gbp
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe

/** A debit the account refused stays refused after a later deposit: the transfer was rejected, and nothing moves. */
object RefusedLegSpec : Suite() {
    init {
        test("a rejected transfer stays rejected after a later deposit, and the money is conserved") {
            holds {
                inMemory {
                    val cy = AccountId("cy")
                    val bob = AccountId("bob")
                    opened(cy, gbp(20_00))
                    opened(bob, gbp(0))

                    val rejected = transfer(TransferId("t-3"), cy, bob, gbp(38_63)).shouldBeRight()
                    deposit(cy, gbp(50_00), "d-1").shouldBeRight()

                    rejected.status shouldBe TransferStatus.Rejected
                    transferStatus(TransferId("t-3")).shouldBeRight().status shouldBe TransferStatus.Rejected
                    balanceOf(cy) shouldBe gbp(70_00)
                    balanceOf(bob) shouldBe gbp(0)
                }
            }
        }
    }
}
