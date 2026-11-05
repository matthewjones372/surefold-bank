package bank.app.memory

import bank.app.gbp
import bank.domain.AccountError
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe

/** Bank spec 0027 through the saga: a transfer's debit counts against the source's daily limit, and only once. */
object TransferDailyLimitSpec : Suite() {
    init {
        test(
            "a transfer whose debit is past the daily limit is rejected, saying what is left today, and moves nothing",
        ) {
            holds {
                inMemory {
                    accounts()
                    withdraw(ada, gbp(60_00), "w-1").shouldBeRight()

                    val rejected = transfer(TransferId("t-1"), ada, bob, gbp(40_01)).shouldBeRight()

                    rejected.status shouldBe TransferStatus.Rejected
                    rejected.reason shouldBe
                        "Account ada may pay out 100.00 GBP a day and has 40.00 GBP of that left today, not 40.01 GBP"
                    transferEvents(TransferId("t-1")).kinds() shouldBe listOf("Requested", "Rejected")
                    balanceOf(ada) shouldBe gbp(440_00)
                    transfer(
                        TransferId("t-2"),
                        ada,
                        bob,
                        gbp(40_00),
                    ).shouldBeRight().status shouldBe TransferStatus.Completed
                }
            }
        }
        test("two transfers under the limit count once each, and a withdrawal past what is left is refused") {
            holds {
                inMemory {
                    accounts()
                    transfer(
                        TransferId("t-1"),
                        ada,
                        bob,
                        gbp(60_00),
                    ).shouldBeRight().status shouldBe TransferStatus.Completed
                    transfer(
                        TransferId("t-2"),
                        ada,
                        bob,
                        gbp(40_00),
                    ).shouldBeRight().status shouldBe TransferStatus.Completed
                    withdraw(ada, gbp(1), "w-1").shouldBeLeft() shouldBe
                        AccountError.DailyLimitExceeded("ada", gbp(100_00), gbp(0), gbp(1))
                    balanceOf(ada) shouldBe gbp(400_00)
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")

    private fun InMemoryBank.accounts() {
        opened(ada, gbp(500_00), dailyLimit = gbp(100_00))
        opened(bob, gbp(0))
    }
}
