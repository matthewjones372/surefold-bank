package bank.app

import bank.domain.AccountId
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

/**
 * Bank spec 0033: a node's payroll credits go through several producers, each account's through one of them, so an
 * account's credits keep their order while the producers share the work.
 */
object PayrollSendersSpec : Suite() {
    init {
        test("an account's credits always go through the same sender") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    accounts.forEach { account -> senderOf(account, 4) shouldBe senderOf(AccountId(account.value), 4) }
                }
            }
        }
        test("accounts spread evenly over the senders, and every sender is one there is") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val counts = accounts.groupingBy { senderOf(it, 4) }.eachCount()

                    counts.keys shouldContainOnly (0 until 4).toList()
                    // A quarter each, give or take a tenth of it.
                    counts.values.forEach { it shouldBeInRange 2_250..2_750 }
                }
            }
        }
        test("one sender takes every account, whatever its id hashes to") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    (accounts + AccountId("")).map { senderOf(it, 1) }.toSet() shouldBe setOf(0)
                }
            }
        }
    }

    private class Fixture {
        val accounts = (0 until 10_000).map { AccountId("acc-$it") }
    }
}
