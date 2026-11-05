package bank.app.memory

import bank.app.BTC
import bank.app.gbp
import bank.domain.Account
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.TransferId
import bank.domain.TransferStatus
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Spec 0001's saga, in memory: how a transfer settles, whichever way its accounts answer. */
object SettlingSpec : Suite() {
    init {
        test("a transfer completes, moving the amount once, and each account forgets its leg after") {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))

                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight().status shouldBe TransferStatus.Completed

                    balanceOf(ada) shouldBe gbp(3_00)
                    balanceOf(bob) shouldBe gbp(7_00)
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "DestinationCredited")
                    // Settled, its closes have reached both accounts (spec 0013).
                    accountEvents(ada).last().shouldBeInstanceOf<AccountEvent.LegsClosed>()
                    accountState(ada).shouldBeInstanceOf<Account.Open>().legs.shouldBeEmpty()
                    accountState(bob).shouldBeInstanceOf<Account.Open>().legs.shouldBeEmpty()
                }
            }
        }
        test("a debit the source cannot cover rejects the transfer with the account's reason, and nothing moves") {
            holds {
                inMemory {
                    opened(ada, gbp(1_00))
                    opened(bob, gbp(0))

                    val rejected = transfer(t1, ada, bob, gbp(5_00)).shouldBeRight()

                    rejected.status shouldBe TransferStatus.Rejected
                    rejected.reason shouldBe "Account ada has 1.00 GBP, not 5.00 GBP"
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "Rejected")
                    balanceOf(ada) shouldBe gbp(1_00)
                    accountEvents(bob).kinds() shouldBe listOf("Opened")
                }
            }
        }
        test("a credit the destination refuses is given back to the source, which ends where it began") {
            holds {
                inMemory {
                    opened(ada, gbp(1_00))
                    // In another currency, so it refuses a credit in pounds once the source has paid.
                    opened(bob, Money.ofMinor(50_000_000, BTC))

                    val refunded = transfer(t1, ada, bob, gbp(50)).shouldBeRight()

                    refunded.status shouldBe TransferStatus.Refunded
                    refunded.reason shouldBe "Account bob is in BTC, not GBP"
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "CreditRefused", "SourceRefunded")
                    balanceOf(ada) shouldBe gbp(1_00)
                    balanceOf(bob) shouldBe Money.ofMinor(50_000_000, BTC)
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")
}
